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
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Optional in-memory diagnostic metadata; never part of the public STT/persistence contracts. */
internal data class LocalSpeechDiagnosticSegment(
    val sessionId: Long,
    val textBefore: String,
    val recognition: LocalSpeechRecognition,
)

/**
 * Offline Spanish recognizer which owns one capture, VAD and Sherpa session at a time.
 * AudioRecord is opened only by [start]; PCM and native session resources are discarded on finish,
 * cancel, permission loss, lifecycle stop, or setup failure. Text remains a caller-owned draft.
 */
internal class SherpaOfflineSpeechToTextEngine internal constructor(
    private val workerScope: CoroutineScope,
    private val hasRecordAudioPermission: () -> Boolean,
    private val captureFactory: suspend () -> LocalPcmCaptureLease,
    private val segmenterFactory: suspend () -> SileroPcmSegmenter,
    private val recognizerFactory: suspend () -> SherpaOfflineRecognizerAdapter,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onSegmentRecognition: ((LocalSpeechDiagnosticSegment) -> Unit)? = null,
) : SpeechToTextEngine {
    private val commandMutex = Mutex()
    private val sessionLock = Any()
    private val mutableState = MutableStateFlow<SpeechToTextState>(SpeechToTextState.Idle)
    @Volatile private var activeSession: Session? = null

    override val capabilities = SpeechToTextCapabilities(
        supportsPartialResults = true,
        supportedLanguageTags = setOf("es"),
        supportsPause = true,
    )
    override val state: StateFlow<SpeechToTextState> = mutableState

    override suspend fun start(request: SpeechToTextRequest): SpeechToTextStartResult =
        commandMutex.withLock {
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
            var segmenter: SileroPcmSegmenter? = null
            var recognizer: SherpaOfflineRecognizerAdapter? = null
            var startingSession: Session? = null
            try {
                // Initialize local model components before acquiring the microphone.
                segmenter = segmenterFactory()
                recognizer = recognizerFactory()
                capture = captureFactory()
                ensurePermission()
                capture.input.open()
                ensurePermission()
                capture.input.start()
                ensurePermission()

                val session = Session(capture, segmenter, recognizer)
                startingSession = session
                val worker = workerScope.launch(workerDispatcher, start = CoroutineStart.LAZY) {
                    runSession(session)
                }
                session.worker = worker
                val workerStarted = synchronized(sessionLock) {
                    activeSession = session
                    mutableState.value = SpeechToTextState.Listening()
                    if (worker.start()) {
                        true
                    } else {
                        activeSession = null
                        false
                    }
                }
                if (!workerStarted) throw CancellationException("Voice capture owner is no longer active")
            } catch (cancelled: CancellationException) {
                startingSession?.let { failed -> synchronized(sessionLock) {
                    if (activeSession === failed) activeSession = null
                } }
                cleanup(capture, segmenter, recognizer, cancelled)
                mutableState.value = SpeechToTextState.Cancelled
                throw cancelled
            } catch (error: Throwable) {
                startingSession?.let { failed -> synchronized(sessionLock) {
                    if (activeSession === failed) activeSession = null
                } }
                val cleanupFailure = cleanup(capture, segmenter, recognizer, error)
                mutableState.value = SpeechToTextState.Error(
                    if (cleanupFailure != null) {
                        SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE)
                    } else {
                        toSpeechError(error, modelPreparation = true)
                    },
                )
                if (error !is Exception && error !is LinkageError) throw error
            }
            SpeechToTextStartResult.Started
        }

    override suspend fun finish() = commandMutex.withLock {
        val session = synchronized(sessionLock) { activeSession } ?: return@withLock
        if (session.cancelRequested.get() || session.finishRequested) return@withLock
        session.finishRequested = true
        session.paused.value = false
        if (!hasPermission()) {
            session.forcedError = SpeechToTextError.PermissionDenied
            session.cancelRequested.set(true)
        }
        mutableState.value = SpeechToTextState.Transcribing(
            session.partialTranscript(),
            session.voiceActivityObserved,
        )
        try {
            session.capture.input.stop()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            session.forcedError = session.forcedError ?: toSpeechError(error)
            session.worker?.cancel()
        }
        if (session.cancelRequested.get()) session.worker?.cancel()
        // Final VAD/ASR continues in the session job. Returning here releases the controller's
        // command lock so a user/lifecycle cancellation can still discard an in-flight final decode.
    }

    override suspend fun pause(): Boolean = commandMutex.withLock {
        val session = synchronized(sessionLock) { activeSession } ?: return@withLock false
        if (session.cancelRequested.get() || session.finishRequested) return@withLock false
        if (session.paused.value) return@withLock true
        if (!hasPermission()) return@withLock false

        session.pauseGeneration.incrementAndGet()
        session.paused.value = true
        try {
            session.capture.input.pause()
            if (synchronized(sessionLock) { activeSession !== session } || session.cancelRequested.get()) {
                return@withLock false
            }
            mutableState.value = SpeechToTextState.Paused(
                session.partialTranscript(),
                session.voiceActivityObserved,
            )
            true
        } catch (cancelled: CancellationException) {
            session.paused.value = false
            throw cancelled
        } catch (error: Throwable) {
            session.forcedError = toSpeechError(error)
            session.cancelRequested.set(true)
            session.paused.value = false
            try { session.capture.input.stop() } catch (_: Throwable) { }
            false
        }
    }

    override suspend fun resume(): Boolean = commandMutex.withLock {
        val session = synchronized(sessionLock) { activeSession } ?: return@withLock false
        if (session.cancelRequested.get() || session.finishRequested || !session.paused.value) {
            return@withLock false
        }
        if (!hasPermission()) return@withLock false

        try {
            session.capture.input.resume()
            if (synchronized(sessionLock) { activeSession !== session } || session.cancelRequested.get()) {
                return@withLock false
            }
            session.paused.value = false
            mutableState.value = SpeechToTextState.Listening(
                session.partialTranscript(),
                session.voiceActivityObserved,
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            session.forcedError = toSpeechError(error)
            session.cancelRequested.set(true)
            session.paused.value = false
            try { session.capture.input.stop() } catch (_: Throwable) { }
            false
        }
    }

    override suspend fun cancel() = withContext(NonCancellable) { commandMutex.withLock {
        val session = synchronized(sessionLock) { activeSession } ?: return@withLock
        session.cancelRequested.set(true)
        session.paused.value = false
        if (!session.finishRequested) {
            try {
                session.capture.input.stop()
            } catch (error: Throwable) {
                session.forcedError = SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE)
            }
        }
        session.worker?.cancelAndJoin()
        if (synchronized(sessionLock) { activeSession === session }) {
            val cleanupError = cleanup(session.capture, session.segmenter, session.recognizer)
            synchronized(sessionLock) {
                if (activeSession === session) {
                    activeSession = null
                    mutableState.value = if (cleanupError == null && session.forcedError == null) {
                        SpeechToTextState.Cancelled
                    } else {
                        SpeechToTextState.Error(
                            session.forcedError ?: SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE),
                        )
                    }
                }
            }
        }
    } }

    private suspend fun runSession(session: Session) {
        val buffer = ShortArray(READ_BLOCK_SAMPLES)
        val recognizedParts = mutableListOf<String>()
        var terminal: SpeechToTextState = SpeechToTextState.Cancelled
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (session.cancelRequested.get()) break
                if (session.paused.value) {
                    mutableState.value = SpeechToTextState.Paused(
                        session.partialTranscript(),
                        session.voiceActivityObserved,
                    )
                    session.paused.first { paused -> !paused }
                    currentCoroutineContext().ensureActive()
                    if (session.cancelRequested.get() || session.finishRequested) break
                }
                ensurePermission()
                val pauseGenerationBeforeRead = session.pauseGeneration.get()
                val count = session.capture.input.read(buffer)
                currentCoroutineContext().ensureActive()
                if (session.cancelRequested.get()) break
                if (count == 0) {
                    if (!session.capture.isOwnerActive()) session.cancelRequested.set(true)
                    val pauseInterruptedRead = session.pauseGeneration.get() != pauseGenerationBeforeRead
                    if (pauseInterruptedRead || session.paused.value) {
                        if (session.paused.value) {
                            mutableState.value = SpeechToTextState.Paused(
                                session.partialTranscript(),
                                session.voiceActivityObserved,
                            )
                            session.paused.first { paused -> !paused }
                        }
                        if (!session.cancelRequested.get() && !session.finishRequested) continue
                    }
                    if (session.finishRequested) break
                    break // Explicit stop, or lifecycle loss (which discards the transcript).
                }
                if (count !in 1..buffer.size) {
                    throw PcmCaptureException(PcmCaptureFailure.READ_FAILED, count)
                }
                ensurePermission()
                val segments = session.segmenter.accept(buffer, length = count)
                if (session.segmenter.hasVoiceActivity()) {
                    session.voiceActivityObserved = true
                    mutableState.value = SpeechToTextState.Listening(
                        session.partialTranscript(),
                        hasVoiceActivity = true,
                    )
                }
                processSegments(session, segments, recognizedParts)
            }

            if (!session.capture.isOwnerActive()) session.cancelRequested.set(true)
            if (!session.cancelRequested.get() && session.forcedError == null) {
                mutableState.value = SpeechToTextState.Transcribing(
                    session.partialTranscript(),
                    session.voiceActivityObserved,
                )
                val finalSegments = session.segmenter.finish()
                if (!session.capture.isOwnerActive()) session.cancelRequested.set(true)
                processSegments(session, finalSegments, recognizedParts)
                if (!session.capture.isOwnerActive()) session.cancelRequested.set(true)
                terminal = if (session.cancelRequested.get()) SpeechToTextState.Cancelled
                    else finalState(recognizedParts)
            }
        } catch (_: CancellationException) {
            terminal = SpeechToTextState.Cancelled
        } catch (error: Exception) {
            terminal = if (!session.capture.isOwnerActive()) {
                session.cancelRequested.set(true)
                SpeechToTextState.Cancelled
            } else {
                SpeechToTextState.Error(toSpeechError(error))
            }
        } catch (error: LinkageError) {
            terminal = SpeechToTextState.Error(SpeechToTextError.EngineUnavailable)
        } finally {
            buffer.fill(0)
            val cleanupError = cleanup(session.capture, session.segmenter, session.recognizer)
            val requestedError = session.forcedError
            terminal = when {
                cleanupError != null -> SpeechToTextState.Error(
                    SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE),
                )
                requestedError != null -> SpeechToTextState.Error(requestedError)
                session.cancelRequested.get() -> SpeechToTextState.Cancelled
                else -> terminal
            }
            synchronized(sessionLock) {
                if (activeSession === session) activeSession = null
                mutableState.value = terminal
            }
        }
    }

    private suspend fun processSegments(
        session: Session,
        segments: List<PcmSpeechSegment>,
        recognizedParts: MutableList<String>,
    ) {
        for (segment in segments) {
            try {
                currentCoroutineContext().ensureActive()
                if (session.cancelRequested.get()) return
                if (session.paused.value) {
                    mutableState.value = SpeechToTextState.Paused(
                        session.partialTranscript(),
                        session.voiceActivityObserved,
                    )
                    session.paused.first { paused -> !paused }
                    currentCoroutineContext().ensureActive()
                }
                if (!session.capture.isOwnerActive()) {
                    session.cancelRequested.set(true)
                    return
                }
                mutableState.value = SpeechToTextState.Transcribing(
                    session.partialTranscript(),
                    session.voiceActivityObserved,
                )
                val result = session.recognizer.transcribe(segment.samples, SAMPLE_RATE)
                currentCoroutineContext().ensureActive()
                if (session.cancelRequested.get()) return
                ensurePermission()
                if (!session.capture.isOwnerActive()) {
                    session.cancelRequested.set(true)
                    return
                }
                val textBefore = if (onSegmentRecognition != null) recognizedParts.joinToString(" ") else ""
                if (result.text.isNotBlank()) recognizedParts += result.text.trim()
                session.setRecognizedText(recognizedParts.joinToString(" "))
                if (onSegmentRecognition != null && result.text.isNotBlank()) {
                    try {
                        onSegmentRecognition.invoke(LocalSpeechDiagnosticSegment(session.id, textBefore, result))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Optional diagnostics cannot change the successful speech result.
                    } catch (_: LinkageError) { /* Optional debug bridge is absent from release. */ }
                }
                mutableState.value = if (session.paused.value) {
                    SpeechToTextState.Paused(session.partialTranscript(), session.voiceActivityObserved)
                } else {
                    SpeechToTextState.Listening(session.partialTranscript(), session.voiceActivityObserved)
                }
            } finally {
                // Avoid retaining completed segment PCM beyond its one local inference.
                segment.samples.fill(0f)
            }
        }
    }

    private fun finalState(parts: List<String>): SpeechToTextState {
        val text = parts.joinToString(" ").trim()
        return if (text.isEmpty()) {
            SpeechToTextState.Error(
                SpeechToTextError.RecognitionFailed(RecognitionFailureReason.NO_SPEECH),
            )
        } else {
            SpeechToTextState.Result(FinalTranscript(text))
        }
    }

    private fun ensurePermission() {
        if (!hasPermission()) throw PcmCaptureException(PcmCaptureFailure.PERMISSION_DENIED)
    }

    private fun hasPermission(): Boolean = try {
        hasRecordAudioPermission()
    } catch (_: SecurityException) {
        false
    }

    private fun toSpeechError(error: Throwable, modelPreparation: Boolean = false): SpeechToTextError = when (error) {
        is PcmCaptureException -> when (error.failure) {
            PcmCaptureFailure.PERMISSION_DENIED -> SpeechToTextError.PermissionDenied
            PcmCaptureFailure.CAPTURE_INACTIVE,
            PcmCaptureFailure.CLIENT_SILENCED,
            PcmCaptureFailure.INVALID_BUFFER,
            PcmCaptureFailure.INITIALIZATION_FAILED,
            PcmCaptureFailure.START_FAILED,
            PcmCaptureFailure.READ_FAILED -> SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE)
        }
        is SecurityException -> SpeechToTextError.PermissionDenied
        is IOException -> if (modelPreparation) SpeechToTextError.OfflineModelMissing
            else SpeechToTextError.RecognitionFailed(RecognitionFailureReason.UNKNOWN)
        is LinkageError -> SpeechToTextError.EngineUnavailable
        else -> SpeechToTextError.RecognitionFailed(RecognitionFailureReason.UNKNOWN)
    }

    private suspend fun cleanup(
        capture: LocalPcmCaptureLease?,
        segmenter: SileroPcmSegmenter?,
        recognizer: SherpaOfflineRecognizerAdapter?,
        primary: Throwable? = null,
    ): Throwable? = withContext(NonCancellable) {
        var first: Throwable? = null
        suspend fun release(block: suspend () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                if (primary != null && error !== primary) primary.addSuppressed(error)
                if (first == null) first = error else if (first !== error) first?.addSuppressed(error)
            }
        }
        // Stop/release the capture before freeing native recognizer or VAD handles.
        if (capture != null) release { capture.close() }
        if (segmenter != null) release { segmenter.close() }
        if (recognizer != null) release { recognizer.close() }
        first
    }

    private class Session(
        val capture: LocalPcmCaptureLease,
        val segmenter: SileroPcmSegmenter,
        val recognizer: SherpaOfflineRecognizerAdapter,
    ) {
        val id = SESSION_IDS.incrementAndGet()
        val cancelRequested = AtomicBoolean(false)
        val pauseGeneration = AtomicLong()
        val paused = MutableStateFlow(false)
        @Volatile var finishRequested = false
        @Volatile var voiceActivityObserved = false
        @Volatile var forcedError: SpeechToTextError? = null
        @Volatile var worker: Job? = null
        @Volatile private var partialText: String = ""

        fun setRecognizedText(text: String) { partialText = text }
        fun partialTranscript(): PartialTranscript? = partialText.takeIf(String::isNotEmpty)?.let(::PartialTranscript)
    }

    companion object {
        private val SESSION_IDS = AtomicLong()
        private const val READ_BLOCK_SAMPLES = 320
        private const val SAMPLE_RATE = AudioRecordPcmSource.SAMPLE_RATE
        private val SPANISH_LANGUAGE = Regex("(?i)^es(?:-[a-z0-9]{2,8})*$")

        /** Production composition. The microphone remains unopened until a controller calls start. */
        fun create(
            context: Context,
            lifecycle: Lifecycle,
            ownerScope: CoroutineScope,
            hasRecordAudioPermission: () -> Boolean = {
                context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
            workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
            tokenScoreReader: ((Long) -> FloatArray?)? = null,
            onSegmentRecognition: ((LocalSpeechDiagnosticSegment) -> Unit)? = null,
        ): SherpaOfflineSpeechToTextEngine {
            val appContext = context.applicationContext
            return SherpaOfflineSpeechToTextEngine(
                workerScope = ownerScope,
                hasRecordAudioPermission = hasRecordAudioPermission,
                captureFactory = {
                    val owner = LifecyclePcmCapture.attach(appContext, lifecycle, ownerScope)
                    LocalPcmCaptureLease(
                        input = AudioRecordLocalPcmInput(owner.source),
                        releaseOwner = { owner.close() },
                        isOwnerActive = { owner.isOwnerActive },
                    )
                },
                segmenterFactory = { SileroPcmSegmenter.create(appContext) },
                recognizerFactory = { SherpaOfflineRecognizerAdapter.create(appContext, tokenScoreReader = tokenScoreReader) },
                workerDispatcher = workerDispatcher,
                onSegmentRecognition = onSegmentRecognition,
            )
        }
    }
}
