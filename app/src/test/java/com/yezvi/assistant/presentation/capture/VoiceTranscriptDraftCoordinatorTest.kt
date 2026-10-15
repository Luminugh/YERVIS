package com.yezvi.assistant.presentation.capture

import androidx.lifecycle.ViewModelStore
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryRepository
import com.yezvi.assistant.domain.speech.FinalTranscript
import com.yezvi.assistant.domain.speech.RecognitionFailureReason
import com.yezvi.assistant.domain.speech.SpeechToTextCapabilities
import com.yezvi.assistant.domain.speech.SpeechToTextEngine
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextStartResult
import com.yezvi.assistant.domain.speech.SpeechToTextState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTranscriptDraftCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    @Test
    fun finalTranscriptIsLiteralAndRemainsUnpersistedUntilAccept() = runTest(dispatcher) {
        val fixture = fixture(listOf("  YEZVI, Kotlin 8792!\n"))

        assertTrue(fixture.coordinator.start())
        runCurrent()
        fixture.coordinator.stop()
        runCurrent()

        val candidate = fixture.coordinator.state.value as VoiceTranscriptDraftState.Candidate
        assertEquals("  YEZVI, Kotlin 8792!\n", candidate.draft.recognizedText)
        assertEquals(candidate.draft.recognizedText, candidate.draft.editableText)
        assertTrue(candidate.draft.annotations.isEmpty())
        assertTrue(fixture.repository.calls.isEmpty())
        fixture.close()
    }

    @Test
    fun editingDoesNotChangeRecognizedTextAndDropsStaleRanges() = runTest(dispatcher) {
        val fixture = fixture(listOf("Texto reconocido literal."))
        fixture.coordinator.start()
        runCurrent()
        fixture.coordinator.stop()
        runCurrent()
        val original = (fixture.coordinator.state.value as VoiceTranscriptDraftState.Candidate).draft
        val annotated = original.withAnnotations(
            listOf(
                VoiceDraftTextRange(0, 6),
                VoiceDraftTextRange(-1, 2),
                VoiceDraftTextRange(3, 3),
                VoiceDraftTextRange(0, original.recognizedText.length + 1),
            ),
        )
        assertEquals(original.recognizedText, annotated.recognizedText)
        assertEquals(original.editableText, annotated.editableText)
        assertEquals(listOf(VoiceDraftTextRange(0, 6)), annotated.annotations)

        assertTrue(fixture.coordinator.editText("Texto corregido por usuario."))
        val edited = (fixture.coordinator.state.value as VoiceTranscriptDraftState.Candidate).draft
        assertEquals("Texto reconocido literal.", edited.recognizedText)
        assertEquals("Texto corregido por usuario.", edited.editableText)
        assertTrue(edited.annotations.isEmpty())
        fixture.close()
    }

    @Test
    fun existingTextDraftRequiresExplicitChoiceAndCanBeKeptUntouched() = runTest(dispatcher) {
        val fixture = fixture(listOf("nota hablada"))
        val existingTextDraft = "nota escrita que debe conservarse"
        fixture.coordinator.start(existingTextDraft)
        runCurrent()
        fixture.coordinator.stop()
        runCurrent()

        val conflict = fixture.coordinator.state.value as VoiceTranscriptDraftState.AwaitingTextDraftDecision
        assertEquals(existingTextDraft, conflict.existingTextDraft)
        assertFalse(fixture.coordinator.accept())
        assertTrue(fixture.repository.calls.isEmpty())
        assertTrue(fixture.coordinator.resolveTextDraftDecision(ExistingTextDraftDecision.KEEP_EXISTING))
        assertEquals("nota escrita que debe conservarse", existingTextDraft)
        assertTrue(fixture.coordinator.state.value is VoiceTranscriptDraftState.Discarded)
        assertTrue(fixture.repository.calls.isEmpty())
        fixture.close()
    }

    @Test
    fun appendOrReplaceDecisionChangesOnlyEditableCandidateText() = runTest(dispatcher) {
        val fixture = fixture(listOf("voz exacta", "otra voz exacta"))
        val existingTextDraft = "texto previo"
        fixture.coordinator.start(existingTextDraft)
        runCurrent()
        fixture.coordinator.stop()
        runCurrent()
        assertTrue(fixture.coordinator.resolveTextDraftDecision(ExistingTextDraftDecision.APPEND_VOICE))
        val appended = (fixture.coordinator.state.value as VoiceTranscriptDraftState.Candidate).draft
        assertEquals("voz exacta", appended.recognizedText)
        assertEquals("texto previo\nvoz exacta", appended.editableText)
        assertTrue(appended.containsMixedTextAndVoice)
        assertEquals("texto previo", existingTextDraft)
        assertFalse(fixture.coordinator.accept())
        assertTrue("mixed origin must never reach the repository", fixture.repository.calls.isEmpty())

        assertTrue(fixture.coordinator.discard())
        assertTrue(fixture.coordinator.start(existingTextDraft))
        runCurrent()
        fixture.coordinator.stop()
        runCurrent()
        assertTrue(fixture.coordinator.resolveTextDraftDecision(ExistingTextDraftDecision.REPLACE_WITH_VOICE))
        val replaced = (fixture.coordinator.state.value as VoiceTranscriptDraftState.Candidate).draft
        assertEquals("otra voz exacta", replaced.recognizedText)
        assertEquals("otra voz exacta", replaced.editableText)
        assertFalse(replaced.containsMixedTextAndVoice)
        assertTrue(fixture.repository.calls.isEmpty())
        fixture.close()
    }

    @Test
    fun blankCorrectionCannotBeSaved() = runTest(dispatcher) {
        val fixture = fixture(listOf("texto reconocible"))
        fixture.coordinator.start()
        runCurrent()
        fixture.coordinator.stop()
        runCurrent()

        assertTrue(fixture.coordinator.editText(" \n"))
        assertFalse(fixture.coordinator.accept())
        assertTrue(fixture.repository.calls.isEmpty())
        assertEquals(
            " \n",
            (fixture.coordinator.state.value as VoiceTranscriptDraftState.Candidate).draft.editableText,
        )
        fixture.close()
    }

    @Test
    fun acceptancePersistsExactEditedTextAndVoiceMetadataThroughCaptureViewModel() = runTest(dispatcher) {
        val fixture = fixture(listOf("YEZVI usa Kotln."))
        fixture.coordinator.start()
        runCurrent()
        fixture.coordinator.stop()
        runCurrent()
        fixture.coordinator.editText("YEZVI usa Kotlin.")

        assertTrue(fixture.repository.calls.isEmpty())
        assertTrue(fixture.coordinator.accept())
        advanceUntilIdle()
        runCurrent()

        assertTrue("CaptureViewModel state: ${fixture.captureViewModel.uiState.value}", fixture.captureViewModel.uiState.value is CaptureSaveState.Saved)
        val saved = fixture.coordinator.state.value as VoiceTranscriptDraftState.Saved
        assertEquals("YEZVI usa Kotln.", saved.draft.recognizedText)
        assertEquals("YEZVI usa Kotlin.", saved.entry.rawText)
        assertEquals(CaptureSourceType.VOICE, saved.entry.sourceType)
        assertEquals(1_791_234_567_890L, saved.entry.capturedAtEpochMillis)
        assertEquals("America/Lima", saved.entry.timezoneId)
        assertEquals("memory-1", saved.entry.id)
        assertEquals(1, fixture.repository.calls.size)
        assertEquals("YEZVI usa Kotlin.", fixture.repository.calls.single().input.rawText)
        assertEquals(CaptureSourceType.VOICE, fixture.repository.calls.single().input.sourceType)
        assertFalse(fixture.coordinator.accept())
        assertEquals(1, fixture.repository.calls.size)
        fixture.close()
    }

    @Test
    fun failedWriteKeepsDraftAndRetryUsesCaptureViewModelId() = runTest(dispatcher) {
        val failure = IllegalStateException("synthetic repository failure")
        val fixture = fixture(listOf("borrador local"), saveFailureOnce = failure)
        fixture.coordinator.start()
        runCurrent()
        fixture.coordinator.stop()
        runCurrent()
        val draft = (fixture.coordinator.state.value as VoiceTranscriptDraftState.Candidate).draft

        fixture.coordinator.accept()
        advanceUntilIdle()
        runCurrent()
        assertTrue("CaptureViewModel state: ${fixture.captureViewModel.uiState.value}", fixture.captureViewModel.uiState.value is CaptureSaveState.Error)
        val failed = fixture.coordinator.state.value as VoiceTranscriptDraftState.SaveFailed
        assertEquals(draft, failed.draft)
        assertEquals("memory-1", failed.memoryId)
        assertEquals(failure, failed.cause)

        assertTrue(fixture.coordinator.retrySave())
        advanceUntilIdle()
        runCurrent()
        val saved = fixture.coordinator.state.value as VoiceTranscriptDraftState.Saved
        assertEquals(draft, saved.draft)
        assertEquals("memory-1", saved.entry.id)
        assertEquals(listOf("memory-1", "memory-1"), fixture.repository.calls.map { it.id })
        fixture.close()
    }

    @Test
    fun discardIgnoresLateResultAndNextSessionStartsWithFreshIdentityAndText() = runTest(dispatcher) {
        val engine = ScriptedSpeechEngine(listOf("sesión anterior", "sesión nueva"))
        val fixture = fixture(engine)

        fixture.coordinator.start()
        runCurrent()
        val firstId = (fixture.coordinator.state.value as VoiceTranscriptDraftState.Recognizing).session.id
        assertTrue(fixture.coordinator.discard())
        engine.emitLateResult("resultado tardío anterior")
        runCurrent()
        assertEquals(VoiceTranscriptDraftState.Discarded(firstId), fixture.coordinator.state.value)

        assertTrue(fixture.coordinator.start())
        runCurrent()
        val secondId = (fixture.coordinator.state.value as VoiceTranscriptDraftState.Recognizing).session.id
        assertTrue(firstId != secondId)
        fixture.coordinator.stop()
        runCurrent()
        val candidate = fixture.coordinator.state.value as VoiceTranscriptDraftState.Candidate
        assertEquals("sesión nueva", candidate.draft.recognizedText)
        assertEquals(secondId, candidate.draft.session.id)
        assertTrue(fixture.repository.calls.isEmpty())
        fixture.close()
    }

    @Test
    fun emptyAndFailedRecognitionNeverBecomeSaveableCandidates() = runTest(dispatcher) {
        val engine = ScriptedSpeechEngine(listOf("", ""))
        val fixture = fixture(engine)
        fixture.coordinator.start()
        runCurrent()
        fixture.coordinator.stop()
        runCurrent()
        assertTrue(fixture.coordinator.state.value is VoiceTranscriptDraftState.EmptyTranscript)
        assertFalse(fixture.coordinator.accept())

        fixture.coordinator.start()
        runCurrent()
        engine.fail(
            SpeechToTextError.RecognitionFailed(RecognitionFailureReason.NO_SPEECH),
        )
        runCurrent()
        assertTrue(fixture.coordinator.state.value is VoiceTranscriptDraftState.RecognitionFailed)
        assertFalse(fixture.coordinator.accept())
        assertTrue(fixture.repository.calls.isEmpty())
        fixture.close()
    }

    private fun TestScope.fixture(
        transcripts: List<String>,
        saveFailureOnce: Exception? = null,
    ): Fixture = fixture(ScriptedSpeechEngine(transcripts), saveFailureOnce)

    private fun TestScope.fixture(
        engine: ScriptedSpeechEngine,
        saveFailureOnce: Exception? = null,
    ): Fixture {
        val repository = FakeMemoryRepository(saveFailureOnce)
        val viewModel = CaptureViewModel(repository) { "memory-1" }
        val controller = VoiceCaptureSessionController(engine, backgroundScope, { true })
        var nextSession = 0
        val coordinator = VoiceTranscriptDraftCoordinator(
            sessionController = controller,
            captureViewModel = viewModel,
            scope = backgroundScope,
            nowEpochMillis = { 1_791_234_567_890L },
            timezoneId = { "America/Lima" },
            sessionIdGenerator = { "voice-session-${++nextSession}" },
        )
        return Fixture(repository, viewModel, coordinator, engine)
    }

    private data class Fixture(
        val repository: FakeMemoryRepository,
        val captureViewModel: CaptureViewModel,
        val coordinator: VoiceTranscriptDraftCoordinator,
        val engine: ScriptedSpeechEngine,
    ) {
        suspend fun close() {
            coordinator.close()
            ViewModelStore().apply { put("capture", captureViewModel) }.clear()
        }
    }

    private class FakeMemoryRepository(
        private val failFirstSave: Exception?,
    ) : MemoryRepository {
        val calls = mutableListOf<SaveCall>()
        private var failureConsumed = false

        override suspend fun save(input: CaptureInput, id: String): MemoryEntry {
            calls += SaveCall(input, id)
            if (!failureConsumed && failFirstSave != null) {
                failureConsumed = true
                throw failFirstSave
            }
            return MemoryEntry(
                id = id,
                rawText = input.rawText,
                sourceType = input.sourceType,
                capturedAtEpochMillis = input.capturedAtEpochMillis,
                timezoneId = input.timezoneId,
                status = CaptureStatus.SAVED,
            )
        }

        override suspend fun findById(id: String): MemoryEntry? = null
        override fun observeById(id: String): Flow<MemoryEntry?> = flowOf(null)
        override fun observeAll(): Flow<List<MemoryEntry>> = flowOf(emptyList())
        override suspend fun deleteById(id: String): Boolean = false
        override suspend fun deleteByIds(ids: Set<String>): Int = 0
    }

    private data class SaveCall(val input: CaptureInput, val id: String)

    private class ScriptedSpeechEngine(transcripts: List<String>) : SpeechToTextEngine {
        private val mutableState = MutableStateFlow<SpeechToTextState>(SpeechToTextState.Idle)
        private val queuedTranscripts = java.util.ArrayDeque(transcripts)
        private var active = false
        private var currentTranscript = ""

        override val capabilities = SpeechToTextCapabilities(supportsPartialResults = true)
        override val state: StateFlow<SpeechToTextState> = mutableState

        override suspend fun start(request: SpeechToTextRequest): SpeechToTextStartResult {
            if (active) return SpeechToTextStartResult.SessionAlreadyActive
            active = true
            currentTranscript = queuedTranscripts.pollFirst().orEmpty()
            mutableState.value = SpeechToTextState.Preparing
            mutableState.value = SpeechToTextState.Listening()
            return SpeechToTextStartResult.Started
        }

        override suspend fun finish() {
            if (!active) return
            mutableState.value = SpeechToTextState.Transcribing()
            mutableState.value = SpeechToTextState.Result(FinalTranscript(currentTranscript))
            active = false
        }

        override suspend fun cancel() {
            if (!active) return
            active = false
            mutableState.value = SpeechToTextState.Cancelled
        }

        suspend fun fail(error: SpeechToTextError) {
            if (!active) return
            active = false
            mutableState.value = SpeechToTextState.Error(error)
        }

        fun emitLateResult(text: String) {
            mutableState.value = SpeechToTextState.Result(FinalTranscript(text))
        }
    }

    class MainDispatcherRule(
        val dispatcher: TestDispatcher = StandardTestDispatcher(),
    ) : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(dispatcher)
        }

        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
}
