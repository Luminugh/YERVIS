package com.yezvi.assistant.data.speech

import com.yezvi.assistant.domain.speech.FinalTranscript
import com.yezvi.assistant.domain.speech.PartialTranscript
import com.yezvi.assistant.domain.speech.RecognitionFailureReason
import com.yezvi.assistant.domain.speech.SpeechToTextCapabilities
import com.yezvi.assistant.domain.speech.SpeechToTextEngine
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextStartResult
import com.yezvi.assistant.domain.speech.SpeechToTextState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Strict on-device SpeechRecognizer implementation; never creates the generic recognizer. */
internal class AndroidOnDeviceSpeechToTextEngine(
    private val factory: OnDeviceSpeechRecognizerFactory,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : SpeechToTextEngine {
    private val mutableState = MutableStateFlow<SpeechToTextState>(SpeechToTextState.Idle)
    private var activeSession: Session? = null

    override val capabilities = SpeechToTextCapabilities(supportsPartialResults = true)
    override val state: StateFlow<SpeechToTextState> = mutableState.asStateFlow()

    override suspend fun start(request: SpeechToTextRequest): SpeechToTextStartResult =
        withContext(mainDispatcher) {
            if (activeSession != null) {
                return@withContext SpeechToTextStartResult.SessionAlreadyActive
            }
            if (!factory.hasRecordAudioPermission()) {
                mutableState.value = SpeechToTextState.Error(SpeechToTextError.PermissionDenied)
                return@withContext SpeechToTextStartResult.Started
            }
            if (!request.languageTag.isSpanishLanguageTag()) {
                mutableState.value = SpeechToTextState.Error(
                    SpeechToTextError.RecognitionFailed(
                        RecognitionFailureReason.LANGUAGE_NOT_SUPPORTED,
                    ),
                )
                return@withContext SpeechToTextStartResult.Started
            }
            if (factory.apiLevel < ANDROID_ON_DEVICE_API ||
                !factory.isOnDeviceRecognitionAvailable()
            ) {
                mutableState.value = SpeechToTextState.Error(SpeechToTextError.EngineUnavailable)
                return@withContext SpeechToTextStartResult.Started
            }

            val recognizer = try {
                factory.createOnDeviceRecognizer()
            } catch (error: SecurityException) {
                mutableState.value = SpeechToTextState.Error(
                    if (factory.hasRecordAudioPermission()) {
                        SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE)
                    } else {
                        SpeechToTextError.PermissionDenied
                    },
                )
                return@withContext SpeechToTextStartResult.Started
            } catch (_: Exception) {
                mutableState.value = SpeechToTextState.Error(SpeechToTextError.EngineUnavailable)
                return@withContext SpeechToTextStartResult.Started
            }

            val session = Session(
                languageTag = request.languageTag,
                recognizer = recognizer,
            )
            activeSession = session
            mutableState.value = SpeechToTextState.Preparing

            try {
                recognizer.setListener(listenerFor(session))
                if (factory.apiLevel >= ANDROID_LANGUAGE_SUPPORT_API) {
                    recognizer.checkLanguageSupport(request.languageTag) { support ->
                        handleLanguageSupport(session, support)
                    }
                } else {
                    startListening(session)
                }
            } catch (error: Exception) {
                endSession(
                    session,
                    error.toSpeechToTextError(),
                    cancelRecognizer = true,
                )
            }
            SpeechToTextStartResult.Started
        }

    override suspend fun finish() = withContext(mainDispatcher) {
        val session = activeSession ?: return@withContext
        if (session.finishRequested) return@withContext
        if (!factory.hasRecordAudioPermission()) {
            endSession(session, SpeechToTextError.PermissionDenied, cancelRecognizer = true)
            return@withContext
        }

        session.finishRequested = true
        mutableState.value = SpeechToTextState.Transcribing(session.partialTranscript)
        if (!session.listeningStarted) return@withContext

        try {
            session.recognizer.stopListening()
        } catch (error: Exception) {
            endSession(
                session,
                error.toSpeechToTextError(),
                cancelRecognizer = true,
            )
        }
    }

    override suspend fun cancel() = withContext(mainDispatcher) {
        val session = activeSession ?: return@withContext
        endSession(session, SpeechToTextState.Cancelled, cancelRecognizer = true)
    }

    private fun handleLanguageSupport(
        session: Session,
        support: OnDeviceLanguageSupport,
    ) {
        if (!isCurrent(session)) return
        when (support) {
            OnDeviceLanguageSupport.Installed -> startListening(session)
            OnDeviceLanguageSupport.ModelMissing ->
                endSession(session, SpeechToTextError.OfflineModelMissing)

            OnDeviceLanguageSupport.Unsupported ->
                endSession(
                    session,
                    SpeechToTextError.RecognitionFailed(
                        RecognitionFailureReason.LANGUAGE_NOT_SUPPORTED,
                    ),
                )

            is OnDeviceLanguageSupport.CheckFailed ->
                endSession(
                    session,
                    SpeechToTextError.RecognitionFailed(support.reason),
                )

            OnDeviceLanguageSupport.PermissionDenied ->
                endSession(session, SpeechToTextError.PermissionDenied, cancelRecognizer = true)
        }
    }

    private fun startListening(session: Session) {
        if (!isCurrent(session)) return
        if (!factory.hasRecordAudioPermission()) {
            endSession(session, SpeechToTextError.PermissionDenied, cancelRecognizer = true)
            return
        }

        try {
            session.listeningStarted = true
            session.recognizer.startListening(session.languageTag)
            if (session.finishRequested) session.recognizer.stopListening()
        } catch (error: Exception) {
            endSession(
                session,
                if (!factory.hasRecordAudioPermission()) {
                    SpeechToTextError.PermissionDenied
                } else {
                    error.toSpeechToTextError()
                },
                cancelRecognizer = true,
            )
        }
    }

    private fun listenerFor(session: Session) = object : OnDeviceSpeechRecognizerListener {
        override fun onReadyForSpeech() {
            if (isCurrentWithPermission(session)) publishListening(session)
        }

        override fun onBeginningOfSpeech() {
            if (isCurrentWithPermission(session)) publishListening(session)
        }

        override fun onEndOfSpeech() {
            if (isCurrentWithPermission(session)) {
                mutableState.value = SpeechToTextState.Transcribing(session.partialTranscript)
            }
        }

        override fun onPartialResult(text: String?) {
            if (!isCurrentWithPermission(session) || text.isNullOrBlank()) return
            session.partialTranscript = PartialTranscript(text)
            mutableState.value = if (session.finishRequested) {
                SpeechToTextState.Transcribing(session.partialTranscript)
            } else {
                SpeechToTextState.Listening(session.partialTranscript)
            }
        }

        override fun onFinalResult(text: String?) {
            if (!isCurrentWithPermission(session)) return
            if (text.isNullOrBlank()) {
                endSession(
                    session,
                    SpeechToTextError.RecognitionFailed(RecognitionFailureReason.NO_SPEECH),
                )
            } else {
                endSession(session, SpeechToTextState.Result(FinalTranscript(text)))
            }
        }

        override fun onError(error: SpeechRecognizerFailure) {
            if (!isCurrent(session)) return
            if (!factory.hasRecordAudioPermission()) {
                endSession(session, SpeechToTextError.PermissionDenied, cancelRecognizer = true)
                return
            }
            endSession(session, error.toSpeechToTextError())
        }
    }

    private fun publishListening(session: Session) {
        mutableState.value = if (session.finishRequested) {
            SpeechToTextState.Transcribing(session.partialTranscript)
        } else {
            SpeechToTextState.Listening(session.partialTranscript)
        }
    }

    private fun endSession(
        session: Session,
        state: SpeechToTextState,
        cancelRecognizer: Boolean = false,
    ) {
        if (!isCurrent(session)) return
        activeSession = null
        session.terminal = true

        var cleanupFailed = false
        if (cancelRecognizer) {
            try {
                session.recognizer.cancel()
            } catch (_: Exception) {
                cleanupFailed = true
            }
        }
        try {
            session.recognizer.destroy()
        } catch (_: Exception) {
            cleanupFailed = true
        }

        mutableState.value = if (cleanupFailed) {
            SpeechToTextState.Error(
                SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE),
            )
        } else {
            state
        }
    }

    private fun endSession(
        session: Session,
        error: SpeechToTextError,
        cancelRecognizer: Boolean = false,
    ) = endSession(session, SpeechToTextState.Error(error), cancelRecognizer)

    private fun isCurrent(session: Session): Boolean =
        activeSession === session && !session.terminal

    private fun isCurrentWithPermission(session: Session): Boolean {
        if (!isCurrent(session)) return false
        if (!factory.hasRecordAudioPermission()) {
            endSession(session, SpeechToTextError.PermissionDenied, cancelRecognizer = true)
            return false
        }
        return true
    }

    private fun SpeechRecognizerFailure.toSpeechToTextError(): SpeechToTextError = when (this) {
        SpeechRecognizerFailure.PermissionDenied -> SpeechToTextError.PermissionDenied
        SpeechRecognizerFailure.OfflineModelMissing -> SpeechToTextError.OfflineModelMissing
        is SpeechRecognizerFailure.RecognitionFailed ->
            SpeechToTextError.RecognitionFailed(reason)
    }

    private fun Exception.toSpeechToTextError(): SpeechToTextError = when (this) {
        is SecurityException -> if (factory.hasRecordAudioPermission()) {
            SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE)
        } else {
            SpeechToTextError.PermissionDenied
        }

        is UnsupportedOperationException -> SpeechToTextError.EngineUnavailable
        else -> SpeechToTextError.RecognitionFailed(RecognitionFailureReason.UNKNOWN)
    }

    private class Session(
        val languageTag: String,
        val recognizer: OnDeviceSpeechRecognizer,
        var partialTranscript: PartialTranscript? = null,
        var listeningStarted: Boolean = false,
        var finishRequested: Boolean = false,
        var terminal: Boolean = false,
    )

    private fun String.isSpanishLanguageTag(): Boolean =
        Locale.forLanguageTag(this).language.equals("es", ignoreCase = true)

    private companion object {
        const val ANDROID_ON_DEVICE_API = 31
        const val ANDROID_LANGUAGE_SUPPORT_API = 33
    }
}
