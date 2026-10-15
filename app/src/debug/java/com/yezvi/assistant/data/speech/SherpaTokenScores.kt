package com.yezvi.assistant.data.speech

/** Debug-only bridge to the patched runtime; the official Sherpa Kotlin API stays unchanged. */
internal object SherpaTokenScores {
    init { System.loadLibrary("sherpa-onnx-jni") }

    external fun readTokenLogProbabilities(streamPtr: Long): FloatArray?
}
