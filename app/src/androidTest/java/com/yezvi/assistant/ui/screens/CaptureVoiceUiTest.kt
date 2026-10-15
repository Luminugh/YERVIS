package com.yezvi.assistant.ui.screens

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yezvi.assistant.MainActivity
import com.yezvi.assistant.R
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryRepository
import com.yezvi.assistant.domain.speech.FinalTranscript
import com.yezvi.assistant.domain.speech.LocalConfirmationSpeech
import com.yezvi.assistant.domain.speech.LocalSpeechAvailability
import com.yezvi.assistant.domain.speech.PartialTranscript
import com.yezvi.assistant.domain.speech.RecognitionFailureReason
import com.yezvi.assistant.domain.speech.SpeechToTextCapabilities
import com.yezvi.assistant.domain.speech.SpeechToTextEngine
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextStartResult
import com.yezvi.assistant.domain.speech.SpeechToTextState
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.presentation.capture.CaptureViewModel
import com.yezvi.assistant.presentation.capture.VoiceDraftSession
import com.yezvi.assistant.presentation.capture.VoiceTranscriptDraft
import com.yezvi.assistant.presentation.capture.VoiceTranscriptDraftState
import com.yezvi.assistant.ui.theme.YERVISTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI coverage for F3.08/F3.09; never opens a microphone or a personal database. */
@RunWith(AndroidJUnit4::class)
class CaptureVoiceUiTest {
    @Test
    fun settingsAloneControlsOptInAndCaptureHasNoDuplicateControls() {
        val settings = mutableStateOf(true)
        val repository = NoSaveRepository()
        val engine = SyntheticEngine()
        val speech = object : LocalConfirmationSpeech {
            override val availability = MutableStateFlow(LocalSpeechAvailability.READY)
            var calls = 0
            override fun initialize() = Unit
            override fun announceSaved(): Boolean { calls++; return true }
            override fun stop() = Unit
            override fun close() = Unit
        }
        val viewModel = CaptureViewModel(repository, confirmationSpeechFactory = { speech })
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                YERVISTheme {
                    if (settings.value) SettingsScreen(MemoryUiCallbacks({}, {}, {}), viewModel.spokenConfirmation)
                    else CaptureScreen(MemoryUiCallbacks({}, {}, {}), viewModel,
                        voiceEngineFactory = { _, _ -> engine }, voicePermissionCheck = { true })
                }
            }
        }
        compose.onNodeWithTag("settings_voice_enabled_row").performClick()
        compose.runOnIdle { settings.value = false }
        compose.onNodeWithTag("capture_spoken_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("capture_tts_availability").assertDoesNotExist()
        compose.runOnIdle { assertTrue(viewModel.spokenConfirmation.state.value.enabled) }
        compose.runOnIdle { settings.value = true }
        compose.onNodeWithTag("settings_voice_enabled_row").assertIsOn().performClick()
        compose.onNodeWithTag("settings_voice_enabled_row").assertIsOff()
        compose.runOnIdle {
            assertEquals(0, speech.calls)
            assertEquals(0, repository.saves)
            viewModel.spokenConfirmation.close()
        }
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val session = VoiceDraftSession("synthetic-voice", 1_791_234_567_890L, "America/Lima")

    @Test
    fun panelReflectsRecognitionAndReviewStates() {
        val state = mutableStateOf<VoiceTranscriptDraftState>(VoiceTranscriptDraftState.Idle)
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                YERVISTheme {
                    VoiceRecordingPanel(state.value, false, false, true, {}, {}, {}, reduceMotion = true)
                }
            }
        }
        fun expect(next: VoiceTranscriptDraftState, label: String) {
            compose.runOnIdle { state.value = next }
            compose.onNodeWithTag("capture_voice_status").assertTextEquals(label)
        }
        expect(VoiceTranscriptDraftState.Idle, "En reposo")
        compose.onNodeWithTag("capture_voice_start").assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        expect(VoiceTranscriptDraftState.Recognizing(session, SpeechToTextState.Preparing), "Preparando voz local…")
        compose.onNodeWithTag("capture_voice_start").assertIsNotEnabled()
        expect(VoiceTranscriptDraftState.Recognizing(session, SpeechToTextState.Listening(PartialTranscript("Texto provisional"))), "Escuchando")
        compose.onNodeWithTag("capture_voice_transcript").assertTextEquals("Texto provisional")
        compose.onNodeWithTag("capture_voice_stop").assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("capture_voice_cancel").assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        expect(VoiceTranscriptDraftState.Recognizing(session, SpeechToTextState.Transcribing()), "Transcribiendo…")
        expect(VoiceTranscriptDraftState.Candidate(VoiceTranscriptDraft(session, "Texto final")), "Transcripción pendiente de revisión")
        expect(VoiceTranscriptDraftState.Discarded(session.id), "Cancelado")
    }

    @Test
    fun captureOpensWithoutFocusingTheDraftEditor() {
        showCapture(SyntheticEngine(), NoSaveRepository(), permission = true)
        compose.waitForIdle()
        compose.onNodeWithTag("capture_text_field").assertIsNotFocused()
    }

    @Test
    fun startButtonBecomesPauseThenResumeWhileStopAndCancelStayAvailable() {
        val engine = SyntheticEngine()
        showCapture(engine, NoSaveRepository(), permission = true)

        click("capture_voice_start")
        compose.waitUntil(5_000) { engine.starts == 1 }
        compose.runOnIdle { engine.state.value = SpeechToTextState.Listening() }
        compose.onNodeWithTag("capture_voice_start").assertTextEquals("Pausar")
        compose.onNodeWithTag("capture_voice_stop").assertIsEnabled()
        compose.onNodeWithTag("capture_voice_cancel").assertIsEnabled()

        click("capture_voice_start")
        compose.waitUntil(5_000) { engine.pauses == 1 }
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Pausado")
        compose.onNodeWithTag("capture_voice_start").assertTextEquals("Reanudar")
        compose.onNodeWithTag("capture_voice_stop").assertIsEnabled()
        compose.onNodeWithTag("capture_voice_cancel").assertIsEnabled()

        click("capture_voice_start")
        compose.waitUntil(5_000) { engine.resumes == 1 }
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Escuchando")
        click("capture_voice_cancel")
        compose.waitUntil(5_000) { engine.cancels == 1 }
    }

    @Test
    fun panelErrorsAndSilenceRemainVisibleAtDoubleFontScale() {
        val state = mutableStateOf<VoiceTranscriptDraftState>(VoiceTranscriptDraftState.EmptyTranscript(session))
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    YERVISTheme {
                        VoiceRecordingPanel(state.value, false, false, true, {}, {}, {}, reduceMotion = true)
                    }
                }
            }
        }
        val errors = listOf(
            SpeechToTextError.PermissionDenied to R.string.capture_voice_permission_error,
            SpeechToTextError.EngineUnavailable to R.string.capture_voice_engine_error,
            SpeechToTextError.OfflineModelMissing to R.string.capture_voice_model_error,
            SpeechToTextError.RecognitionFailed(RecognitionFailureReason.NO_SPEECH) to R.string.capture_voice_no_speech,
        )
        compose.onNodeWithTag("capture_voice_status").assertIsDisplayed()
        compose.onNodeWithTag("capture_voice_detail")
            .assertTextEquals(compose.activity.getString(R.string.capture_voice_no_speech))
        errors.forEach { (error, resource) ->
            compose.runOnIdle { state.value = VoiceTranscriptDraftState.RecognitionFailed(session, error) }
            compose.onNodeWithTag("capture_voice_status").assertTextEquals(compose.activity.getString(R.string.capture_voice_error))
            compose.onNodeWithTag("capture_voice_detail").assertIsDisplayed()
                .assertTextEquals(compose.activity.getString(resource))
            compose.onNodeWithTag("capture_voice_start").assertIsEnabled()
        }
    }

    @Test
    fun stopRequestedKeepsTranscribingWhenEngineReturnsListeningDuringDrain() {
        val state = mutableStateOf<VoiceTranscriptDraftState>(
            VoiceTranscriptDraftState.Recognizing(session, SpeechToTextState.Transcribing()),
        )
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                YERVISTheme {
                    VoiceRecordingPanel(state.value, true, false, true, {}, {}, {}, reduceMotion = true)
                }
            }
        }
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Transcribiendo…")
        compose.runOnIdle {
            state.value = VoiceTranscriptDraftState.Recognizing(session, SpeechToTextState.Listening())
        }
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Transcribiendo…")
        compose.onNodeWithTag("capture_voice_stop").assertIsNotEnabled()
        compose.onNodeWithTag("capture_voice_cancel").assertIsEnabled()
        compose.onNodeWithTag("capture_voice_detail")
            .assertTextEquals(compose.activity.getString(R.string.capture_voice_finishing_detail))
    }

    @Test
    fun cancelWhilePreparingInterruptsSuspendedStart() {
        val gate = CompletableDeferred<Unit>()
        val engine = SyntheticEngine(gate)
        val repository = NoSaveRepository()
        showCapture(engine, repository, permission = true)
        click("capture_voice_start")
        compose.waitUntil(5_000) { engine.starts == 1 }
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Preparando voz local…")
        click("capture_voice_cancel")
        compose.waitUntil(5_000) { engine.startInterrupted && engine.cancels == 1 }
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Cancelado")
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Cancelado")
        compose.runOnIdle { assertEquals(0, repository.saves) }
    }

    @Test
    fun removingCaptureFromCompositionCancelsActiveEngineWithoutSaving() {
        val engine = SyntheticEngine()
        val repository = NoSaveRepository()
        val viewModel = CaptureViewModel(repository)
        val visible = mutableStateOf(true)
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                YERVISTheme {
                    if (visible.value) {
                        CaptureScreen(
                            callbacks = MemoryUiCallbacks({}, {}, {}),
                            viewModel = viewModel,
                            voiceEngineFactory = { _, _ -> engine },
                            voicePermissionCheck = { true },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        Espresso.closeSoftKeyboard()
        click("capture_voice_start")
        compose.waitUntil(5_000) { engine.starts == 1 }
        compose.runOnIdle { engine.state.value = SpeechToTextState.Listening() }
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(5_000) { engine.cancels == 1 }
        compose.onNodeWithTag("capture_voice_start").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, repository.saves) }
    }

    @Test
    fun realControlsStopAndCancelWithoutSavingAndProtectTypedDraft() {
        val engine = SyntheticEngine()
        val repository = NoSaveRepository()
        showCapture(engine, repository, permission = true)
        compose.onNodeWithTag("capture_text_field").performTextInput("Borrador escrito protegido")
        Espresso.closeSoftKeyboard()
        click("capture_voice_start")
        compose.waitUntil(5_000) { engine.starts == 1 }
        compose.runOnIdle { engine.state.value = SpeechToTextState.Listening() }
        click("capture_voice_cancel")
        compose.waitUntil(5_000) { engine.cancels == 1 }
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Cancelado")
        click("capture_voice_start")
        compose.waitUntil(5_000) { engine.starts == 2 }
        compose.runOnIdle { engine.state.value = SpeechToTextState.Listening() }
        click("capture_voice_stop")
        compose.waitUntil(5_000) { engine.finishes == 1 }
        compose.runOnIdle { engine.state.value = SpeechToTextState.Result(FinalTranscript("Transcripción sintética")) }
        compose.waitForIdle()
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Transcripción pendiente de revisión")
        compose.onNodeWithTag("capture_voice_transcript").assertTextEquals("Transcripción sintética")
        assertEquals("Borrador escrito protegido", compose.onNodeWithTag("capture_text_field")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { assertEquals(0, repository.saves) }
    }

    @Test
    fun deniedPermissionNeverStartsEngineOrSaves() {
        val engine = SyntheticEngine()
        val repository = NoSaveRepository()
        showCapture(engine, repository, permission = false)
        click("capture_voice_start")
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, engine.starts)
            assertEquals(0, repository.saves)
        }
        compose.onNodeWithTag("capture_voice_detail")
            .assertTextEquals(compose.activity.getString(R.string.capture_voice_permission_error))
    }

    @Test
    fun injectedPermissionStateDoesNotWritePersistentPermissionHistory() {
        val preferences = compose.activity.applicationContext
            .getSharedPreferences("yezvi_microphone_permission", 0)
        val before = preferences.all.toMap()
        showCapture(SyntheticEngine(), NoSaveRepository(), permission = true)
        compose.waitForIdle()
        assertEquals(before, preferences.all.toMap())
        compose.onNodeWithTag("capture_voice_permission_status")
            .assertTextEquals(compose.activity.getString(R.string.capture_voice_permission_granted))
    }

    @Test
    fun finalTranscriptIsEditedAndSavedOnlyAfterExplicitReviewWithSessionMetadata() {
        val engine = SyntheticEngine()
        val firstSaveGate = CompletableDeferred<Unit>()
        val repository = RecordingRepository(failFirstSave = true, firstSaveGate = firstSaveGate)
        showCapture(engine, repository, permission = true, idGenerator = { "stable-voice-memory-id" })
        completeRecognition(engine, "YEZVI usa Kotln.")
        compose.runOnIdle { assertEquals(0, repository.calls.size) }

        compose.onNodeWithTag("capture_text_field").performScrollTo().performTextClearance()
        compose.onNodeWithTag("capture_text_field").performTextInput("YEZVI usa Kotlin, corregido.")
        Espresso.closeSoftKeyboard()
        clickBottomAction("capture_review_action")
        compose.onNodeWithTag("capture_review_body").assertTextEquals("YEZVI usa Kotlin, corregido.")
        compose.onNodeWithTag("capture_save_button").performClick()
        compose.waitUntil(5_000) { repository.calls.size == 1 }
        compose.onNodeWithTag("capture_save_button").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, repository.calls.size) }
        compose.runOnIdle { firstSaveGate.complete(Unit) }
        compose.waitUntil(5_000) { repository.failures == 1 }
        compose.onNodeWithTag("capture_retry_button").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { repository.calls.size == 2 && repository.savedEntries.size == 1 }

        compose.runOnIdle {
            assertEquals(listOf("stable-voice-memory-id", "stable-voice-memory-id"), repository.calls.map { it.id })
            val persisted = repository.savedEntries.single()
            assertEquals("YEZVI usa Kotlin, corregido.", persisted.rawText)
            assertEquals(CaptureSourceType.VOICE, persisted.sourceType)
            assertTrue(persisted.capturedAtEpochMillis > 0L)
            assertTrue(persisted.timezoneId.isNotBlank())
            assertEquals(CaptureStatus.SAVED, persisted.status)
        }
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Guardado en este dispositivo.")
        compose.onNodeWithTag("capture_finish_button").performClick()
        compose.runOnIdle { assertEquals(2, repository.calls.size) }
    }

    @Test
    fun writtenDraftConflictRequiresChoiceAndReplaceThenSaveIsExplicit() {
        val engine = SyntheticEngine()
        val repository = RecordingRepository()
        showCapture(engine, repository, permission = true, idGenerator = { "replace-memory-id" })
        compose.onNodeWithTag("capture_text_field").performTextInput("Borrador anterior")
        Espresso.closeSoftKeyboard()
        completeRecognition(engine, "Transcripción nueva")

        compose.onNodeWithTag("capture_voice_existing_draft").assertTextEquals("Borrador anterior")
        compose.onNodeWithTag("capture_voice_replace").assertIsDisplayed().performClick()
        assertEditableText("Transcripción nueva")
        compose.runOnIdle { assertEquals(0, repository.calls.size) }
        compose.onNodeWithTag("capture_text_field").performScrollTo().performTextClearance()
        compose.onNodeWithTag("capture_text_field").performTextInput("Voz corregida")
        Espresso.closeSoftKeyboard()
        clickBottomAction("capture_review_action")
        compose.onNodeWithTag("capture_review_body").assertTextEquals("Voz corregida")
        compose.runOnIdle { assertEquals(0, repository.calls.size) }
        compose.onNodeWithTag("capture_save_button").performClick()
        compose.waitUntil(5_000) { repository.savedEntries.size == 1 }
        compose.runOnIdle {
            assertEquals("Voz corregida", repository.savedEntries.single().rawText)
            assertEquals(CaptureSourceType.VOICE, repository.savedEntries.single().sourceType)
            assertEquals("replace-memory-id", repository.calls.single().id)
        }
    }

    @Test
    fun appendingTextAndVoiceShowsOriginBlockAndCancelRestoresWrittenDraft() {
        val engine = SyntheticEngine()
        val repository = RecordingRepository()
        showCapture(engine, repository, permission = true)
        compose.onNodeWithTag("capture_text_field").performTextInput("Solo escrito")
        Espresso.closeSoftKeyboard()
        completeRecognition(engine, "Solo voz")

        compose.onNodeWithTag("capture_voice_append").performClick()
        assertEditableText("Solo escrito\nSolo voz")
        compose.onNodeWithTag("capture_voice_detail")
            .assertTextEquals(compose.activity.getString(R.string.capture_voice_mixed_origin_blocked))
        clickBottomAction("capture_review_action")
        compose.onNodeWithTag("capture_mixed_origin_blocked").assertIsDisplayed()
        compose.onNodeWithTag("capture_save_button").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, repository.calls.size) }
        compose.onNodeWithTag("capture_close").performClick()
        clickBottomAction("capture_clear_action")
        compose.waitForIdle()
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Cancelado")
        assertEditableText("Solo escrito")
        compose.runOnIdle { assertEquals(0, repository.calls.size) }
    }

    @Test
    fun keepWrittenDraftDiscardsVoiceWithoutWriting() {
        val engine = SyntheticEngine()
        val repository = RecordingRepository()
        showCapture(engine, repository, permission = true)
        compose.onNodeWithTag("capture_text_field").performTextInput("Conservar esta nota")
        Espresso.closeSoftKeyboard()
        completeRecognition(engine, "Texto dictado")
        compose.onNodeWithTag("capture_voice_keep_existing").performClick()
        assertEditableText("Conservar esta nota")
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Cancelado")
        compose.runOnIdle { assertEquals(0, repository.calls.size) }
    }

    @Test
    fun spokenConfirmationRequiresOptInAndCanBeFullyDisabled() {
        val engine = SyntheticEngine()
        val repository = RecordingRepository()
        val speech = object : LocalConfirmationSpeech {
            override val availability = MutableStateFlow(LocalSpeechAvailability.READY)
            var starts = 0
            var calls = 0
            var closes = 0
            override fun initialize() { starts++ }
            override fun announceSaved(): Boolean { calls++; return true }
            override fun stop() = Unit
            override fun close() { closes++ }
        }
        val viewModel = showCapture(engine, repository, permission = true, confirmationSpeech = speech)
        compose.onNodeWithTag("capture_spoken_confirmation").assertDoesNotExist()
        completeRecognition(engine, "Nota sintética de prueba TTS")
        compose.runOnIdle { assertEquals(0, speech.starts); assertEquals(0, speech.calls) }
        // Settings owns the opt-in; Capture only consumes the shared coordinator.
        compose.runOnIdle { viewModel.spokenConfirmation.setEnabled(true) }
        compose.waitUntil(5_000) { speech.starts == 1 }
        clickBottomAction("capture_review_action")
        compose.runOnIdle { assertEquals(0, speech.calls) }
        compose.onNodeWithTag("capture_save_button").performClick()
        compose.waitUntil(5_000) { repository.savedEntries.size == 1 && speech.calls == 1 }
        compose.onNodeWithTag("capture_finish_button").performClick()
        compose.runOnIdle { viewModel.spokenConfirmation.setEnabled(false) }
        compose.runOnIdle { assertEquals(1, speech.closes); assertEquals(1, speech.calls) }
    }

    @Test
    fun optedInTranscriptionAndDiscardStaySilentAndLeavingCaptureClosesTts() {
        val engine = SyntheticEngine()
        val speech = object : LocalConfirmationSpeech {
            override val availability = MutableStateFlow(LocalSpeechAvailability.READY)
            var calls = 0
            var closes = 0
            override fun initialize() = Unit
            override fun announceSaved(): Boolean { calls++; return true }
            override fun stop() = Unit
            override fun close() { closes++ }
        }
        val repository = NoSaveRepository()
        val viewModel = CaptureViewModel(repository, confirmationSpeechFactory = { speech })
        val visible = mutableStateOf(true)
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                YERVISTheme {
                    if (visible.value) CaptureScreen(
                        MemoryUiCallbacks({}, {}, {}), viewModel,
                        voiceEngineFactory = { _, _ -> engine }, voicePermissionCheck = { true },
                    )
                }
            }
        }
        compose.waitForIdle()
        Espresso.closeSoftKeyboard()
        compose.runOnIdle { viewModel.spokenConfirmation.setEnabled(true) }
        completeRecognition(engine, "Borrador sintético que se descarta")
        compose.runOnIdle { assertEquals(0, speech.calls); assertEquals(0, repository.saves) }
        click("capture_voice_cancel")
        compose.waitForIdle()
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Cancelado")
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(5_000) { speech.closes == 1 }
        compose.runOnIdle {
            assertEquals(0, speech.calls)
            assertEquals(0, repository.saves)
            viewModel.spokenConfirmation.close()
        }
    }

    private fun completeRecognition(engine: SyntheticEngine, transcript: String) {
        val nextStart = engine.starts + 1
        val nextFinish = engine.finishes + 1
        click("capture_voice_start")
        compose.waitUntil(5_000) { engine.starts >= nextStart }
        compose.runOnIdle { engine.state.value = SpeechToTextState.Listening() }
        click("capture_voice_stop")
        compose.waitUntil(5_000) { engine.finishes >= nextFinish }
        compose.runOnIdle { engine.state.value = SpeechToTextState.Result(FinalTranscript(transcript)) }
        compose.waitForIdle()
        compose.onNodeWithTag("capture_voice_status").assertTextEquals("Transcripción pendiente de revisión")
    }

    private fun showCapture(
        engine: SyntheticEngine,
        repository: MemoryRepository,
        permission: Boolean,
        idGenerator: () -> String = { "synthetic-memory-id" },
        confirmationSpeech: LocalConfirmationSpeech? = null,
    ): CaptureViewModel {
        val viewModel = CaptureViewModel(repository,
            confirmationSpeechFactory = confirmationSpeech?.let { speech -> { speech } },
            idGenerator = idGenerator)
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                YERVISTheme {
                    CaptureScreen(
                        callbacks = MemoryUiCallbacks({}, {}, {}),
                        viewModel = viewModel,
                        voiceEngineFactory = { _, _ -> engine },
                        voicePermissionCheck = { permission },
                    )
                }
            }
        }
        compose.waitForIdle()
        Espresso.closeSoftKeyboard()
        return viewModel
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun clickBottomAction(tag: String) = compose.onNodeWithTag(tag).performClick()

    private fun assertEditableText(expected: String) {
        val actual = compose.onNodeWithTag("capture_text_field")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(expected, actual)
    }

    private class SyntheticEngine(private val startGate: CompletableDeferred<Unit>? = null) : SpeechToTextEngine {
        override val capabilities = SpeechToTextCapabilities(supportsPartialResults = true, supportsPause = true)
        override val state = MutableStateFlow<SpeechToTextState>(SpeechToTextState.Idle)
        @Volatile var starts = 0
        @Volatile var finishes = 0
        @Volatile var cancels = 0
        @Volatile var pauses = 0
        @Volatile var resumes = 0
        @Volatile var startInterrupted = false
        override suspend fun start(request: SpeechToTextRequest): SpeechToTextStartResult {
            starts++
            state.value = SpeechToTextState.Preparing
            try {
                startGate?.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                startInterrupted = true
                throw cancelled
            }
            return SpeechToTextStartResult.Started
        }
        override suspend fun finish() { finishes++; state.value = SpeechToTextState.Transcribing() }
        override suspend fun pause(): Boolean {
            pauses++
            val partial = (state.value as? SpeechToTextState.Listening)?.partialTranscript
            state.value = SpeechToTextState.Paused(partial)
            return true
        }
        override suspend fun resume(): Boolean {
            resumes++
            val partial = (state.value as? SpeechToTextState.Paused)?.partialTranscript
            state.value = SpeechToTextState.Listening(partial)
            return true
        }
        override suspend fun cancel() { cancels++; state.value = SpeechToTextState.Cancelled }
    }

    private class NoSaveRepository : MemoryRepository {
        var saves = 0
        override suspend fun save(input: CaptureInput, id: String): MemoryEntry {
            saves++
            error("Recognition must not save automatically")
        }
        override suspend fun findById(id: String): MemoryEntry? = null
        override fun observeById(id: String) = flowOf<MemoryEntry?>(null)
        override fun observeAll() = flowOf(emptyList<MemoryEntry>())
        override suspend fun deleteById(id: String) = false
        override suspend fun deleteByIds(ids: Set<String>) = 0
    }

    private class RecordingRepository(
        private var failFirstSave: Boolean = false,
        private val firstSaveGate: CompletableDeferred<Unit>? = null,
    ) : MemoryRepository {
        val calls = mutableListOf<SaveCall>()
        val savedEntries = mutableListOf<MemoryEntry>()
        var failures = 0

        override suspend fun save(input: CaptureInput, id: String): MemoryEntry {
            calls += SaveCall(input, id)
            if (calls.size == 1) firstSaveGate?.await()
            if (failFirstSave) {
                failFirstSave = false
                failures++
                throw IllegalStateException("synthetic persistence failure")
            }
            return MemoryEntry(
                id = id,
                rawText = input.rawText,
                sourceType = input.sourceType,
                capturedAtEpochMillis = input.capturedAtEpochMillis,
                timezoneId = input.timezoneId,
                status = CaptureStatus.SAVED,
            ).also(savedEntries::add)
        }

        override suspend fun findById(id: String): MemoryEntry? = savedEntries.find { it.id == id }
        override fun observeById(id: String) = flowOf(savedEntries.find { it.id == id })
        override fun observeAll() = flowOf(savedEntries.toList())
        override suspend fun deleteById(id: String) = savedEntries.removeAll { it.id == id }
        override suspend fun deleteByIds(ids: Set<String>): Int {
            val before = savedEntries.size
            savedEntries.removeAll { it.id in ids }
            return before - savedEntries.size
        }
    }

    private data class SaveCall(val input: CaptureInput, val id: String)
}
