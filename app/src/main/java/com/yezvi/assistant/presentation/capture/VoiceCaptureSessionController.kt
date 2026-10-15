package com.yezvi.assistant.presentation.capture

import com.yezvi.assistant.domain.speech.RecognitionFailureReason
import com.yezvi.assistant.domain.speech.SpeechToTextEngine
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextStartResult
import com.yezvi.assistant.domain.speech.SpeechToTextState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates one user-started voice session around the provider-neutral STT contract.
 *
 * This class does not capture audio itself. Its owner must supply the real local engine and call
 * [close] when the capture surface is left. The permission callback must reflect the current
 * `RECORD_AUDIO` grant; engines must also check platform permission at their own resource boundary.
 */
internal class VoiceCaptureSessionController(
    private val engine: SpeechToTextEngine,
    private val scope: CoroutineScope,
    private val hasRecordAudioPermission: () -> Boolean,
    private val request: SpeechToTextRequest = SpeechToTextRequest("es-PE"),
) {
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private val mutableState = MutableStateFlow<SpeechToTextState>(SpeechToTextState.Idle)

    private var active = false
    private var finishRequested = false
    private var closed = false
    private var observationJob: Job? = null

    val state: StateFlow<SpeechToTextState> = mutableState
    val supportsPause: Boolean get() = engine.capabilities.supportsPause

    /** Starts only after a current permission check; repeated calls during a session are ignored. */
    suspend fun start() = operationMutex.withLock {
        val mayStart = synchronized(stateLock) { !closed && !active }
        if (!mayStart) return@withLock

        if (!hasRecordAudioPermission()) {
            publish(SpeechToTextState.Error(SpeechToTextError.PermissionDenied))
            return@withLock
        }

        synchronized(stateLock) {
            active = true
            finishRequested = false
            mutableState.value = SpeechToTextState.Preparing
        }

        val startResult = try {
            engine.start(request)
        } catch (cancelled: CancellationException) {
            cancelAfterInterruption(cancelled)
            throw cancelled
        } catch (_: Exception) {
            cancelAfterFailure(
                if (hasRecordAudioPermission()) {
                    SpeechToTextError.RecognitionFailed(RecognitionFailureReason.UNKNOWN)
                } else {
                    SpeechToTextError.PermissionDenied
                },
            )
            return@withLock
        }

        when (startResult) {
            SpeechToTextStartResult.Started -> {
                if (!hasRecordAudioPermission()) {
                    cancelActive(SpeechToTextState.Error(SpeechToTextError.PermissionDenied))
                } else {
                    observeEngineState()
                }
            }

            SpeechToTextStartResult.SessionAlreadyActive -> {
                finishSession(
                    SpeechToTextState.Error(
                        SpeechToTextError.RecognitionFailed(RecognitionFailureReason.BUSY),
                    ),
                )
            }
        }
    }

    /** Requests a final transcript once; this never persists or accepts the transcript. */
    suspend fun stop() = operationMutex.withLock {
        val canFinish = synchronized(stateLock) { active && !finishRequested }
        if (!canFinish) return@withLock

        if (!hasRecordAudioPermission()) {
            cancelActive(SpeechToTextState.Error(SpeechToTextError.PermissionDenied))
            return@withLock
        }

        synchronized(stateLock) { finishRequested = true }
        try {
            engine.finish()
            publishObservedState(engine.state.value)
        } catch (cancelled: CancellationException) {
            cancelAfterInterruption(cancelled)
            throw cancelled
        } catch (_: Exception) {
            cancelAfterFailure(
                if (hasRecordAudioPermission()) {
                    SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE)
                } else {
                    SpeechToTextError.PermissionDenied
                },
            )
        }
    }

    /** Pauses only a live session when the selected provider explicitly supports it. */
    suspend fun pause(): Boolean = operationMutex.withLock {
        val canPause = synchronized(stateLock) {
            active && !finishRequested && engine.capabilities.supportsPause &&
                (mutableState.value is SpeechToTextState.Listening ||
                    mutableState.value is SpeechToTextState.Transcribing)
        }
        if (!canPause) return@withLock false
        if (!hasRecordAudioPermission()) {
            cancelActive(SpeechToTextState.Error(SpeechToTextError.PermissionDenied))
            return@withLock false
        }

        try {
            if (engine.pause()) publishObservedState(engine.state.value) else false
        } catch (cancelled: CancellationException) {
            cancelAfterInterruption(cancelled)
            throw cancelled
        } catch (_: Exception) {
            cancelAfterFailure(
                if (hasRecordAudioPermission()) {
                    SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE)
                } else {
                    SpeechToTextError.PermissionDenied
                },
            )
            false
        }
    }

    /** Resumes a paused session only after checking that microphone permission still exists. */
    suspend fun resume(): Boolean = operationMutex.withLock {
        val canResume = synchronized(stateLock) {
            active && !finishRequested && engine.capabilities.supportsPause &&
                mutableState.value is SpeechToTextState.Paused
        }
        if (!canResume) return@withLock false
        if (!hasRecordAudioPermission()) {
            cancelActive(SpeechToTextState.Error(SpeechToTextError.PermissionDenied))
            return@withLock false
        }

        try {
            if (engine.resume()) publishObservedState(engine.state.value) else false
        } catch (cancelled: CancellationException) {
            cancelAfterInterruption(cancelled)
            throw cancelled
        } catch (_: Exception) {
            cancelAfterFailure(
                if (hasRecordAudioPermission()) {
                    SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE)
                } else {
                    SpeechToTextError.PermissionDenied
                },
            )
            false
        }
    }

    /** Cancels and discards this session's transcript draft. */
    suspend fun cancel() = operationMutex.withLock {
        cancelActive(SpeechToTextState.Cancelled)
    }

    /** Call when the system reports that RECORD_AUDIO was revoked during an active session. */
    suspend fun onPermissionRevoked() = operationMutex.withLock {
        cancelActive(SpeechToTextState.Error(SpeechToTextError.PermissionDenied))
    }

    /** Closes this controller and releases its active engine session before leaving Capturar. */
    suspend fun close() = withContext(NonCancellable) {
        operationMutex.withLock {
            synchronized(stateLock) { closed = true }
            cancelActive(SpeechToTextState.Cancelled)
            observationJob?.cancel()
            observationJob = null
        }
    }

    private suspend fun cancelActive(finalState: SpeechToTextState) {
        if (!synchronized(stateLock) { active }) return

        try {
            engine.cancel()
        } catch (cancelled: CancellationException) {
            cancelAfterInterruption(cancelled, finalState)
            throw cancelled
        } catch (_: Exception) {
            stopObservingEngine()
            publish(
                SpeechToTextState.Error(
                    SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE),
                ),
            )
            return
        }

        finishSession(finalState)
    }

    private suspend fun cancelAfterInterruption(
        original: CancellationException,
        finalState: SpeechToTextState = SpeechToTextState.Cancelled,
    ) {
        var cleanupFailure: Throwable? = null
        withContext(NonCancellable) {
            try {
                engine.cancel()
            } catch (error: Exception) {
                cleanupFailure = error
            }
        }

        if (cleanupFailure == null) {
            finishSession(finalState)
        } else {
            cleanupFailure?.let { if (it !== original) original.addSuppressed(it) }
            stopObservingEngine()
            publish(
                SpeechToTextState.Error(
                    SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE),
                ),
            )
        }
    }

    private suspend fun cancelAfterFailure(error: SpeechToTextError) {
        var cleanupFailed = false
        withContext(NonCancellable) {
            try {
                engine.cancel()
            } catch (_: Exception) {
                cleanupFailed = true
            }
        }
        val failureState = SpeechToTextState.Error(
            if (cleanupFailed) {
                SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE)
            } else {
                error
            },
        )
        if (cleanupFailed) {
            stopObservingEngine()
            publish(failureState)
        } else {
            finishSession(failureState)
        }
    }

    private fun observeEngineState() {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            engine.state
                .takeWhile { observed -> publishObservedState(observed) }
                .collect()
        }
        synchronized(stateLock) { observationJob = job }
        job.start()
    }

    /** Returns false after a terminal engine state so the collector releases its subscription. */
    private fun publishObservedState(observed: SpeechToTextState): Boolean =
        synchronized(stateLock) {
            if (!active) return@synchronized false
            mutableState.value = observed
            if (observed.isTerminal()) {
                active = false
                finishRequested = false
                observationJob = null
                false
            } else {
                true
            }
        }

    private fun finishSession(state: SpeechToTextState) {
        synchronized(stateLock) {
            active = false
            finishRequested = false
            observationJob?.cancel()
            observationJob = null
            mutableState.value = state
        }
    }

    private fun stopObservingEngine() {
        synchronized(stateLock) {
            observationJob?.cancel()
            observationJob = null
        }
    }

    private fun publish(state: SpeechToTextState) {
        synchronized(stateLock) { mutableState.value = state }
    }

    private fun SpeechToTextState.isTerminal(): Boolean =
        this is SpeechToTextState.Result ||
            this is SpeechToTextState.Cancelled ||
            this is SpeechToTextState.Error
}
