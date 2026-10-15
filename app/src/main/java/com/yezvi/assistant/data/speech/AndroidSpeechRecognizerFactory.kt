package com.yezvi.assistant.data.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.yezvi.assistant.domain.speech.RecognitionFailureReason

/** Android adapter that deliberately exposes only the on-device recognizer factory. */
internal class AndroidSpeechRecognizerFactory(context: Context) : OnDeviceSpeechRecognizerFactory {
    private val appContext = context.applicationContext

    override val apiLevel: Int
        get() = Build.VERSION.SDK_INT

    override fun hasRecordAudioPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun isOnDeviceRecognitionAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    override fun createOnDeviceRecognizer(): OnDeviceSpeechRecognizer {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        ) {
            throw UnsupportedOperationException("On-device recognition is unavailable")
        }
        return AndroidOnDeviceSpeechRecognizer(
            context = appContext,
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext),
        )
    }
}

private class AndroidOnDeviceSpeechRecognizer(
    private val context: Context,
    private val recognizer: SpeechRecognizer,
) : OnDeviceSpeechRecognizer {
    private var listener: OnDeviceSpeechRecognizerListener? = null

    override fun setListener(listener: OnDeviceSpeechRecognizerListener) {
        this.listener = listener
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    this@AndroidOnDeviceSpeechRecognizer.listener?.onReadyForSpeech()
                }

                override fun onBeginningOfSpeech() {
                    this@AndroidOnDeviceSpeechRecognizer.listener?.onBeginningOfSpeech()
                }

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    this@AndroidOnDeviceSpeechRecognizer.listener?.onEndOfSpeech()
                }

                override fun onError(error: Int) {
                    this@AndroidOnDeviceSpeechRecognizer.listener?.onError(error.toFailure())
                }

                override fun onResults(results: Bundle?) {
                    this@AndroidOnDeviceSpeechRecognizer.listener?.onFinalResult(
                        results.recognitionText(),
                    )
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    this@AndroidOnDeviceSpeechRecognizer.listener?.onPartialResult(
                        partialResults.recognitionText(),
                    )
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
    }

    override fun checkLanguageSupport(
        languageTag: String,
        callback: (OnDeviceLanguageSupport) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            callback(OnDeviceLanguageSupport.CheckFailed(RecognitionFailureReason.UNKNOWN))
            return
        }

        try {
            recognizer.checkRecognitionSupport(
                recognizerIntent(languageTag),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        val installed = recognitionSupport.installedOnDeviceLanguages
                        val downloadable = recognitionSupport.supportedOnDeviceLanguages +
                            recognitionSupport.pendingOnDeviceLanguages
                        callback(
                            when {
                                installed.matchesRequestedLanguage(languageTag) ->
                                    OnDeviceLanguageSupport.Installed

                                downloadable.matchesRequestedLanguage(languageTag) ->
                                    OnDeviceLanguageSupport.ModelMissing

                                else -> OnDeviceLanguageSupport.Unsupported
                            },
                        )
                    }

                    override fun onError(error: Int) {
                        callback(
                            when (error) {
                                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                                    OnDeviceLanguageSupport.Unsupported

                                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                    OnDeviceLanguageSupport.ModelMissing

                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                    OnDeviceLanguageSupport.PermissionDenied

                                else -> OnDeviceLanguageSupport.CheckFailed(error.toFailureReason())
                            },
                        )
                    }
                },
            )
        } catch (error: Exception) {
            callback(
                when {
                    error is SecurityException &&
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED ->
                        OnDeviceLanguageSupport.PermissionDenied

                    error is SecurityException ->
                        OnDeviceLanguageSupport.CheckFailed(RecognitionFailureReason.AUDIO_CAPTURE)

                    else -> OnDeviceLanguageSupport.CheckFailed(RecognitionFailureReason.UNKNOWN)
                },
            )
        }
    }

    override fun startListening(languageTag: String) {
        recognizer.startListening(recognizerIntent(languageTag))
    }

    override fun stopListening() {
        recognizer.stopListening()
    }

    override fun cancel() {
        recognizer.cancel()
    }

    override fun destroy() {
        listener = null
        recognizer.destroy()
    }

    private fun recognizerIntent(languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun Bundle?.recognitionText(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun Int.toFailure(): SpeechRecognizerFailure = when (this) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            SpeechRecognizerFailure.PermissionDenied

        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            SpeechRecognizerFailure.OfflineModelMissing

        SpeechRecognizer.ERROR_AUDIO -> SpeechRecognizerFailure.RecognitionFailed(
            RecognitionFailureReason.AUDIO_CAPTURE,
        )

        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> SpeechRecognizerFailure.RecognitionFailed(RecognitionFailureReason.NO_SPEECH)

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        -> SpeechRecognizerFailure.RecognitionFailed(RecognitionFailureReason.BUSY)

        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            SpeechRecognizerFailure.RecognitionFailed(
                RecognitionFailureReason.LANGUAGE_NOT_SUPPORTED,
            )

        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            SpeechRecognizerFailure.RecognitionFailed(
                RecognitionFailureReason.SERVICE_DISCONNECTED,
            )

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        -> SpeechRecognizerFailure.RecognitionFailed(
            RecognitionFailureReason.SERVICE_DISCONNECTED,
        )

        else -> SpeechRecognizerFailure.RecognitionFailed(RecognitionFailureReason.UNKNOWN)
    }

    private fun Int.toFailureReason(): RecognitionFailureReason = when (this) {
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> RecognitionFailureReason.LANGUAGE_NOT_SUPPORTED
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> RecognitionFailureReason.LANGUAGE_UNAVAILABLE
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionFailureReason.AUDIO_CAPTURE
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> RecognitionFailureReason.SERVICE_DISCONNECTED
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionFailureReason.BUSY
        else -> RecognitionFailureReason.UNKNOWN
    }
}

/** Accept an exact locale or a generic language-only model, without treating other regions as equal. */
internal fun List<String>.matchesRequestedLanguage(requestedTag: String): Boolean {
    val requested = java.util.Locale.forLanguageTag(requestedTag)
    return any { candidateTag ->
        val candidate = java.util.Locale.forLanguageTag(candidateTag)
        candidateTag.equals(requestedTag, ignoreCase = true) ||
            (candidate.language.equals(requested.language, ignoreCase = true) &&
                candidate.script == requested.script &&
                candidate.country.isEmpty() &&
                candidate.variant.isEmpty())
    }
}
