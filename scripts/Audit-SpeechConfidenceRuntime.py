"""Read-only audit of the debug token-score runtime; no publication certification."""
from __future__ import annotations

import argparse
import importlib.util
import json
import pathlib
import zipfile
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("asr_audit", ROOT / "scripts/Audit-SherpaAsrOnly.py")
asr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asr)
BRIDGE = "Java_com_yezvi_assistant_data_speech_SherpaTokenScores_readTokenLogProbabilities"
BASELINE_SHA = "afaf0053ec5a94513cbfb08c4b5e7b2d995cad3d07f5dbd93ac4d8c59c506396"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--experimental-aar", type=asr.local_path, required=True)
    parser.add_argument("--baseline-aar", type=asr.local_path, required=True)
    parser.add_argument("--debug-apk", type=asr.local_path, required=True)
    parser.add_argument("--merged-manifest", type=asr.local_path, required=True)
    parser.add_argument("--build-root", type=asr.local_path, required=True)
    parser.add_argument("--report", type=asr.local_path, required=True)
    args = parser.parse_args()
    audit = asr.Audit(ROOT)
    report = dict(format=1, scope="Debug-only runtime, native/source identity and privacy checks",
                  distributionApproved=False, legalCertification=False)
    with zipfile.ZipFile(args.experimental_aar) as experimental, \
            zipfile.ZipFile(args.baseline_aar) as baseline, zipfile.ZipFile(args.debug_apk) as apk:
        report["runtime"] = dict(bytes=args.experimental_aar.stat().st_size,
                                 sha256=asr.file_hash(args.experimental_aar))
        audit.check("Original ASR-only runtime is untouched", asr.file_hash(args.baseline_aar) == BASELINE_SHA,
                    {"baselineSha256": asr.file_hash(args.baseline_aar)})
        audit.check("Official classes.jar is unchanged", experimental.read("classes.jar") == baseline.read("classes.jar"),
                    {"sha256": asr.sha256(experimental.read("classes.jar"))})
        report["libraries"] = audit.native_inventory(experimental, "jni", "AAR")
        native_identity = []
        for abi in asr.ABIS:
            for library in asr.RUNTIME_NAMES:
                name = f"jni/{abi}/{library}"
                blob = experimental.read(name)
                shipped = apk.read(f"lib/{abi}/{library}")
                row = dict(abi=abi, library=library, sha256=asr.sha256(blob),
                           debugApkIdentity=blob == shipped)
                audit.check(f"Debug APK ships the experimental {abi}/{library}", row["debugApkIdentity"], row)
                if library == "libonnxruntime.so":
                    row["unchangedFromBaseline"] = blob == baseline.read(name)
                    audit.check(f"ORT is unchanged for {abi}", row["unchangedFromBaseline"], row)
                else:
                    row["bridgeExported"] = BRIDGE in asr.Elf(blob).exports()
                    audit.check(f"Token-score JNI is exported for {abi}", row["bridgeExported"], row)
                native_identity.append(row)
        report["nativeIdentity"] = native_identity
        notices = [name for name in baseline.namelist() if name.startswith("META-INF/yezvi-stt/licenses/")
                   and not name.endswith("/")]
        retained = all(name in experimental.namelist() and baseline.read(name) == experimental.read(name) for name in notices)
        audit.check("All baseline runtime notices are retained", len(notices) == 27 and retained,
                    {"count": len(notices), "byteIdentical": retained})
        report["retainedNotices"] = len(notices)
        packet = {}
        for name in ("README.md", "LICENSE", "source-manifest.json", "sherpa-1.13.8-confidence.patch", "yezvi-token-scores.cc"):
            embedded = experimental.read("META-INF/yezvi-stt-confidence/" + name)
            local = (ROOT / "scripts/sherpa-confidence" / name).read_bytes()
            packet[name] = dict(sha256=asr.sha256(embedded), matchesLocalSource=embedded == local)
            audit.check("Experimental source/license packet matches " + name, embedded == local, packet[name])
        report["sourcePacket"] = packet
        debug_spec = json.loads(apk.read("assets/stt/confidence-runtime.json"))
        audit.check("Debug runtime inventory is complete and matches its AAR",
                    debug_spec.get("completeAbiSet") is True and debug_spec.get("experimental") is True and
                    debug_spec.get("sha256") == report["runtime"]["sha256"] and
                    debug_spec.get("bytes") == report["runtime"]["bytes"], debug_spec)
    manifest = ET.parse(args.merged_manifest).getroot()
    permissions = [node.get(asr.ANDROID + "name") for node in manifest.findall("uses-permission")]
    audit.check("Application does not request Internet permission", "android.permission.INTERNET" not in permissions, permissions)
    report["permissions"] = permissions
    report["sourceExclusion"] = audit.build_evidence(args.build_root)
    report["checks"] = audit.checks
    report["technicalIssues"] = audit.issues
    report["technicalChecksPassed"] = not audit.issues
    report["limits"] = ["No execution on ARM64, API24, Samsung or 16KB pages is inferred from an ELF audit",
                        "Existing ORT provenance/license closure and 16KB gates remain open",
                        "No calibrated accuracy, battery or peak-memory guarantee follows from these checks"]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"technicalChecksPassed": report["technicalChecksPassed"],
                      "technicalIssues": len(audit.issues), "retainedNotices": len(notices)}))
    return 0 if not audit.issues else 1


if __name__ == "__main__":
    raise SystemExit(main())
