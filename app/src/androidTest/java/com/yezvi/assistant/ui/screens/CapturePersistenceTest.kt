package com.yezvi.assistant.ui.screens

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.MainActivity
import com.yezvi.assistant.data.local.memory.YezviDatabase
import com.yezvi.assistant.data.local.memory.YezviDatabaseFactory
import com.yezvi.assistant.data.repository.RoomMemoryRepository
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.presentation.capture.CaptureSaveState
import com.yezvi.assistant.presentation.capture.CaptureViewModel
import com.yezvi.assistant.ui.navigation.YezviApp
import com.yezvi.assistant.ui.theme.YERVISTheme
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F2.07 — Capture review writes through the ViewModel to a real, reopenable Room database. */
@RunWith(AndroidJUnit4::class)
class CapturePersistenceTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun reviewSaveReportsSqliteFailureKeepsDraftAndRetryPersistsSameCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "yezvi-f207-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        var database: YezviDatabase? = YezviDatabaseFactory.create(context, databaseName)
        val repository = RoomMemoryRepository(requireNotNull(database).memoryEntryDao())
        val stableId = "f207-capture-stable-id"
        val conflictingInput = CaptureInput(
            rawText = "Semilla sintética para provocar colisión de ID.",
            sourceType = CaptureSourceType.TEXT,
            capturedAtEpochMillis = 1_791_234_567_890L,
            timezoneId = "America/Lima",
        )
        val idRequestCount = AtomicInteger()
        val viewModel = CaptureViewModel(repository) {
            idRequestCount.incrementAndGet()
            stableId
        }

        try {
            runBlocking { repository.save(conflictingInput, stableId) }
            composeTestRule.activity.runOnUiThread {
                composeTestRule.activity.setContent {
                    YERVISTheme {
                        YezviApp(
                            captureViewModelOverride = viewModel,
                            memoryRepositoryOverride = repository,
                        )
                    }
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("bottom_nav_capture").performClick()
            composeTestRule.onNodeWithTag("capture_text_field")
                .performTextInput("  Conservar el texto original de prueba.\nSegunda línea.  ")
            Espresso.closeSoftKeyboard()
            val captureStartedAt = System.currentTimeMillis()
            composeTestRule.onNode(hasText("Revisar") and hasClickAction()).performClick()
            composeTestRule.onNodeWithTag("capture_review_body")
                .assertTextEquals("  Conservar el texto original de prueba.\nSegunda línea.  ")
            composeTestRule.onNodeWithTag("capture_save_button").performClick()
            composeTestRule.onNodeWithText(
                "No se pudo guardar. El borrador se conserva; puedes reintentar.",
            ).assertIsDisplayed()
            composeTestRule.waitUntil(5_000) {
                viewModel.uiState.value is CaptureSaveState.Error
            }
            assertEquals(
                conflictingInput,
                runBlocking { repository.findById(stableId) }?.let {
                    CaptureInput(it.rawText, it.sourceType, it.capturedAtEpochMillis, it.timezoneId)
                },
            )

            composeTestRule.onNode(hasText("Cerrar") and hasClickAction()).performClick()
            assertDraftEquals("  Conservar el texto original de prueba.\nSegunda línea.  ")
            runBlocking { repository.deleteById(stableId) }

            composeTestRule.onNode(hasText("Revisar") and hasClickAction()).performClick()
            composeTestRule.onNodeWithTag("capture_retry_button").performClick()
            composeTestRule.onNodeWithText("Guardado en este dispositivo.").assertIsDisplayed()
            composeTestRule.waitUntil(5_000) {
                viewModel.uiState.value is CaptureSaveState.Saved
            }
            assertEquals(1, idRequestCount.get())
            val savedState = viewModel.uiState.value as CaptureSaveState.Saved
            assertEquals(stableId, savedState.entry.id)
            assertEquals(CaptureStatus.SAVED, savedState.entry.status)
            assertEquals(
                "  Conservar el texto original de prueba.\nSegunda línea.  ",
                savedState.entry.rawText,
            )
            assertEquals(CaptureSourceType.TEXT, savedState.entry.sourceType)
            assertEquals(TimeZone.getDefault().id, savedState.entry.timezoneId)
            assertTrue(savedState.entry.capturedAtEpochMillis >= captureStartedAt)
            assertTrue(savedState.entry.capturedAtEpochMillis <= System.currentTimeMillis())
            assertDraftEquals("")

            composeTestRule.onNodeWithTag("capture_finish_button").performClick()
            composeTestRule.onNodeWithTag("bottom_nav_memory").performClick()
            composeTestRule.onNodeWithText(
                "  Conservar el texto original de prueba.\nSegunda línea.  ",
            ).assertIsDisplayed()
            composeTestRule.onNodeWithText("Ejemplo: revisar el resumen de mañana.")
                .assertDoesNotExist()

            requireNotNull(database).close()
            database = null
            val reopened = YezviDatabaseFactory.create(context, databaseName)
            try {
                val persisted = runBlocking {
                    RoomMemoryRepository(reopened.memoryEntryDao()).findById(stableId)
                }
                assertEquals(savedState.entry, persisted)
            } finally {
                reopened.close()
            }
        } finally {
            database?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun assertDraftEquals(expected: String) {
        val editableText = composeTestRule.onNodeWithTag("capture_text_field")
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text
        assertEquals(expected, editableText)
    }
}
