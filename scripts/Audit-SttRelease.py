"""Historical Vosk-only snapshot audit; use Audit-SherpaAsrOnly.py for the current release.

A successful build is not publication approval. This script deliberately rejects Sherpa.
"""
import argparse
import hashlib
import json
import pathlib
import sys
import xml.etree.ElementTree as ET
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=pathlib.Path, required=True)
    parser.add_argument("--merged-manifest", type=pathlib.Path, required=True)
    parser.add_argument("--report", type=pathlib.Path, required=True)
    parser.add_argument("--for-publication", action="store_true")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    research = root / "docs/INVESTIGACION/F3.06-vosk"
    licenses = json.loads((research / "license-manifest.json").read_text(encoding="utf-8-sig"))
    native = json.loads((research / "native-audit.json").read_text(encoding="utf-8-sig"))
    app_native = json.loads((research / "app-native-audit.json").read_text(encoding="utf-8-sig"))
    native["artifacts"].extend(app_native["artifacts"])
    known = {lib["path"].replace("jni/", "lib/", 1): lib
             for artifact in native["artifacts"] for lib in artifact["native_libraries"]}
    failures, binaries = [], []
    permissions = [node.get("{http://schemas.android.com/apk/res/android}name")
                   for node in ET.parse(args.merged_manifest).getroot().findall("uses-permission")]
    if "android.permission.INTERNET" in permissions:
        failures.append("Unexpected INTERNET permission")
    with zipfile.ZipFile(args.apk) as apk:
        names = apk.namelist()
        forbidden = [n for n in names if any(term in n.lower() for term in ("sherpa", "espeak", "ucd-tools", "piper-phonemize"))
                     or n.startswith("assets/stt/nemo-") or n.endswith("silero_vad.onnx")]
        if forbidden:
            failures.append("Research Sherpa components remain in release")
        for notice in licenses["notices"]:
            entry = "assets/stt/vosk-licenses/" + pathlib.Path(notice["file"]).name
            if entry not in names or sha(apk.read(entry)) != notice["sha256"]:
                failures.append("Missing/changed original notice: " + entry)
        if "assets/stt/VOSK-ATTRIBUTIONS.md" not in names:
            failures.append("Missing attribution index")
        for name in names:
            if not name.startswith("lib/") or not name.endswith(".so"):
                continue
            digest = sha(apk.read(name))
            original = known.get(name)
            exact = bool(original and original["sha256"] == digest)
            if not exact:
                failures.append("Unknown or transformed native library: " + name)
            record = dict(path=name, sha256=digest, matchesInspectedArtifact=exact)
            if original:
                record["loadSegments"] = original.get("load_segments", [])
                record["gnuRelro"] = original.get("gnu_relro_segments", [])
                record["needed"] = original.get("dt_needed", [])
                if original["bits"] == 64:
                    relro_ok = all(s["end_mod_16384"] == 0 for s in record["gnuRelro"])
                    load_ok = all(s["p_align"] >= 16384 and s["mod16k_congruent"] for s in record["loadSegments"])
                    if not relro_ok or not load_ok:
                        failures.append("16 KB ELF compatibility unresolved: " + name)
            binaries.append(record)
    # The permissive licenses do not require reproducible source, but the project audit also
    # requires attributable native toolchain/source snapshots before publication.
    failures.append("Exact prebuilt native Kaldi/OpenFst/NDK build traceability remains unresolved")
    report = dict(apk=str(args.apk), bytes=args.apk.stat().st_size, sha256=sha(args.apk.read_bytes()),
                  jnaLicenseChoice="Apache-2.0", permissions=permissions, researchComponents=forbidden,
                  noticeCount=len(licenses["notices"]), nativeLibraries=binaries,
                  identifiedLicenseFinding="No incompatible/GPL component identified in the Vosk route; original notices packaged",
                  publicationReady=not failures, publicationBlockers=failures,
                  scope="STT artifacts only; not a legal certification of all app dependencies")
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("bytes", "noticeCount", "researchComponents", "publicationReady", "publicationBlockers")}, indent=2))
    return 1 if args.for_publication and failures else 0


if __name__ == "__main__":
    sys.exit(main())
