# YEZVI — local ASR/VAD resources

Runtime: YEZVI build of sherpa-onnx 1.13.8, revision
11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf (Apache-2.0), with native TTS and
speaker diarization disabled. This is not the unchanged official full AAR.
https://github.com/k2-fsa/sherpa-onnx/tree/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf
Original API bindings retained; native code rebuilt using NDK r28c. Upstream
applies its Android threading patch to simple-sentencepiece. Build flags,
sources and hashes are recorded in the accompanying audit inventory.

One shared ONNX Runtime 1.28.2 (MIT) per ABI, packaged by Sherpa maintainer
csukuangfj: https://github.com/csukuangfj/onnxruntime-libs/releases/tag/v1.28.2
No second ORT runtime or TTS/Piper/eSpeak/UCD library is included in this build.
Kaldi/fbank/OpenFst/simple-sentencepiece use Apache-2.0; KissFFT BSD-3-Clause;
Darts BSD-2-Clause; JSON MIT. Original notices and copyrights are retained in
stt/licenses/; stt/licenses-manifest.json identifies each exact notice.
ORT/NDK aggregated notices conservatively include additional component texts;
their presence does not claim every mentioned component was linked.

Eigen 5.0.1: MPL-2.0 and compatible per-file licenses described in its notices.
The unmodified covered Eigen source is available under those licenses at:
https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.tar.gz
SHA-256: e9c326dc8c05cd1e044c71f30f1b2e34a6161a3b6ecf445d56b53ff1669e3dec
Preserve accessible covered source and these rights when distributing this
executable. This does not place independent YEZVI files under MPL.
NDK libc++/compiler notices, including LLVM exceptions, are also retained.

Spanish model: NVIDIA, stt_es_fastconformer_hybrid_large_pc, CC BY 4.0.
https://huggingface.co/nvidia/stt_es_fastconformer_hybrid_large_pc/blob/d3fcff82e2ddec13517256402aa5da86400c5893/README.md
https://creativecommons.org/licenses/by/4.0/
Sherpa conversion extracts the RNNT branch, exports ONNX and quantizes int8:
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-fast-conformer-transducer-es-1424-int8.tar.bz2
tokens.txt is derived model vocabulary. YEZVI derives bpe.vocab as token,
TAB, -1.0, LF (1025 rows); it does not reproduce original Unigram scores.

Silero VAD: https://github.com/snakers4/silero-vad (MIT).
Asset: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
Its exact SHA-256, rather than an inferred upstream version, identifies it.
All model/resource hashes appear in stt/resources-manifest.json.
No endorsement by NVIDIA or any upstream author is implied.

No private audio, reference transcripts or test corpus belong in this APK.
The notice packet is not publication approval or a certification of all app
dependencies. See the build-specific distribution audit before public release.
