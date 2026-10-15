# YEZVI — Vosk local STT acknowledgements

Vosk Android 0.3.75, copyright Alpha Cephei and contributors, Apache License 2.0.
Official artifact: https://repo.maven.apache.org/maven2/com/alphacephei/vosk-android/0.3.75/
Spanish model vosk-model-small-es-0.42, Copyright 2022-2050 AC Technologies LLC.
The official model catalog licenses these weights/graphs under Apache License 2.0:
https://alphacephei.com/vosk/models
Model files are unmodified; their original README is retained. No endorsement is implied.

JNA 5.18.1 offers Apache-2.0 OR LGPL-2.1-or-later. YEZVI chooses Apache-2.0.
Its native libffi uses MIT. Kaldi/OpenFst use Apache-2.0; OpenBLAS and CLAPACK
use BSD-3-Clause, with the included AT&T/Lucent/Bellcore f2c notice. SimpleJSON's
original WTFPL header is retained without inventing a license version.
LLVM/libc++ and compiler-rt notices are retained conservatively; the prebuilt
artifact's exact toolchain revision is not certified.

All 13 original notices in stt/vosk-licenses/ accompany this binary.
stt/vosk-licenses-manifest.json records source URLs, revisions and notice hashes.
stt/vosk-resources.json records exact binary/model hashes and restoration sources.
No third-party Vosk/JNA source was modified. The Android/Kotlin integration is
YEZVI code. Vosk uses unrestricted dictation, without post-recognition substitutions.

The existing AndroidX graphics-path 1.0.1 native library is also present in the
application. Copyright 2022 The Android Open Source Project; Apache License 2.0.
The Apache license text accompanies this binary in vosk-licenses/VOSK-APACHE-2.0.txt.
Source headers and exact artifact/native hashes are recorded separately in the
project's app-native-audit.json; this component is not part of Vosk.

This notice packet establishes the identified license choices, not a legal
certification of all application dependencies. Before public distribution,
complete the documented source/toolchain and Android 16 KB compatibility gates.
The debug research variant also contains the separate, rebuilt Sherpa ASR/VAD
runtime and its notices. Neither variant is approved for publication by this packet.
