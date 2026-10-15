"""Read-only local artifact/build audit; this is not a legal or publication certification.

Only the requested JSON report is written. No native code, shell command, remote resource,
audio, reference or transcript is executed/read. A research audit can pass technical checks
while manual release gates remain pending. --for-publication also enforces those gates.
"""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import hashlib
import json
import pathlib
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile


ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
RUNTIME_NAMES = ("libsherpa-onnx-jni.so", "libonnxruntime.so")
PAGE = 16384
FORBIDDEN_NATIVE = re.compile(r"vosk|jnidispatch|espeak|piper|(?:^|[-_/])(?:lib)?ucd(?:[-_.]|$)|tts|diarization", re.I)
FORBIDDEN_EXPORT = re.compile(r"tts|diarization|espeak|piper[-_]?phon|(?:^|_)ucd(?:_|$)", re.I)
FORBIDDEN_SOURCE = re.compile(r"espeak|piper[-_]?phon|(?:^|[/_-])(?:lib)?ucd(?:[-_./]|$)|hclust", re.I)
ANDROID = "{http://schemas.android.com/apk/res/android}"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def local_path(value):
    if "://" in str(value):
        raise argparse.ArgumentTypeError("Only local filesystem paths are accepted")
    return pathlib.Path(value).expanduser().resolve()


class Elf:
    """Bounds-checked ELF32/64 reader, including section-free PT_DYNAMIC fallback."""
    def __init__(self, data):
        self.data = data
        if len(data) < 52 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
            raise ValueError("Unsupported/non-ELF native artifact")
        self.bits = 32 if data[4] == 1 else 64
        self.endian = "<" if data[5] == 1 else ">"
        header = self.unpack("HHIIIIIHHHHHH" if self.bits == 32 else "HHIQQQIHHHHHH", 16)
        self.machine = header[1]
        phoff, shoff, phentsize, phnum, shentsize, shnum = header[4], header[5], header[8], header[9], header[10], header[11]
        self.programs, self.sections = [], []
        for index in range(phnum):
            values = self.unpack("IIIIIIII" if self.bits == 32 else "IIQQQQQQ", phoff + index * phentsize)
            if self.bits == 32:
                kind, offset, vaddr, _, filesz, memsz, flags, align = values
            else:
                kind, flags, offset, vaddr, _, filesz, memsz, align = values
            self.range(offset, filesz)
            self.programs.append(dict(type=kind, offset=offset, vaddr=vaddr, filesz=filesz, memsz=memsz, flags=flags, align=align))
        for index in range(shnum):
            values = self.unpack("IIIIIIIIII" if self.bits == 32 else "IIQQQQIIQQ", shoff + index * shentsize)
            self.sections.append(dict(type=values[1], offset=values[4], size=values[5], link=values[6], entsize=values[9]))
        self.dynamic = collections.defaultdict(list)
        dynamic_size = 8 if self.bits == 32 else 16
        for segment in self.programs:
            if segment["type"] != 2:
                continue
            for offset in range(segment["offset"], segment["offset"] + segment["filesz"], dynamic_size):
                tag, value = self.unpack("iI" if self.bits == 32 else "qQ", offset)
                if tag == 0:
                    break
                self.dynamic[tag].append(value)

    def range(self, offset, size):
        if offset < 0 or size < 0 or offset + size > len(self.data):
            raise ValueError("ELF range is outside the artifact")
        return offset

    def unpack(self, format_, offset):
        size = struct.calcsize(self.endian + format_)
        self.range(offset, size)
        return struct.unpack_from(self.endian + format_, self.data, offset)

    def address(self, vaddr, size=1):
        for segment in self.programs:
            if segment["type"] == 1 and segment["vaddr"] <= vaddr and vaddr + size <= segment["vaddr"] + segment["filesz"]:
                return self.range(segment["offset"] + vaddr - segment["vaddr"], size)
        raise ValueError("ELF virtual address is not file-backed")

    def text(self, offset, limit):
        self.range(offset, limit)
        end = self.data.find(b"\0", offset, offset + limit)
        if end < 0:
            raise ValueError("ELF string is unterminated")
        return self.data[offset:end].decode("utf-8", errors="replace")

    def dyn_string(self, relative):
        if 5 not in self.dynamic or 10 not in self.dynamic:
            raise ValueError("ELF dynamic string table is absent")
        size = self.dynamic[10][0]
        if relative >= size:
            raise ValueError("ELF dynamic string index is outside its table")
        return self.text(self.address(self.dynamic[5][0] + relative), size - relative)

    def needed(self):
        return sorted({self.dyn_string(value) for value in self.dynamic[1]})

    def symbol_count(self):
        if 4 in self.dynamic:  # DT_HASH: nbucket, nchain.
            return self.unpack("II", self.address(self.dynamic[4][0], 8))[1]
        if 0x6FFFFEF5 in self.dynamic:  # DT_GNU_HASH: walk the last populated bucket chain.
            base = self.address(self.dynamic[0x6FFFFEF5][0], 16)
            nbuckets, first_symbol, bloom_size, _ = self.unpack("IIII", base)
            buckets = base + 16 + bloom_size * (self.bits // 8)
            self.range(buckets, nbuckets * 4)
            starts = [self.unpack("I", buckets + index * 4)[0] for index in range(nbuckets)]
            last = max(starts, default=0)
            if last < first_symbol:
                return first_symbol
            chains = buckets + nbuckets * 4
            index = last
            while not self.unpack("I", chains + (index - first_symbol) * 4)[0] & 1:
                index += 1
                if index > len(self.data) // 4:
                    raise ValueError("ELF GNU hash chain is invalid")
            return index + 1
        # A stripped section table is unnecessary when hash tables are present.
        raise ValueError("ELF dynamic symbol count has no section/hash evidence")

    def exports(self):
        result = set()
        tables = [section for section in self.sections if section["type"] == 11]
        if tables:
            for table in tables:
                if not table["entsize"] or table["link"] >= len(self.sections):
                    raise ValueError("ELF dynsym metadata is invalid")
                strings = self.sections[table["link"]]
                self.range(table["offset"], table["size"])
                self.range(strings["offset"], strings["size"])
                for index in range(table["size"] // table["entsize"]):
                    symbol = self.symbol(table["offset"] + index * table["entsize"])
                    if self.exported(symbol):
                        if symbol["name"] >= strings["size"]:
                            raise ValueError("ELF symbol string is outside table")
                        result.add(self.text(strings["offset"] + symbol["name"], strings["size"] - symbol["name"]))
        elif 6 in self.dynamic:
            stride = self.dynamic.get(11, [16 if self.bits == 32 else 24])[0]
            count = self.symbol_count()
            base = self.address(self.dynamic[6][0], count * stride)
            for index in range(count):
                symbol = self.symbol(base + index * stride)
                if self.exported(symbol):
                    result.add(self.dyn_string(symbol["name"]))
        else:
            raise ValueError("ELF has no inspectable dynamic symbol table")
        return sorted(result - {""})

    def symbol(self, offset):
        if self.bits == 32:
            name, _, _, info, other, section = self.unpack("IIIBBH", offset)
        else:
            name, info, other, section, _, _ = self.unpack("IBBHQQ", offset)
        return dict(name=name, info=info, other=other, section=section)

    @staticmethod
    def exported(symbol):
        return symbol["section"] != 0 and symbol["info"] >> 4 in (1, 2) and symbol["other"] & 3 in (0, 3)

    def page_alignment(self):
        loads = [{key: segment[key] for key in ("offset", "vaddr", "filesz", "memsz", "align")} for segment in self.programs if segment["type"] == 1]
        relro = [dict(vaddr=segment["vaddr"], memsz=segment["memsz"], end=segment["vaddr"] + segment["memsz"]) for segment in self.programs if segment["type"] == 0x6474E552]
        if self.bits == 32:
            return dict(applicable=False, reason="16 KB ELF gate applies to 64-bit libraries here", loadSegments=loads, relro=relro)
        for load in loads:
            load["aligned16k"] = load["align"] >= PAGE and load["offset"] % PAGE == load["vaddr"] % PAGE
        for region in relro:
            region["endAligned16k"] = region["end"] % PAGE == 0
        return dict(applicable=True, loadSegments=loads, relro=relro, relroPresent=bool(relro),
                    passed=bool(loads) and all(item["aligned16k"] for item in loads) and all(item["endAligned16k"] for item in relro))


class Audit:
    def __init__(self, root):
        self.root = root
        self.checks, self.issues, self.publication_blockers = [], [], []

    def check(self, name, passed, evidence, publication_only=False):
        record = dict(check=name, status="passed" if passed else "failed", evidence=evidence)
        self.checks.append(record)
        if not passed:
            (self.publication_blockers if publication_only else self.issues).append(record)
        return passed

    def path(self, value):
        path = pathlib.Path(value)
        return path if path.is_absolute() else self.root / path

    def asset(self, archive, path, expected_hash=None, expected_bytes=None):
        names = archive.namelist()
        if names.count(path) != 1:
            return dict(path=path, passed=False, problem="Asset missing or duplicated")
        blob = archive.read(path)
        actual = sha256(blob)
        return dict(path=path, bytes=len(blob), sha256=actual,
                    passed=(expected_hash is None or actual == expected_hash) and (expected_bytes is None or len(blob) == expected_bytes))

    def native_inventory(self, archive, prefix, label):
        names = archive.namelist()
        candidates = [name for name in names if name.startswith(prefix + "/") and name.endswith(".so")]
        self.check(label + " native paths are unique", len(candidates) == len(set(candidates)), {"duplicates": [name for name, count in collections.Counter(candidates).items() if count > 1]})
        forbidden = [name for name in candidates if FORBIDDEN_NATIVE.search(name)]
        self.check(label + " excludes removed native components", not forbidden, forbidden)
        inventory = {}
        for name in sorted(set(candidates)):
            parts = name.split("/")
            if len(parts) != 3:
                self.check(label + " native path format", False, name)
                continue
            blob = archive.read(name)
            row = dict(path=name, abi=parts[1], library=parts[2], bytes=len(blob), sha256=sha256(blob))
            try:
                elf = Elf(blob)
                exports = elf.exports()
                row.update(bits=elf.bits, machine=elf.machine, needed=elf.needed(), exportCount=len(exports), pageAlignment=elf.page_alignment())
                expected_machine = {"armeabi-v7a": (32, 40), "arm64-v8a": (64, 183), "x86": (32, 3), "x86_64": (64, 62)}.get(parts[1])
                self.check(label + " " + name + " matches its ABI", expected_machine == (elf.bits, elf.machine), {"bits": elf.bits, "machine": elf.machine, "expected": expected_machine})
                excluded = [value for value in exports if FORBIDDEN_EXPORT.search(value)]
                row["excludedExports"] = excluded
                if parts[2] in RUNTIME_NAMES:
                    self.check(label + " " + name + " excludes callable removed components", not excluded and not any(FORBIDDEN_NATIVE.search(value) for value in row["needed"]), {"exports": excluded, "needed": row["needed"]})
                if parts[2] == RUNTIME_NAMES[0]:
                    required = ("Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_newFromFile", "Java_com_k2fsa_sherpa_onnx_Vad_compute")
                    self.check(label + " " + name + " retains ASR/VAD and dynamic ORT", all(value in exports for value in required) and "libonnxruntime.so" in row["needed"], {"requiredExportsPresent": {value: value in exports for value in required}, "needed": row["needed"]})
                if parts[2] == RUNTIME_NAMES[1]:
                    self.check(label + " " + name + " exports ORT C API", "OrtGetApiBase" in exports, {"OrtGetApiBase": "OrtGetApiBase" in exports})
                if label == "APK" and elf.bits == 64:
                    self.check("Whole APK 16 KB ELF: " + name, row["pageAlignment"]["passed"], row["pageAlignment"], publication_only=True)
            except (ValueError, struct.error, IndexError) as error:
                row["parseError"] = str(error)
                self.check(label + " parses ELF: " + name, False, str(error))
            inventory[name] = row
        for abi in ABIS:
            for library in RUNTIME_NAMES:
                name = prefix + "/" + abi + "/" + library
                self.check(label + " includes exactly one " + abi + "/" + library, names.count(name) == 1, {"count": names.count(name)})
        if label == "AAR":
            unexpected = [name for name in candidates if name.split("/")[-1] not in RUNTIME_NAMES]
            self.check("ASR-only AAR contains only the two intended native libraries per ABI", not unexpected, unexpected)
        return inventory

    def build_evidence(self, build_root):
        records = []
        for abi in ABIS:
            directory = build_root / abi
            cache_file, commands_file = directory / "CMakeCache.txt", directory / "compile_commands.json"
            record = dict(abi=abi, directory=str(directory), status="incomplete")
            if not cache_file.is_file() or not commands_file.is_file():
                record["problem"] = "Root CMake cache or actual compilation database is missing"
                self.check(abi + " build source-exclusion evidence available", False, record)
                records.append(record)
                continue
            cache = {}
            for line in cache_file.read_text(encoding="utf-8-sig", errors="replace").splitlines():
                match = re.match(r"^([^/#][^:=]*):[^=]+=(.*)$", line)
                if match:
                    cache[match[1]] = match[2]
            flags = {key: cache.get(key) for key in ("SHERPA_ONNX_ENABLE_TTS", "SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION", "ANDROID_ABI", "ANDROID_PLATFORM", "BUILD_SHARED_LIBS", "ANDROID_STL")}
            disabled = all((flags[key] or "").upper() in ("OFF", "FALSE", "0", "NO") for key in ("SHERPA_ONNX_ENABLE_TTS", "SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION"))
            self.check(abi + " disables TTS/diarization in effective CMake cache", disabled, flags)
            self.check(abi + " targets Android API 24", flags["ANDROID_ABI"] == abi and flags["ANDROID_PLATFORM"] in ("android-24", "24"), flags)
            commands = json.loads(commands_file.read_text(encoding="utf-8-sig"))
            sources = [str(item.get("file", "")).replace("\\", "/") for item in commands]
            bad_sources = [source for source in sources if FORBIDDEN_SOURCE.search(source)]
            api_targets = set()
            for item in commands:
                command = item.get("command") or " ".join(item.get("arguments", []))
                api_targets.update(int(match) for match in re.findall(r"(?:--target=|-target\s+)\S*android(?:eabi)?(\d+)(?=[\s\"']|$)", command))
            self.check(abi + " actual compiler inputs omit eSpeak/Piper/UCD/hclust", bool(sources) and not bad_sources, {"compiledSourceCount": len(sources), "excludedSourceInputs": bad_sources, "compileCommandsSha256": file_hash(commands_file)})
            self.check(abi + " compiler triples target API 24", api_targets == {24}, {"observedApiLevels": sorted(api_targets)})
            maps = []
            # Skip dependency source/download trees: only final build link maps are relevant.
            pending = [directory]
            while pending:
                current = pending.pop()
                for child in current.iterdir():
                    if child.is_dir() and child.name != "_deps":
                        pending.append(child)
                    elif child.is_file() and child.suffix.lower() == ".map":
                        maps.append(child)
            references, map_evidence = set(), []
            for map_file in maps:
                content = map_file.read_text(encoding="utf-8-sig", errors="replace")
                # Restrict detection to linker archive/member/object identities, not comments,
                # disabled -D flags, upstream option names or arbitrary binary strings.
                refs = set(re.findall(r"[^\s<>]+\.a\([^\r\n)]+\)", content))
                refs.update(re.findall(r"[^\s<>]+\.(?:o|obj)(?=[:\s)]|$)", content))
                references.update(refs)
                map_evidence.append(dict(path=str(map_file), sha256=file_hash(map_file), objectReferenceCount=len(refs)))
            bad_references = sorted(value for value in references if FORBIDDEN_SOURCE.search(value.replace("\\", "/")))
            link_proven = bool(map_evidence) and bool(references) and not bad_references
            self.check(abi + " actual linked objects omit eSpeak/Piper/UCD/hclust", link_proven, {"maps": map_evidence, "objectReferenceCount": len(references), "excludedObjectReferences": bad_references})
            record.update(cacheSha256=file_hash(cache_file), configuration=flags, compileCommandsSha256=file_hash(commands_file), compiledSources=len(sources), linkedObjectReferences=len(references), maps=map_evidence,
                          status="passed" if disabled and flags["ANDROID_ABI"] == abi and flags["ANDROID_PLATFORM"] in ("android-24", "24") and api_targets == {24} and sources and not bad_sources and link_proven else "failed")
            records.append(record)
        return dict(status="passed" if all(row["status"] == "passed" for row in records) else "incomplete-or-failed", abis=records,
                    limitation="Compilation/link identity and flags are evidence, not bit-for-bit reproducibility or proof of every incorporated header license")

    def packaged_notices(self, apk):
        manifest_path = self.root / "app/src/main/assets/stt/licenses-manifest.json"
        if not manifest_path.is_file():
            self.check("Packaged license manifest exists", False, str(manifest_path))
            return dict(status="missing")
        blob = manifest_path.read_bytes()
        manifest = json.loads(blob.decode("utf-8-sig"))
        rows = [self.asset(apk, "assets/stt/licenses-manifest.json", sha256(blob), len(blob))]
        notices = manifest.get("notices", manifest.get("artifacts", []))
        for notice in notices:
            name = notice.get("file") or notice.get("path") or notice.get("notice", {}).get("path")
            hash_ = notice.get("sha256") or notice.get("notice", {}).get("sha256")
            size = notice.get("bytes", notice.get("notice", {}).get("bytes"))
            if not name or not hash_:
                rows.append(dict(path=name, passed=False, problem="Notice lacks pinned path/SHA256"))
                continue
            normalized = str(name).replace("\\", "/")
            asset = "assets/" + normalized.split("/assets/", 1)[1] if "/assets/" in normalized else "assets/stt/licenses/" + pathlib.PurePosixPath(normalized).name
            if normalized.startswith("assets/"):
                asset = normalized
            elif normalized in ("ATTRIBUTIONS.md", "NOTICE", "NOTICE.txt"):
                asset = "assets/stt/" + normalized
            rows.append(self.asset(apk, asset, hash_, size))
        attribution_path = self.root / "app/src/main/assets/stt/ATTRIBUTIONS.md"
        if attribution_path.is_file():
            attributions = attribution_path.read_bytes()
            rows.append(self.asset(apk, "assets/stt/ATTRIBUTIONS.md", sha256(attributions), len(attributions)))
        else:
            rows.append(dict(path="assets/stt/ATTRIBUTIONS.md", passed=False, problem="Local attributions missing"))
        self.check("All pinned notices and local attributions are packaged byte-for-byte", bool(notices) and all(row["passed"] for row in rows), rows)
        return dict(status="passed" if notices and all(row["passed"] for row in rows) else "failed", assets=rows, attributionsHashBasis="Exact retained local attribution bytes; notice hashes come from the manifest")

    def packaged_models(self, apk):
        path = self.root / "app/src/main/assets/stt/resources-manifest.json"
        if not path.is_file():
            self.check("Model resource manifest exists", False, str(path))
            return dict(status="missing")
        blob = path.read_bytes()
        manifest = json.loads(blob.decode("utf-8-sig"))
        rows = [self.asset(apk, "assets/stt/resources-manifest.json", sha256(blob), len(blob))]
        count = 0
        for resource in manifest.get("resources", []):
            path_ = resource.get("path", "").replace("\\", "/")
            if "/assets/" not in path_:
                continue  # Runtime AAR identity is checked separately; this is the model audit.
            asset = "assets/" + path_.split("/assets/", 1)[1]
            rows.append(self.asset(apk, asset, resource.get("sha256"), resource.get("bytes")))
            count += 1
        self.check("Pinned original model/tokens/VAD resources match APK bytes", count >= 6 and all(row["passed"] for row in rows), rows)
        return dict(status="passed" if count >= 6 and all(row["passed"] for row in rows) else "failed", assets=rows)

    def sources(self):
        path = self.root / "docs/INVESTIGACION/F3.06-asr-only/sources.json"
        if not path.is_file():
            self.check("Source/license inventory exists", False, str(path))
            return {}, []
        manifest = json.loads(path.read_text(encoding="utf-8-sig"))
        identities = []
        for item in manifest.get("sherpa", {}).get("file_evidence", []):
            spec = item.get("local_file", {})
            file = self.path(spec.get("path", ""))
            valid = file.is_file() and file_hash(file) == spec.get("sha256") and file.stat().st_size == spec.get("bytes")
            identities.append(dict(path=str(file), passed=valid))
        self.check("Pinned critical source/recipe identities remain intact", bool(identities) and all(row["passed"] for row in identities), identities)
        licenses = [dict(component="sherpa-onnx", license=manifest.get("sherpa", {}).get("license"), revision=manifest.get("sherpa", {}).get("revision"))]
        licenses += [dict(component=item.get("name"), license=item.get("license"), revision=item.get("version_or_revision")) for item in manifest.get("dependencies", [])]
        licenses.append(dict(component="ONNX Runtime prebuilt", license=manifest.get("onnxruntime", {}).get("license"), revision=manifest.get("onnxruntime", {}).get("version")))
        licenses += [dict(component=item.get("component"), license=item.get("license"), scope=item.get("distribution_scope"))
                     for item in manifest.get("required_notice_files", [])
                     if item.get("component") and item.get("license")]
        return dict(path=str(path), sha256=file_hash(path), scope=manifest.get("scope"), declaredAuditComplete=manifest.get("distributionAuditComplete"), sourceIdentities=identities,
                    excludedBySourceConfiguration=manifest.get("excluded_by_source_configuration", []), limits=manifest.get("limits", [])), licenses


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=local_path, required=True)
    parser.add_argument("--apk", type=local_path, required=True)
    parser.add_argument("--merged-manifest", type=local_path, required=True)
    parser.add_argument("--build-root", type=local_path, required=True)
    parser.add_argument("--report", type=local_path, required=True)
    parser.add_argument("--for-publication", action="store_true")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    audit = Audit(root)
    report = dict(schemaVersion=1, generatedAtUtc=dt.datetime.now(dt.timezone.utc).isoformat(), mode="publication" if args.for_publication else "local-research",
                  scope="Artifact/ELF, compiled-source/link-map, model and retained-notice audit; no audio, runtime execution, signing or publication",
                  legalCertification=False, distributionApproved=False,
                  inputs={key: str(getattr(args, key)) for key in ("aar", "apk", "merged_manifest", "build_root")})
    try:
        with zipfile.ZipFile(args.aar) as aar, zipfile.ZipFile(args.apk) as apk:
            report["aar"] = dict(path=str(args.aar), bytes=args.aar.stat().st_size, sha256=file_hash(args.aar), libraries=audit.native_inventory(aar, "jni", "AAR"))
            report["apk"] = dict(path=str(args.apk), bytes=args.apk.stat().st_size, sha256=file_hash(args.apk), libraries=audit.native_inventory(apk, "lib", "APK"))
            identity = []
            for abi in ABIS:
                for library in RUNTIME_NAMES:
                    source = report["aar"]["libraries"].get("jni/" + abi + "/" + library, {})
                    destination = report["apk"]["libraries"].get("lib/" + abi + "/" + library, {})
                    identity.append(dict(abi=abi, library=library, aarSha256=source.get("sha256"), apkSha256=destination.get("sha256"), passed=bool(source.get("sha256")) and source.get("sha256") == destination.get("sha256")))
            audit.check("APK ships byte-identical audited AAR native files", all(row["passed"] for row in identity), identity)
            report["nativeAarApkIdentity"] = identity
            report["notices"] = audit.packaged_notices(apk)
            report["modelResources"] = audit.packaged_models(apk)
        manifest = ET.parse(args.merged_manifest).getroot()
        sdk, application = manifest.find("uses-sdk"), manifest.find("application")
        permissions = [node.get(ANDROID + "name") for node in manifest.findall("uses-permission")]
        min_sdk = sdk.get(ANDROID + "minSdkVersion") if sdk is not None else None
        manifest_row = dict(sha256=file_hash(args.merged_manifest), minSdk=min_sdk, permissions=permissions,
                            debuggable=application.get(ANDROID + "debuggable", "false") if application is not None else None,
                            testOnly=application.get(ANDROID + "testOnly", "false") if application is not None else None)
        report["mergedManifest"] = manifest_row
        audit.check("Merged application targets minSdk 24", min_sdk == "24", manifest_row)
        audit.check("Merged application does not request Internet permission", "android.permission.INTERNET" not in permissions, permissions)
        if manifest_row["debuggable"] == "true" or manifest_row["testOnly"] == "true":
            audit.publication_blockers.append(dict(check="Final release artifact identity", status="pending", evidence="Inspected APK is debug/test-only; audit the signed release candidate separately"))
        report["sourceExclusionGate"] = audit.build_evidence(args.build_root)
        report["sourceInventory"], report["identifiedLicenses"] = audit.sources()
    except (OSError, ValueError, KeyError, TypeError, struct.error, zipfile.BadZipFile, ET.ParseError) as error:
        audit.check("Audit inputs are readable and well formed", False, {"type": type(error).__name__, "message": str(error)})
    report["checks"] = audit.checks
    report["technicalIssues"] = audit.issues
    report["publicationBlockers"] = audit.publication_blockers
    report["technicalChecksPassed"] = not audit.issues
    report["technicalChecksExcludePublicationOnlyChecks"] = True
    native_rows = list(report.get("apk", {}).get("libraries", {}).values())
    page_rows = [row for row in native_rows if row.get("bits") == 64]
    report["wholeAppNative16kPassed"] = (
        all(row["pageAlignment"]["passed"] for row in page_rows)
        if page_rows and not any("parseError" in row for row in native_rows) else None
    )
    report["manualReleaseGates"] = [
        dict(gate="Execution on every shipped ABI and minimum API", status="not-established-by-file-audit", requiredEvidence="Run native model/VAD/cancellation tests on all distributed ABIs, including minSdk 24; this script does not infer execution from ELF presence"),
        dict(gate="Execution on a real/emulated 16 KB page environment", status="not-established-by-file-audit", requiredEvidence="Record getconf PAGE_SIZE=16384 and actual ASR/VAD/resource tests; ELF alignment alone is insufficient"),
        dict(gate="License obligations and corresponding source availability", status="manual-review-required", requiredEvidence="Review exact incorporated source/header conditions (including MPL and LLVM exceptions), attribution/model modifications and durable source availability; ORT notice supersets are not an exact SBOM"),
        dict(gate="Owner signing and publication approval", status="owner-action-required", requiredEvidence="Approve channel/license choices and exact signed APK/AAB identity; this read-only audit neither signs nor authorizes publication"),
    ]
    report["publicationReady"] = not audit.issues and not audit.publication_blockers and not report["manualReleaseGates"]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(report=str(args.report), technicalChecksPassed=report["technicalChecksPassed"], technicalIssues=len(audit.issues), publicationBlockers=len(audit.publication_blockers), manualReleaseGates=len(report["manualReleaseGates"]), publicationReady=report["publicationReady"])))
    return 1 if audit.issues else 2 if args.for_publication and not report["publicationReady"] else 0


if __name__ == "__main__":
    sys.exit(main())
