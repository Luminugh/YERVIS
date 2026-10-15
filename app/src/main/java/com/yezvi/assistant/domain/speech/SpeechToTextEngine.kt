package com.yezvi.assistant.domain.speech

import kotlinx.coroutines.flow.StateFlow

/** Request parameters shared by local speech recognition providers. */
data class SpeechToTextRequest(
    /** BCP 47 language tag requested for recognition, for example `es-PE`. */
    val languageTag: String,
) {
    init {
        require(languageTag.isNotBlank()) { "languageTag must not be blank" }
    }
}

/** Capabilities which can be checked before starting a recognition session. */
data class SpeechToTextCapabilities(
    /** Whether this engine can emit revisable partial transcripts before the final result. */
    val supportsPartialResults: Boolean,
    /**
     * Language tags the engine currently reports as supported, or `null` when it cannot provide
     * that list. A reported language may still become unavailable before recognition starts.
     */
    val supportedLanguageTags: Set<String>? = null,
    /** Whether a live session can pause and resume microphone capture without ending its draft. */
    val supportsPause: Boolean = false,
)

/** An intermediate hypothesis. The user must be able to review it before saving it as a memory. */
data class PartialTranscript(val text: String)

/** The final recognition result. It remains a draft until the user reviews and accepts it. */
data class FinalTranscript(val text: String)

/** Provider-neutral lifecycle and output of one speech recognition session. */
sealed interface SpeechToTextState {
    data object Idle : SpeechToTextState
    data object Preparing : SpeechToTextState
    data class Listening(
        val partialTranscript: PartialTranscript? = null,
        /** True once the local engine's VAD detects voice activity in captured PCM. */
        val hasVoiceActivity: Boolean = false,
    ) : SpeechToTextState
    data class Paused(
        val partialTranscript: PartialTranscript? = null,
        val hasVoiceActivity: Boolean = false,
    ) : SpeechToTextState
    data class Transcribing(
        val partialTranscript: PartialTranscript? = null,
        val hasVoiceActivity: Boolean = false,
    ) : SpeechToTextState
    data class Result(val transcript: FinalTranscript) : SpeechToTextState
    data object Cancelled : SpeechToTextState
    data class Error(val cause: SpeechToTextError) : SpeechToTextState
}

/** Errors the caller can present or use to offer a user-controlled retry. */
sealed interface SpeechToTextError {
    data object EngineUnavailable : SpeechToTextError
    data object OfflineModelMissing : SpeechToTextError
    data object PermissionDenied : SpeechToTextError
    data class RecognitionFailed(val reason: RecognitionFailureReason) : SpeechToTextError
}

/** Provider-neutral recognition failure details useful for deciding whether to retry. */
enum class RecognitionFailureReason {
    NO_SPEECH,
    AUDIO_CAPTURE,
    BUSY,
    LANGUAGE_NOT_SUPPORTED,
    LANGUAGE_UNAVAILABLE,
    SERVICE_DISCONNECTED,
    UNKNOWN,
}

/** Result of requesting a new session; a busy engine leaves its current state untouched. */
sealed interface SpeechToTextStartResult {
    data object Started : SpeechToTextStartResult
    data object SessionAlreadyActive : SpeechToTextStartResult
}

/**
 * Local speech-to-text boundary, independent of Android APIs, UI, and memory persistence.
 *
 * Implementations must process audio on-device only. They must never upload audio or silently
 * fall back to cloud recognition; unavailable local recognition is reported through [state].
 * [state] is the current session snapshot, including the latest partial transcript when supported.
 * `start` accepts at most one active session; a concurrent request returns
 * [SpeechToTextStartResult.SessionAlreadyActive] without replacing that session. Call [finish] to
 * request a final result or [cancel] to discard the in-progress draft. Cancellation exceptions
 * from coroutine callers must be propagated.
 */
interface SpeechToTextEngine {
    val capabilities: SpeechToTextCapabilities
    val state: StateFlow<SpeechToTextState>

    /** Begins recognition for [request], or reports that another session is still active. */
    suspend fun start(request: SpeechToTextRequest): SpeechToTextStartResult

    /** Stops listening and requests a final transcript for the active session. */
    suspend fun finish()

    /** Pauses active microphone input while retaining the current recognition session. */
    suspend fun pause(): Boolean = false

    /** Resumes a session paused through [pause]. */
    suspend fun resume(): Boolean = false

    /** Cancels the active session and discards its transcript draft. */
    suspend fun cancel()
}
