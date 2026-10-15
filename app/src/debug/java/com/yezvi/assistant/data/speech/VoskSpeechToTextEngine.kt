package com.yezvi.assistant.data.speech

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import com.yezvi.assistant.domain.speech.FinalTranscript
import com.yezvi.assistant.domain.speech.PartialTranscript
import com.yezvi.assistant.domain.speech.RecognitionFailureReason
import com.yezvi.assistant.domain.speech.SpeechToTextCapabilities
import com.yezvi.assistant.domain.speech.SpeechToTextEngine
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextStartResult
import com.yezvi.assistant.domain.speech.SpeechToTextState
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Explicit foreground PCM16 → Vosk streaming STT. Only local model files are accepted; an
 * unavailable model is an error. Endpoint texts remain partial until finish/EOF succeeds and
 * all session resources are closed. Cancellation discards any late native result.
 */
internal class VoskSpeechToTextEngine internal constructor(
    private val workerScope: CoroutineScope,
    private val hasRecordAudioPermission: () -> Boolean,
    private val captureFactory: suspend () -> LocalPcmCaptureLease,
    private val recognizerFactory: suspend () -> VoskStreamingRecognizer,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SpeechToTextEngine {
    private val commandMutex = Mutex()
    private val sessionLock = Any()
    private val mutableState = MutableStateFlow<SpeechToTextState>(SpeechToTextState.Idle)
    private var activeSession: Session? = null

    override val capabilities = SpeechToTextCapabilities(true, setOf("es"))
    override val state: StateFlow<SpeechToTextState> = mutableState

    override suspend fun start(request: SpeechToTextRequest): SpeechToTextStartResult = commandMutex.withLock {
        if (synchronized(sessionLock) { activeSession != null }) {
            return@withLock SpeechToTextStartResult.SessionAlreadyActive
        }
        mutableState.value = SpeechToTextState.Preparing
        if (!hasPermission()) {
            mutableState.value = SpeechToTextState.Error(SpeechToTextError.PermissionDenied)
            return@withLock SpeechToTextStartResult.Started
        }
        if (!SPANISH_LANGUAGE.matches(request.languageTag)) {
            mutableState.value = SpeechToTextState.Error(
                SpeechToTextError.RecognitionFailed(RecognitionFailureReason.LANGUAGE_NOT_SUPPORTED),
            )
            return@withLock SpeechToTextStartResult.Started
        }

        var capture: LocalPcmCaptureLease? = null
        var recognizer: VoskStreamingRecognizer? = null
        var startingSession: Session? = null
        var modelReady = false
        try {
            workerScope.coroutineContext.ensureActive()
            recognizer = recognizerFactory()
            modelReady = true
            capture = captureFactory()
            ensurePermission()
            capture.input.open()
            ensurePermission()
            capture.input.start()
            ensurePermission()
            currentCoroutineContext().ensureActive()
            if (!capture.isOwnerActive()) throw CancellationException("Capture owner is inactive")
            val session = Session(capture, recognizer)
            startingSession = session
            session.worker = workerScope.launch(workerDispatcher, start = CoroutineStart.LAZY) { runSession(session) }
            val launched = synchronized(sessionLock) {
                activeSession = session
                mutableState.value = SpeechToTextState.Listening()
                if (checkNotNull(session.worker).start()) true else {
                    activeSession = null
                    false
                }
            }
            if (!launched) throw CancellationException("Capture owner was cancelled")
        } catch (error: Throwable) {
            startingSession?.let { session -> synchronized(sessionLock) {
                if (activeSession === session) activeSession = null
            } }
            val cleanupError = cleanup(capture, recognizer, error)
            mutableState.value = if (error is CancellationException) SpeechToTextState.Cancelled else {
                SpeechToTextState.Error(
                    if (cleanupError != null) audioCaptureError() else toSpeechError(error, !modelReady),
                )
            }
            if (error is CancellationException || (error !is Exception && error !is LinkageError)) throw error
        }
        SpeechToTextStartResult.Started
    }

    override suspend fun finish() = commandMutex.withLock {
        val session = synchronized(sessionLock) { activeSession } ?: return@withLock
        if (session.cancelRequested.get() || session.finishRequested) return@withLock
        session.finishRequested = true
        if (!hasPermission()) {
            session.forcedError = SpeechToTextError.PermissionDenied
            session.cancelRequested.set(true)
        }
        publish(session, SpeechToTextState.Transcribing(session.partialTranscript()))
        try {
            session.capture.input.stop()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            session.forcedError = session.forcedError ?: toSpeechError(error)
            session.worker?.cancel()
        }
        if (session.cancelRequested.get()) session.worker?.cancel()
        // JNI final decoding remains in the worker, so the controller can still cancel it.
    }

    override suspend fun cancel() = withContext(NonCancellable) { commandMutex.withLock {
        val session = synchronized(sessionLock) { activeSession } ?: return@withLock
        session.cancelRequested.set(true)
        if (!session.finishRequested) {
            try { session.capture.input.stop() } catch (_: Exception) {
                session.forcedError = audioCaptureError()
            }
        }
        session.worker?.cancelAndJoin()
        // A lazy job cancelled before executing cannot run its own finally.
        if (synchronized(sessionLock) { activeSession === session }) {
            val cleanupError = cleanup(session.capture, session.recognizer)
            complete(session, if (cleanupError != null || session.forcedError != null) {
                SpeechToTextState.Error(session.forcedError ?: audioCaptureError())
            } else SpeechToTextState.Cancelled)
        }
    } }

    private suspend fun runSession(session: Session) {
        val buffer = ShortArray(READ_BLOCK_SAMPLES)
        var terminal: SpeechToTextState = SpeechToTextState.Cancelled
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!isSessionAllowed(session)) break
                ensurePermission()
                val count = session.capture.input.read(buffer)
                currentCoroutineContext().ensureActive()
                if (!isSessionAllowed(session)) break
                if (count == 0) break
                if (count !in 1..buffer.size) throw PcmCaptureException(PcmCaptureFailure.READ_FAILED, count)
                ensurePermission()
                val text = session.recognizer.accept(buffer, count)
                currentCoroutineContext().ensureActive()
                if (!isSessionAllowed(session)) break
                ensurePermission()
                session.partialText = text
                publish(session, if (session.finishRequested) {
                    SpeechToTextState.Transcribing(session.partialTranscript())
                } else SpeechToTextState.Listening(session.partialTranscript()))
            }
            if (isSessionAllowed(session) && session.forcedError == null) {
                ensurePermission()
                publish(session, SpeechToTextState.Transcribing(session.partialTranscript()))
                val text = session.recognizer.finish()
                currentCoroutineContext().ensureActive()
                ensurePermission()
                terminal = if (!isSessionAllowed(session)) SpeechToTextState.Cancelled else if (text.isBlank()) {
                    SpeechToTextState.Error(SpeechToTextError.RecognitionFailed(RecognitionFailureReason.NO_SPEECH))
                } else SpeechToTextState.Result(FinalTranscript(text))
            }
        } catch (_: CancellationException) {
            terminal = SpeechToTextState.Cancelled
        } catch (error: Exception) {
            terminal = if (!session.capture.isOwnerActive()) {
                session.cancelRequested.set(true)
                SpeechToTextState.Cancelled
            } else SpeechToTextState.Error(toSpeechError(error))
        } catch (_: LinkageError) {
            terminal = SpeechToTextState.Error(SpeechToTextError.EngineUnavailable)
        } finally {
            buffer.fill(0)
            val cleanupError = cleanup(session.capture, session.recognizer)
            terminal = when {
                cleanupError != null -> SpeechToTextState.Error(audioCaptureError())
                session.forcedError != null -> SpeechToTextState.Error(checkNotNull(session.forcedError))
                session.cancelRequested.get() -> SpeechToTextState.Cancelled
                else -> terminal
            }
            complete(session, terminal)
        }
    }

    private fun isSessionAllowed(session: Session): Boolean {
        if (!session.capture.isOwnerActive()) session.cancelRequested.set(true)
        return !session.cancelRequested.get()
    }

    private fun publish(session: Session, value: SpeechToTextState) = synchronized(sessionLock) {
        if (activeSession === session && !session.cancelRequested.get()) mutableState.value = value
    }

    private fun complete(session: Session, value: SpeechToTextState) = synchronized(sessionLock) {
        if (activeSession === session) {
            activeSession = null
            mutableState.value = value
        }
    }

    private fun hasPermission() = try { hasRecordAudioPermission() } catch (_: SecurityException) { false }
    private fun ensurePermission() {
        if (!hasPermission()) throw PcmCaptureException(PcmCaptureFailure.PERMISSION_DENIED)
    }

    private fun toSpeechError(error: Throwable, modelPreparation: Boolean = false): SpeechToTextError = when (error) {
        is PcmCaptureException -> if (error.failure == PcmCaptureFailure.PERMISSION_DENIED) {
            SpeechToTextError.PermissionDenied
        } else audioCaptureError()
        is SecurityException -> SpeechToTextError.PermissionDenied
        is LinkageError -> SpeechToTextError.EngineUnavailable
        is IOException -> if (modelPreparation) SpeechToTextError.OfflineModelMissing else {
            SpeechToTextError.RecognitionFailed(RecognitionFailureReason.UNKNOWN)
        }
        else -> SpeechToTextError.RecognitionFailed(RecognitionFailureReason.UNKNOWN)
    }

    private fun audioCaptureError() = SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE)

    private suspend fun cleanup(
        capture: LocalPcmCaptureLease?,
        recognizer: VoskStreamingRecognizer?,
        primary: Throwable? = null,
    ): Throwable? = withContext(NonCancellable) {
        var first: Throwable? = null
        suspend fun release(block: suspend () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (primary != null && primary !== error) primary.addSuppressed(error)
                if (first == null) first = error else if (first !== error) first?.addSuppressed(error)
            }
        }
        if (capture != null) release { capture.close() }
        if (recognizer != null) release { recognizer.close() }
        first
    }

    private class Session(val capture: LocalPcmCaptureLease, val recognizer: VoskStreamingRecognizer) {
        val cancelRequested = AtomicBoolean(false)
        @Volatile var finishRequested = false
        @Volatile var forcedError: SpeechToTextError? = null
        @Volatile var worker: Job? = null
        @Volatile var partialText = ""
        fun partialTranscript() = partialText.takeIf { it.isNotEmpty() }?.let(::PartialTranscript)
    }

    companion object {
        private const val READ_BLOCK_SAMPLES = 320
        private val SPANISH_LANGUAGE = Regex("(?i)^es(?:-[a-z0-9]{2,8})*$")

        /** Preparing this factory does not open the microphone or load native resources. */
        fun create(
            context: Context,
            lifecycle: Lifecycle,
            ownerScope: CoroutineScope,
            hasRecordAudioPermission: () -> Boolean = {
                context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
            workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): VoskSpeechToTextEngine {
            val appContext = context.applicationContext
            return VoskSpeechToTextEngine(
                workerScope = ownerScope,
                hasRecordAudioPermission = hasRecordAudioPermission,
                captureFactory = {
                    val owner = LifecyclePcmCapture.attach(appContext, lifecycle, ownerScope)
                    LocalPcmCaptureLease(AudioRecordLocalPcmInput(owner.source), { owner.close() }, { owner.isOwnerActive })
                },
                recognizerFactory = { VoskStreamingRecognizer.create(appContext, workerDispatcher) },
                workerDispatcher = workerDispatcher,
            )
        }
    }
}
