package com.yezvi.assistant.ui.screens

import android.database.sqlite.SQLiteDatabase
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.MainActivity
import com.yezvi.assistant.data.local.memory.YezviDatabase
import com.yezvi.assistant.data.local.memory.YezviDatabaseFactory
import com.yezvi.assistant.data.repository.RoomMemoryCorrectionRepository
import com.yezvi.assistant.data.repository.RoomMemoryRepository
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.presentation.memory.CorrectionSaveStatus
import com.yezvi.assistant.presentation.memory.EntryDetailUiState
import com.yezvi.assistant.presentation.memory.EntryDetailViewModel
import com.yezvi.assistant.ui.theme.YEZVITheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F2.11 — Editing/deletion UI exercises the existing append-only contract with real SQLite. */
@RunWith(AndroidJUnit4::class)
class EntryDetailEditingTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun correctionCancelHistoryPersistenceAndConfirmedDeleteUseRealRoom() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "yezvi-f211-edit-delete-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        var database: YezviDatabase? = YezviDatabaseFactory.create(context, databaseName)
        var memoryRepository = RoomMemoryRepository(requireNotNull(database).memoryEntryDao())
        var correctionRepository = RoomMemoryCorrectionRepository(
            requireNotNull(database).memoryCorrectionDao(),
        )
        val entryId = "f211-synthetic-edit-delete"
        val originalText = "Texto original sintético que nunca debe cambiar."
        val previousText = "Primera corrección sintética."
        val capturedAt = 1_791_234_567_890L
        val firstModifiedAt = 1_791_234_600_000L
        val secondModifiedAt = 1_791_234_700_000L
        val entry = runBlocking {
            memoryRepository.save(
                CaptureInput(
                    rawText = originalText,
                    sourceType = CaptureSourceType.TEXT,
                    capturedAtEpochMillis = capturedAt,
                    timezoneId = "America/Lima",
                ),
                id = entryId,
            ).also {
                correctionRepository.addCorrection(entryId, previousText, firstModifiedAt)
            }
        }
        database?.close()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { sqlite ->
            sqlite.execSQL(
                "CREATE TRIGGER fail_one_f211_correction BEFORE INSERT ON memory_entry_corrections " +
                    "WHEN NEW.correctedText = 'Corrección sintética que falla temporalmente.' " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic correction failure'); END",
            )
        }
        database = YezviDatabaseFactory.create(context, databaseName)
        memoryRepository = RoomMemoryRepository(requireNotNull(database).memoryEntryDao())
        correctionRepository = RoomMemoryCorrectionRepository(
            requireNotNull(database).memoryCorrectionDao(),
        )
        val viewModel = EntryDetailViewModel(
            memoryRepository = memoryRepository,
            correctionRepository = correctionRepository,
            entryId = entryId,
            nowEpochMillis = { secondModifiedAt },
        )
        val viewModelStore = ViewModelStore().apply { put("f211-detail", viewModel) }
        var backRequests = 0

        try {
            composeTestRule.activity.runOnUiThread {
                composeTestRule.activity.setContent {
                    val detailState by viewModel.uiState.collectAsStateWithLifecycle()
                    val deleteState by viewModel.deleteState.collectAsStateWithLifecycle()
                    val correctionState by viewModel.correctionState.collectAsStateWithLifecycle()
                    YEZVITheme(dynamicColor = false) {
                        EntryDetailScreen(
                            state = detailState,
                            deleteState = deleteState,
                            correctionState = correctionState,
                            onBack = { backRequests++ },
                            onRetry = viewModel::retry,
                            onDelete = viewModel::delete,
                            onCancelDelete = viewModel::dismissDelete,
                            onRetryCorrections = viewModel::retryCorrections,
                            onBeginCorrection = viewModel::beginCorrection,
                            onCorrectionDraftChange = viewModel::updateCorrectionDraft,
                            onSaveCorrection = viewModel::saveCorrection,
                            onCancelCorrection = viewModel::cancelCorrection,
                        )
                    }
                }
            }
            composeTestRule.waitUntil(5_000) {
                viewModel.uiState.value is EntryDetailUiState.Loaded &&
                    !viewModel.correctionState.value.isLoading
            }

            composeTestRule.onNodeWithTag("entry_current_text").assertTextEquals(previousText)
            composeTestRule.onNodeWithText(originalText).performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("entry_correction_open")
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            composeTestRule.onNodeWithTag("entry_correction_editor").assertIsDisplayed()
            composeTestRule.onNodeWithTag("entry_correction_editor")
                .performTextClearance()
            composeTestRule.onNodeWithTag("entry_correction_editor")
                .performTextInput("Borrador temporal que se cancela.")
            Espresso.closeSoftKeyboard()
            composeTestRule.onNodeWithText("Cancelar").performClick()
            composeTestRule.waitForIdle()
            assertEquals(1, runBlocking { correctionRepository.getCorrectionHistory(entryId).size })
            assertEquals(previousText, runBlocking { correctionRepository.getCurrentVersion(entryId)?.text })
            assertEquals(originalText, runBlocking { memoryRepository.findById(entryId)?.rawText })

            composeTestRule.onNodeWithTag("entry_correction_open").performClick()
            composeTestRule.onNodeWithText("Atrás").performClick()
            assertFalse(viewModel.correctionState.value.isEditing)
            assertEquals(0, backRequests)

            composeTestRule.onNodeWithTag("entry_correction_open").performScrollTo().performClick()
            composeTestRule.onNodeWithTag("entry_correction_editor")
                .performTextClearance()
            composeTestRule.onNodeWithTag("entry_correction_editor")
                .performTextInput("Corrección sintética que falla temporalmente.")
            Espresso.closeSoftKeyboard()
            composeTestRule.onNodeWithTag("entry_correction_save").performScrollTo().performClick()
            composeTestRule.waitUntil(5_000) {
                viewModel.correctionState.value.saveStatus == CorrectionSaveStatus.FAILED
            }
            composeTestRule.onNodeWithTag("entry_correction_error").assertIsDisplayed()
            val retainedDraft = composeTestRule.onNodeWithTag("entry_correction_editor")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text
            assertEquals("Corrección sintética que falla temporalmente.", retainedDraft)
            assertEquals(originalText, runBlocking { memoryRepository.findById(entryId)?.rawText })

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite -> sqlite.execSQL("DROP TRIGGER fail_one_f211_correction") }
            composeTestRule.onNodeWithTag("entry_correction_save").performScrollTo().performClick()
            Espresso.closeSoftKeyboard()
            composeTestRule.waitUntil(5_000) {
                viewModel.correctionState.value.saveStatus == CorrectionSaveStatus.SAVED
            }

            val correctionText = "Corrección sintética que falla temporalmente."

            val savedHistory = runBlocking { correctionRepository.getCorrectionHistory(entryId) }
            assertEquals(listOf(previousText, correctionText), savedHistory.map { it.correctedText })
            assertEquals(listOf(firstModifiedAt, secondModifiedAt), savedHistory.map { it.modifiedAtEpochMillis })
            assertEquals(correctionText, viewModel.correctionState.value.currentVersion?.text)
            assertEquals(originalText, runBlocking { memoryRepository.findById(entryId)?.rawText })
            composeTestRule.onNodeWithTag("entry_current_text")
                .assertTextEquals(correctionText)
            composeTestRule.onNodeWithText(originalText).performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("entry_correction_history_1")
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule.onNodeWithTag("entry_correction_history_2")
                .performScrollTo()
                .assertIsDisplayed()

            composeTestRule.onNodeWithTag("entry_delete_open_action")
                .performScrollTo()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            composeTestRule.onNodeWithText("¿Eliminar este recuerdo?").assertIsDisplayed()
            composeTestRule.onNodeWithTag("entry_delete_cancel_action").performClick()
            assertEquals(entry, runBlocking { memoryRepository.findById(entryId) })
            assertEquals(2, runBlocking { correctionRepository.getCorrectionHistory(entryId).size })

            composeTestRule.onNodeWithTag("entry_delete_open_action").performScrollTo().performClick()
            composeTestRule.onNodeWithTag("entry_delete_confirm_action").performClick()
            composeTestRule.waitUntil(5_000) {
                viewModel.deleteState.value == com.yezvi.assistant.presentation.memory.EntryDeleteUiState.Deleted
            }
            composeTestRule.onNodeWithText("Recuerdo eliminado").assertIsDisplayed()
            assertNull(runBlocking { memoryRepository.findById(entryId) })
            assertTrue(runBlocking { correctionRepository.getCorrectionHistory(entryId).isEmpty() })
            composeTestRule.onNodeWithTag("entry_delete_confirm_action").performClick()
            assertEquals(1, backRequests)

            viewModelStore.clear()
            database?.close()
            database = YezviDatabaseFactory.create(context, databaseName)
            val reopenedMemory = RoomMemoryRepository(requireNotNull(database).memoryEntryDao())
            val reopenedCorrections = RoomMemoryCorrectionRepository(
                requireNotNull(database).memoryCorrectionDao(),
            )
            assertNull(runBlocking { reopenedMemory.findById(entryId) })
            assertTrue(runBlocking { reopenedCorrections.getCorrectionHistory(entryId).isEmpty() })
        } finally {
            viewModelStore.clear()
            database?.close()
            context.deleteDatabase(databaseName)
        }
    }
}
