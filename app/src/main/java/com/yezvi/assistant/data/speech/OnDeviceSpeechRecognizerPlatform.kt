package com.yezvi.assistant.data.speech

import com.yezvi.assistant.domain.speech.RecognitionFailureReason

/** Small Android boundary so the engine lifecycle can be tested without Android services. */
internal interface OnDeviceSpeechRecognizerFactory {
    val apiLevel: Int

    fun hasRecordAudioPermission(): Boolean

    fun isOnDeviceRecognitionAvailable(): Boolean

    fun createOnDeviceRecognizer(): OnDeviceSpeechRecognizer
}

internal interface OnDeviceSpeechRecognizer {
    fun setListener(listener: OnDeviceSpeechRecognizerListener)

    /** Called only on API 33+; must report installed on-device support, never online support. */
    fun checkLanguageSupport(
        languageTag: String,
        callback: (OnDeviceLanguageSupport) -> Unit,
    )

    fun startListening(languageTag: String)

    fun stopListening()

    fun cancel()

    fun destroy()
}

internal interface OnDeviceSpeechRecognizerListener {
    fun onReadyForSpeech()

    fun onBeginningOfSpeech()

    fun onEndOfSpeech()

    fun onPartialResult(text: String?)

    fun onFinalResult(text: String?)

    fun onError(error: SpeechRecognizerFailure)
}

internal sealed interface OnDeviceLanguageSupport {
    data object Installed : OnDeviceLanguageSupport
    data object ModelMissing : OnDeviceLanguageSupport
    data object Unsupported : OnDeviceLanguageSupport
    data object PermissionDenied : OnDeviceLanguageSupport
    data class CheckFailed(val reason: RecognitionFailureReason) : OnDeviceLanguageSupport
}

/** Typed SDK-neutral failure mapping from the Android RecognitionListener. */
internal sealed interface SpeechRecognizerFailure {
    data object PermissionDenied : SpeechRecognizerFailure
    data object OfflineModelMissing : SpeechRecognizerFailure
    data class RecognitionFailed(val reason: RecognitionFailureReason) : SpeechRecognizerFailure
}
