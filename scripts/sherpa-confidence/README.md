# YEZVI experimental Sherpa token-score runtime

This Apache-2.0 adaptation is debug-only and preserves the pinned Sherpa 1.13.8
commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`. Upstream copyright notices remain.
YEZVI changes are marked in the patch; new bridge copyright is YEZVI contributors.

The NeMo modified beam search keeps its existing decoder ranking, context boost,
tokens and timestamps. A per-decode reusable float scratch buffer preserves token
log-probabilities immediately after LogSoftmax and before contextual boosting.
The scratch is copied only when a context graph can apply a boost. It is roughly
4 KiB for the Spanish model vocabulary; no logits history or second ONNX pass is
retained. Scores describe the selected hypothesis, are uncalibrated, and must not
be advertised as probability of a word being correct.

The extra JNI symbol reads `ys_log_probs` only while the caller still owns a live
OfflineStream, after decode and before release, on that stream's owner thread.
Zero pointers, empty or mismatched vectors, more than 2048 scores, non-finite
scores or values above 1e-5 produce null. Nonzero stale pointers are not a valid
input and cannot be safely validated by JNI; Kotlin owns lifetime and exclusion.
No audio, text or scores are logged or uploaded by the bridge. The official Java
classes.jar is unchanged. Only the debug artifact exports this extra symbol.

Build locally from verified cached prerequisites; no automatic download:

```powershell
./scripts/Build-SherpaAsrOnly.ps1 -ConfidencePrototype -Abis x86_64 -SkipPackage
./scripts/Build-SherpaAsrOnly.ps1 -ConfidencePrototype -Abis arm64-v8a,armeabi-v7a,x86
```

The script copies clean tracked native sources from the original commit into a separate ignored cache, checks and
applies the versioned patch, adds the bridge, and uses separate dependency/build
folders. Baseline checkout, native builds and baseline AAR remain intact. The
result is `asr-only/confidence/sherpa-onnx-asr-only-confidence-1.13.8-yezvi.aar`.
Its 27-notice baseline packet is retained and the patch, added source, source
manifest and overlay receipt are embedded under META-INF/yezvi-stt-confidence.

This adaptation adds no model or third-party dependency. Existing Sherpa Apache,
ORT notices, Eigen/MPL source availability and model/VAD licensing obligations
remain. Existing ORT provenance and 16 KB compatibility review gates remain open.
The recipe and local test artifact do not constitute approval to publish an APK.
