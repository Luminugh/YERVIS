package com.yezvi.assistant.presentation.capture

import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.speech.RecognitionFailureReason
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextState
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private fun Char.isNotWhitespaceOrOpeningPunctuation(): Boolean =
    !isWhitespace() && this !in "([{«“‘"

private fun Char.isNotWhitespaceOrClosingPunctuation(): Boolean =
    !isWhitespace() && this !in ".,!?;:)]}»”’"

/** Immutable capture identity and temporal context, fixed when the user starts one voice session. */
internal data class VoiceDraftSession(
    val id: String,
    val capturedAtEpochMillis: Long,
    val timezoneId: String,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(timezoneId.isNotBlank()) { "timezoneId must not be blank" }
    }
}

/** Optional range-only metadata for future review hints; it never carries a confidence score. */
internal data class VoiceDraftTextRange(
    val startUtf16: Int,
    val endUtf16Exclusive: Int,
)

/** Literal recognizer output and user-editable text remain separate until explicit acceptance. */
internal data class VoiceTranscriptDraft(
    val session: VoiceDraftSession,
    val recognizedText: String,
    val editableText: String = recognizedText,
    val annotations: List<VoiceDraftTextRange> = emptyList(),
    /** True after combining a written draft with speech; current persistence has no mixed source. */
    val containsMixedTextAndVoice: Boolean = false,
    /** Exact recognizer outputs for each voice segment; edits only affect [editableText]. */
    val recognizedSegments: List<String> = listOf(recognizedText),
) {
    init {
        require(recognizedText.isNotBlank()) { "recognizedText must not be blank" }
    }

    /** Unknown or stale ranges are ignored without touching either text value. */
    fun withAnnotations(ranges: List<VoiceDraftTextRange>): VoiceTranscriptDraft = copy(
        annotations = ranges.filter { range ->
            range.startUtf16 >= 0 &&
                range.endUtf16Exclusive > range.startUtf16 &&
                range.endUtf16Exclusive <= recognizedText.length
        }.distinct(),
    )

    /** Any actual edit invalidates ranges tied to the prior displayed text. */
    fun withEditableText(text: String): VoiceTranscriptDraft = copy(
        editableText = text,
        annotations = if (text == editableText) annotations else emptyList(),
    )

    /** Adds another literal voice segment at the user's selected UTF-16 range. */
    fun withVoiceContinuation(text: String, selectionStart: Int, selectionEnd: Int): VoiceTranscriptDraft {
        require(text.isNotBlank()) { "continuation text must not be blank" }
        val start = selectionStart.coerceIn(0, editableText.length)
        val end = selectionEnd.coerceIn(start, editableText.length)
        val before = editableText.substring(0, start)
        val after = editableText.substring(end)
        val needsSpaceBefore = before.lastOrNull()?.let { it.isNotWhitespaceOrOpeningPunctuation() } == true &&
            text.firstOrNull()?.let { it.isNotWhitespaceOrClosingPunctuation() } == true
        val needsSpaceAfter = text.lastOrNull()?.let { it.isNotWhitespaceOrOpeningPunctuation() } == true &&
            after.firstOrNull()?.let { it.isNotWhitespaceOrClosingPunctuation() } == true
        val insertedText = buildString {
            if (needsSpaceBefore) append(' ')
            append(text)
            if (needsSpaceAfter) append(' ')
        }
        return copy(
            editableText = before + insertedText + after,
            annotations = emptyList(),
            recognizedSegments = recognizedSegments + text,
        )
    }
}

internal enum class ExistingTextDraftDecision {
    REPLACE_WITH_VOICE,
    APPEND_VOICE,
    KEEP_EXISTING,
}

/** Presentation state for one reviewable voice result and its explicit persistence operation. */
internal sealed interface VoiceTranscriptDraftState {
    data object Idle : VoiceTranscriptDraftState

    data class Recognizing(
        val session: VoiceDraftSession,
        val speechState: SpeechToTextState,
    ) : VoiceTranscriptDraftState

    data class AwaitingTextDraftDecision(
        val draft: VoiceTranscriptDraft,
        val existingTextDraft: String,
    ) : VoiceTranscriptDraftState

    data class Candidate(
        val draft: VoiceTranscriptDraft,
        val continuationError: SpeechToTextError? = null,
    ) : VoiceTranscriptDraftState

    data class Saving(
        val draft: VoiceTranscriptDraft,
        val memoryId: String,
    ) : VoiceTranscriptDraftState

    data class SaveFailed(
        val draft: VoiceTranscriptDraft,
        val memoryId: String?,
        val cause: Exception,
    ) : VoiceTranscriptDraftState

    data class Saved(
        val draft: VoiceTranscriptDraft,
        val entry: MemoryEntry,
    ) : VoiceTranscriptDraftState

    data class EmptyTranscript(val session: VoiceDraftSession) : VoiceTranscriptDraftState

    data class RecognitionFailed(
        val session: VoiceDraftSession,
        val cause: SpeechToTextError,
    ) : VoiceTranscriptDraftState

    data class Discarded(val sessionId: String) : VoiceTranscriptDraftState
}

/**
 * Bridges one local STT session to an editable candidate and the existing capture save path.
 * A final transcript is never persisted until [accept] is called explicitly.
 */
internal class VoiceTranscriptDraftCoordinator(
    private val sessionController: VoiceCaptureSessionController,
    private val captureViewModel: CaptureViewModel,
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val timezoneId: () -> String = { TimeZone.getDefault().id },
    private val sessionIdGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private val mutableState = MutableStateFlow<VoiceTranscriptDraftState>(VoiceTranscriptDraftState.Idle)

    private var activeSession: ActiveSession? = null
    private var recognitionObserver: Job? = null
    private var saveObserver: Job? = null
    private var closed = false

    val state: StateFlow<VoiceTranscriptDraftState> = mutableState.asStateFlow()

    /** Starts a fresh recognition unless another session or unreviewed candidate is owned here. */
    suspend fun start(existingTextDraft: String = ""): Boolean = startSession(existingTextDraft)

    /** Continues a voice-only candidate at the cursor range supplied by the capture editor. */
    suspend fun continueAt(
        draft: VoiceTranscriptDraft,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean = startSession(
        existingTextDraft = "",
        continuationDraft = draft,
        insertionStart = selectionStart,
        insertionEnd = selectionEnd,
    )

    private suspend fun startSession(
        existingTextDraft: String,
        continuationDraft: VoiceTranscriptDraft? = null,
        insertionStart: Int = 0,
        insertionEnd: Int = insertionStart,
    ): Boolean = operationMutex.withLock {
        synchronized(stateLock) {
            if (closed || activeSession != null || !mutableState.value.canStartAnotherSession(continuationDraft)) {
                return@withLock false
            }
        }

        val metadata = VoiceDraftSession(
            id = sessionIdGenerator().also { require(it.isNotBlank()) { "sessionId must not be blank" } },
            capturedAtEpochMillis = nowEpochMillis(),
            timezoneId = timezoneId(),
        )
        val session = ActiveSession(
            metadata = metadata,
            existingTextDraft = existingTextDraft,
            continuationDraft = continuationDraft,
            insertionStart = insertionStart,
            insertionEnd = insertionEnd,
        )
        synchronized(stateLock) {
            if (closed || activeSession != null || !mutableState.value.canStartAnotherSession(continuationDraft)) {
                return@withLock false
            }
            activeSession = session
            mutableState.value = VoiceTranscriptDraftState.Recognizing(
                metadata,
                SpeechToTextState.Idle,
            )
        }

        // Ignore the controller's previous terminal snapshot; only transitions after this
        // subscription belong to this requested session.
        recognitionObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var isInitialSnapshot = true
            sessionController.state.collect { speechState ->
                if (isInitialSnapshot) {
                    isInitialSnapshot = false
                } else {
                    consumeSpeechState(session, speechState)
                }
            }
        }

        try {
            sessionController.start()
        } catch (cancelled: CancellationException) {
            discardActiveSession(session)
            throw cancelled
        } catch (_: Exception) {
            consumeSpeechState(
                session,
                SpeechToTextState.Error(
                    SpeechToTextError.RecognitionFailed(
                        RecognitionFailureReason.UNKNOWN,
                    ),
                ),
                allowStartupError = true,
            )
            return@withLock true
        }

        // Permission and model errors can be emitted before the controller becomes active.
        val snapshot = sessionController.state.value
        if (snapshot is SpeechToTextState.Error) {
            consumeSpeechState(session, snapshot, allowStartupError = true)
        } else {
            consumeSpeechState(session, snapshot)
        }
        true
    }

    /** Stops capture and lets the recognizer publish its final result. */
    suspend fun stop() = operationMutex.withLock {
        val session = synchronized(stateLock) { activeSession } ?: return@withLock
        try {
            sessionController.stop()
            consumeSpeechState(session, sessionController.state.value)
        } catch (cancelled: CancellationException) {
            discardActiveSession(session)
            throw cancelled
        } catch (_: Exception) {
            consumeSpeechState(
                session,
                sessionController.state.value as? SpeechToTextState.Error
                    ?: SpeechToTextState.Error(
                        SpeechToTextError.RecognitionFailed(
                            RecognitionFailureReason.AUDIO_CAPTURE,
                        ),
                    ),
                allowStartupError = true,
            )
        }
    }

    /** Pauses the live provider while retaining its session and recognized draft. */
    suspend fun pause(): Boolean = operationMutex.withLock {
        if (closed || synchronized(stateLock) { activeSession == null }) return@withLock false
        sessionController.pause()
    }

    /** Resumes the same live session after another permission check. */
    suspend fun resume(): Boolean = operationMutex.withLock {
        if (closed || synchronized(stateLock) { activeSession == null }) return@withLock false
        sessionController.resume()
    }

    /** Cancels recognition or discards an unaccepted candidate; it never saves. */
    suspend fun discard(): Boolean = operationMutex.withLock {
        val session = synchronized(stateLock) { activeSession }
        if (session != null) {
            discardActiveSession(session)
            try {
                sessionController.cancel()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The controller publishes its cleanup error; reflect it below when available.
            }
            val finalState = sessionController.state.value
            if (finalState is SpeechToTextState.Error) {
                synchronized(stateLock) {
                    mutableState.value = session.continuationDraft?.let { existing ->
                        VoiceTranscriptDraftState.Candidate(existing, finalState.cause)
                    } ?: VoiceTranscriptDraftState.RecognitionFailed(session.metadata, finalState.cause)
                }
            }
            return@withLock true
        }

        synchronized(stateLock) {
            val sessionId = mutableState.value.sessionIdForDiscard() ?: return@withLock false
            saveObserver?.cancel()
            saveObserver = null
            mutableState.value = VoiceTranscriptDraftState.Discarded(sessionId)
            true
        }
    }

    /** Updates only the editable text. The recognizer's literal output remains immutable. */
    fun editText(text: String): Boolean = synchronized(stateLock) {
        val currentDraft = when (val current = mutableState.value) {
            is VoiceTranscriptDraftState.Candidate -> current.draft
            is VoiceTranscriptDraftState.SaveFailed -> current.draft
            else -> return@synchronized false
        }
        val updated = currentDraft.withEditableText(text)
        mutableState.value = VoiceTranscriptDraftState.Candidate(updated)
        true
    }

    /** Resolves a text/voice collision explicitly; the caller's existing text is never mutated. */
    fun resolveTextDraftDecision(decision: ExistingTextDraftDecision): Boolean =
        synchronized(stateLock) {
            val pending = mutableState.value as? VoiceTranscriptDraftState.AwaitingTextDraftDecision
                ?: return@synchronized false
            when (decision) {
                ExistingTextDraftDecision.REPLACE_WITH_VOICE -> {
                    mutableState.value = VoiceTranscriptDraftState.Candidate(
                        pending.draft.withEditableText(pending.draft.recognizedText),
                    )
                }
                ExistingTextDraftDecision.APPEND_VOICE -> {
                    val separator = if (pending.existingTextDraft.lastOrNull()?.isWhitespace() == true) "" else "\n"
                    mutableState.value = VoiceTranscriptDraftState.Candidate(
                        pending.draft.withEditableText(
                            pending.existingTextDraft + separator + pending.draft.recognizedText,
                        ).copy(containsMixedTextAndVoice = true),
                    )
                }
                ExistingTextDraftDecision.KEEP_EXISTING -> {
                    mutableState.value = VoiceTranscriptDraftState.Discarded(pending.draft.session.id)
                }
            }
            true
        }

    /** Persists the accepted editable text through CaptureViewModel; no alternate repository path. */
    suspend fun accept(): Boolean = operationMutex.withLock {
        if (closed) return@withLock false
        val draft = synchronized(stateLock) {
            (mutableState.value as? VoiceTranscriptDraftState.Candidate)?.draft
        } ?: return@withLock false
        // CaptureSourceType has only TEXT and VOICE. Never mislabel combined content as VOICE.
        if (draft.containsMixedTextAndVoice) return@withLock false
        submit(draft)
    }

    /** Retries a failed repository write through CaptureViewModel, preserving its stable ID. */
    suspend fun retrySave(): Boolean = operationMutex.withLock {
        if (closed) return@withLock false
        val failed = synchronized(stateLock) {
            mutableState.value as? VoiceTranscriptDraftState.SaveFailed
        } ?: return@withLock false
        if (failed.draft.containsMixedTextAndVoice) return@withLock false
        val input = failed.draft.toCaptureInput()

        if (failed.memoryId == null) {
            synchronized(stateLock) { mutableState.value = VoiceTranscriptDraftState.Candidate(failed.draft) }
            return@withLock submit(failed.draft)
        }

        val viewModelFailure = captureViewModel.uiState.value as? CaptureSaveState.Error
        if (
            viewModelFailure == null ||
            viewModelFailure.input != input ||
            viewModelFailure.id != failed.memoryId
        ) {
            return@withLock false
        }

        synchronized(stateLock) {
            mutableState.value = VoiceTranscriptDraftState.Saving(failed.draft, failed.memoryId)
        }
        try {
            captureViewModel.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            synchronized(stateLock) {
                mutableState.value = VoiceTranscriptDraftState.SaveFailed(
                    failed.draft,
                    failed.memoryId,
                    failure,
                )
            }
            return@withLock false
        }
        consumeSaveState(failed.draft, input, captureViewModel.uiState.value)
        observeSaveState(failed.draft, input)
        true
    }

    /** Releases an active recording before its owner leaves Capturar. */
    suspend fun close() = withContext(NonCancellable) {
        operationMutex.withLock {
            val session = synchronized(stateLock) {
                closed = true
                activeSession.also { activeSession = null }
            }
            recognitionObserver?.cancel()
            recognitionObserver = null
            saveObserver?.cancel()
            saveObserver = null
            if (session != null) {
                synchronized(stateLock) {
                    mutableState.value = VoiceTranscriptDraftState.Discarded(session.metadata.id)
                }
            }
            sessionController.close()
        }
    }

    private fun submit(draft: VoiceTranscriptDraft): Boolean {
        if (draft.editableText.isBlank()) return false
        val input = draft.toCaptureInput()
        val currentSaveState = captureViewModel.uiState.value
        if (currentSaveState is CaptureSaveState.Saving) {
            if (currentSaveState.input != input) return false
            synchronized(stateLock) {
                mutableState.value = VoiceTranscriptDraftState.Saving(draft, currentSaveState.id)
            }
            observeSaveState(draft, input)
            return true
        }

        return try {
            captureViewModel.save(input)
            val latest = captureViewModel.uiState.value
            if (consumeSaveState(draft, input, latest) == null) {
                synchronized(stateLock) { mutableState.value = VoiceTranscriptDraftState.Candidate(draft) }
                false
            } else {
                if (latest is CaptureSaveState.Saving) observeSaveState(draft, input)
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            synchronized(stateLock) {
                mutableState.value = VoiceTranscriptDraftState.SaveFailed(draft, null, failure)
            }
            true
        }
    }

    private fun observeSaveState(draft: VoiceTranscriptDraft, input: CaptureInput) {
        saveObserver?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            captureViewModel.uiState.collect { saveState ->
                val terminal = consumeSaveState(draft, input, saveState)
                if (terminal == true) {
                    saveObserver = null
                    cancel()
                }
            }
        }
        saveObserver = job
        job.start()
    }

    /** Returns null for another capture, false while matching persistence continues, or true at end. */
    private fun consumeSaveState(
        draft: VoiceTranscriptDraft,
        input: CaptureInput,
        saveState: CaptureSaveState,
    ): Boolean? {
        val next = when (saveState) {
            is CaptureSaveState.Saving -> if (saveState.input == input) {
                VoiceTranscriptDraftState.Saving(draft, saveState.id)
            } else {
                return null
            }
            is CaptureSaveState.Saved -> if (saveState.entry.matches(input)) {
                VoiceTranscriptDraftState.Saved(draft, saveState.entry)
            } else {
                return null
            }
            is CaptureSaveState.Error -> if (saveState.input == input) {
                VoiceTranscriptDraftState.SaveFailed(draft, saveState.id, saveState.cause)
            } else {
                return null
            }
            CaptureSaveState.Idle -> return null
        }

        synchronized(stateLock) {
            val currentDraft = when (val current = mutableState.value) {
                is VoiceTranscriptDraftState.Saving -> current.draft
                is VoiceTranscriptDraftState.SaveFailed -> current.draft
                is VoiceTranscriptDraftState.Candidate -> current.draft
                is VoiceTranscriptDraftState.Saved -> current.draft
                else -> null
            }
            if (currentDraft?.session?.id != draft.session.id) return null
            mutableState.value = next
        }
        return next !is VoiceTranscriptDraftState.Saving
    }

    private fun consumeSpeechState(
        session: ActiveSession,
        speechState: SpeechToTextState,
        allowStartupError: Boolean = false,
    ) {
        synchronized(stateLock) {
            if (closed || activeSession !== session) return
            when (speechState) {
                SpeechToTextState.Idle -> Unit
                SpeechToTextState.Preparing,
                is SpeechToTextState.Listening,
                is SpeechToTextState.Paused,
                is SpeechToTextState.Transcribing -> {
                    session.observedActiveState = true
                    mutableState.value = VoiceTranscriptDraftState.Recognizing(
                        session.metadata,
                        speechState,
                    )
                }
                is SpeechToTextState.Result -> {
                    if (!session.observedActiveState) return
                    activeSession = null
                    recognitionObserver?.cancel()
                    recognitionObserver = null
                    val transcript = speechState.transcript.text
                    if (transcript.isBlank()) {
                        mutableState.value = session.continuationDraft?.let { existing ->
                            VoiceTranscriptDraftState.Candidate(
                                draft = existing,
                                continuationError = SpeechToTextError.RecognitionFailed(
                                    RecognitionFailureReason.NO_SPEECH,
                                ),
                            )
                        } ?: VoiceTranscriptDraftState.EmptyTranscript(session.metadata)
                    } else {
                        val candidate = session.continuationDraft?.withVoiceContinuation(
                            text = transcript,
                            selectionStart = session.insertionStart,
                            selectionEnd = session.insertionEnd,
                        ) ?: VoiceTranscriptDraft(session.metadata, transcript)
                        mutableState.value = if (session.existingTextDraft.isBlank()) {
                            VoiceTranscriptDraftState.Candidate(candidate)
                        } else {
                            VoiceTranscriptDraftState.AwaitingTextDraftDecision(
                                draft = candidate,
                                existingTextDraft = session.existingTextDraft,
                            )
                        }
                    }
                }
                SpeechToTextState.Cancelled -> {
                    if (!session.observedActiveState) return
                    activeSession = null
                    recognitionObserver?.cancel()
                    recognitionObserver = null
                    mutableState.value = session.continuationDraft?.let { VoiceTranscriptDraftState.Candidate(it) }
                        ?: VoiceTranscriptDraftState.Discarded(session.metadata.id)
                }
                is SpeechToTextState.Error -> {
                    if (!session.observedActiveState && !allowStartupError) return
                    activeSession = null
                    recognitionObserver?.cancel()
                    recognitionObserver = null
                    mutableState.value = session.continuationDraft?.let { existing ->
                        VoiceTranscriptDraftState.Candidate(existing, speechState.cause)
                    } ?: VoiceTranscriptDraftState.RecognitionFailed(session.metadata, speechState.cause)
                }
            }
        }
    }

    private fun discardActiveSession(session: ActiveSession) {
        synchronized(stateLock) {
            if (activeSession !== session) return
            activeSession = null
            recognitionObserver?.cancel()
            recognitionObserver = null
            mutableState.value = session.continuationDraft?.let { VoiceTranscriptDraftState.Candidate(it) }
                ?: VoiceTranscriptDraftState.Discarded(session.metadata.id)
        }
    }

    private fun VoiceTranscriptDraftState.canStartAnotherSession(
        continuationDraft: VoiceTranscriptDraft? = null,
    ): Boolean = when (this) {
        VoiceTranscriptDraftState.Idle,
        is VoiceTranscriptDraftState.Discarded,
        is VoiceTranscriptDraftState.EmptyTranscript,
        is VoiceTranscriptDraftState.RecognitionFailed,
        is VoiceTranscriptDraftState.Saved -> true
        is VoiceTranscriptDraftState.Candidate -> continuationDraft != null &&
            continuationDraft.session.id == draft.session.id && !draft.containsMixedTextAndVoice
        is VoiceTranscriptDraftState.Recognizing,
        is VoiceTranscriptDraftState.AwaitingTextDraftDecision,
        is VoiceTranscriptDraftState.Saving,
        is VoiceTranscriptDraftState.SaveFailed -> false
    }

    private fun VoiceTranscriptDraftState.sessionIdForDiscard(): String? = when (this) {
        is VoiceTranscriptDraftState.AwaitingTextDraftDecision -> draft.session.id
        is VoiceTranscriptDraftState.Candidate -> draft.session.id
        is VoiceTranscriptDraftState.SaveFailed -> draft.session.id
        is VoiceTranscriptDraftState.EmptyTranscript -> session.id
        is VoiceTranscriptDraftState.RecognitionFailed -> session.id
        else -> null
    }

    private fun VoiceTranscriptDraft.toCaptureInput() = CaptureInput(
        rawText = editableText,
        sourceType = CaptureSourceType.VOICE,
        capturedAtEpochMillis = session.capturedAtEpochMillis,
        timezoneId = session.timezoneId,
    )

    private fun MemoryEntry.matches(input: CaptureInput): Boolean =
        rawText == input.rawText &&
            sourceType == input.sourceType &&
            capturedAtEpochMillis == input.capturedAtEpochMillis &&
            timezoneId == input.timezoneId

    private data class ActiveSession(
        val metadata: VoiceDraftSession,
        val existingTextDraft: String,
        val continuationDraft: VoiceTranscriptDraft? = null,
        val insertionStart: Int = 0,
        val insertionEnd: Int = insertionStart,
        var observedActiveState: Boolean = false,
    )

}
