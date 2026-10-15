package com.yezvi.assistant.domain.speech

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CompletableDeferred

/** Deterministic test double; it has no microphone, Android, or network dependencies. */
internal class FakeSpeechToTextEngine(
    override val capabilities: SpeechToTextCapabilities = SpeechToTextCapabilities(
        supportsPartialResults = true,
        supportedLanguageTags = setOf("es-PE"),
    ),
    private val startError: SpeechToTextError? = null,
    private val finalTranscript: FinalTranscript = FinalTranscript("Nota de voz sintética"),
    private val startGate: CompletableDeferred<Unit>? = null,
    private val finishGate: CompletableDeferred<Unit>? = null,
    private val cancelGate: CompletableDeferred<Unit>? = null,
    private val startException: Exception? = null,
    private val finishException: Exception? = null,
    cancelException: Exception? = null,
) : SpeechToTextEngine {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<SpeechToTextState>(SpeechToTextState.Idle)
    @Volatile
    var isSessionActive = false
        private set

    @Volatile
    var startCallCount = 0
        private set

    @Volatile
    var finishCallCount = 0
        private set

    @Volatile
    var cancelCallCount = 0
        private set

    @Volatile
    var pauseCallCount = 0
        private set

    @Volatile
    var resumeCallCount = 0
        private set

    var nextCancelException: Exception? = cancelException

    override val state: StateFlow<SpeechToTextState> = mutableState
    val activeStateCollectors: Int
        get() = mutableState.subscriptionCount.value

    override suspend fun start(request: SpeechToTextRequest): SpeechToTextStartResult =
        mutex.withLock {
            startCallCount += 1
            if (isSessionActive) return@withLock SpeechToTextStartResult.SessionAlreadyActive

            isSessionActive = true
            mutableState.value = SpeechToTextState.Preparing
            startGate?.await()
            startException?.let { throw it }
            startError?.let { error ->
                mutableState.value = SpeechToTextState.Error(error)
                isSessionActive = false
                return@withLock SpeechToTextStartResult.Started
            }
            SpeechToTextStartResult.Started
        }

    suspend fun beginListening() = mutex.withLock {
        if (isSessionActive) mutableState.value = SpeechToTextState.Listening()
    }

    suspend fun emitPartial(text: String) = mutex.withLock {
        if (!isSessionActive || !capabilities.supportsPartialResults) return@withLock
        val partial = PartialTranscript(text)
        mutableState.value = when (mutableState.value) {
            is SpeechToTextState.Listening -> SpeechToTextState.Listening(partial)
            is SpeechToTextState.Paused -> SpeechToTextState.Paused(partial)
            is SpeechToTextState.Transcribing -> SpeechToTextState.Transcribing(partial)
            else -> mutableState.value
        }
    }

    override suspend fun finish() = mutex.withLock {
        finishCallCount += 1
        if (!isSessionActive) return@withLock
        finishException?.let { throw it }
        val partial = when (val current = mutableState.value) {
            is SpeechToTextState.Listening -> current.partialTranscript
            is SpeechToTextState.Paused -> current.partialTranscript
            is SpeechToTextState.Transcribing -> current.partialTranscript
            else -> null
        }
        mutableState.value = SpeechToTextState.Transcribing(partial)
        finishGate?.await()
        mutableState.value = SpeechToTextState.Result(finalTranscript)
        isSessionActive = false
    }

    override suspend fun pause(): Boolean = mutex.withLock {
        pauseCallCount += 1
        if (!isSessionActive || !capabilities.supportsPause) return@withLock false
        val partial = when (val current = mutableState.value) {
            is SpeechToTextState.Listening -> current.partialTranscript
            is SpeechToTextState.Transcribing -> current.partialTranscript
            else -> null
        }
        mutableState.value = SpeechToTextState.Paused(partial)
        true
    }

    override suspend fun resume(): Boolean = mutex.withLock {
        resumeCallCount += 1
        if (!isSessionActive || mutableState.value !is SpeechToTextState.Paused) return@withLock false
        val partial = (mutableState.value as SpeechToTextState.Paused).partialTranscript
        mutableState.value = SpeechToTextState.Listening(partial)
        true
    }

    override suspend fun cancel() = mutex.withLock {
        cancelCallCount += 1
        if (!isSessionActive) return@withLock
        cancelGate?.await()
        nextCancelException?.let { error ->
            nextCancelException = null
            throw error
        }
        mutableState.value = SpeechToTextState.Cancelled
        isSessionActive = false
    }

    suspend fun fail(error: SpeechToTextError) = mutex.withLock {
        if (!isSessionActive) return@withLock
        mutableState.value = SpeechToTextState.Error(error)
        isSessionActive = false
    }
}
