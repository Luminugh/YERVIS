# Sherpa JNI looks up these binding classes/fields by their upstream names.
# Native TTS/diarization is excluded at CMake/link time; this rule grants no TTS capability.
-keep class com.k2fsa.sherpa.onnx.** { *; }
