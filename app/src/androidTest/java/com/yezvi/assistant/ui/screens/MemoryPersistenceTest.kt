package com.yezvi.assistant.ui.screens

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.MainActivity
import com.yezvi.assistant.data.local.memory.YezviDatabase
import com.yezvi.assistant.data.local.memory.YezviDatabaseFactory
import com.yezvi.assistant.data.repository.RoomMemoryRepository
import com.yezvi.assistant.presentation.capture.CaptureViewModel
import com.yezvi.assistant.ui.navigation.YezviApp
import com.yezvi.assistant.ui.theme.YEZVITheme
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F2.08 — captures appear in the persistent timeline and detail after reopening Room. */
@RunWith(AndroidJUnit4::class)
class MemoryPersistenceTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun emptyTimelineUpdatesFromCaptureAndSurvivesActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "yezvi-f208-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        var database: YezviDatabase? = YezviDatabaseFactory.create(context, databaseName)
        var repository = RoomMemoryRepository(requireNotNull(database).memoryEntryDao())
        var idSequence = AtomicInteger()
        var captureViewModel = newCaptureViewModel(repository, idSequence)
        val firstId = "f208-note-1"
        val secondId = "f208-note-2"
        val firstText = "Nota sintética F2.08: preparar la agenda."
        val secondText = "Nota sintética F2.08: llamar al taller."

        try {
            setTestContent(repository, captureViewModel)
            composeTestRule.waitForIdle()
            composeTestRule.onNode(hasText("Hoy") and isHeading()).assertIsDisplayed()

            composeTestRule.onNodeWithTag("bottom_nav_memory").performClick()
            waitForTag("memory_empty")
            composeTestRule.onNodeWithText("Sin entradas").assertIsDisplayed()
            composeTestRule.onNodeWithText("Aún no se guardan entradas reales")
                .assertDoesNotExist()

            saveFromCapture(firstText)
            composeTestRule.onNodeWithTag("bottom_nav_memory").performClick()
            waitForTag("timeline_entry_$firstId")
            composeTestRule.onNodeWithText(firstText).assertIsDisplayed()

            saveFromCapture(secondText)
            composeTestRule.onNodeWithTag("bottom_nav_memory").performClick()
            waitForTag("timeline_entry_$secondId")
            composeTestRule.onNodeWithText(firstText).assertIsDisplayed()
            composeTestRule.onNodeWithText(secondText).assertIsDisplayed()
            composeTestRule.onNodeWithText("Aún no se guardan entradas reales")
                .assertDoesNotExist()

            val timelineEntries = runBlocking { repository.observeAll().first() }
            assertEquals(listOf(secondId, firstId), timelineEntries.map { it.id })
            val secondCardTop = composeTestRule.onNodeWithTag("timeline_entry_$secondId")
                .fetchSemanticsNode().boundsInRoot.top
            val firstCardTop = composeTestRule.onNodeWithTag("timeline_entry_$firstId")
                .fetchSemanticsNode().boundsInRoot.top
            assertTrue("Newest entry should appear first", secondCardTop < firstCardTop)

            composeTestRule.onNodeWithTag("timeline_entry_$secondId").performClick()
            waitForHeading("Detalle")
            composeTestRule.onNodeWithTag("entry_current_text").assertTextEquals(secondText)
            val secondEntry = requireNotNull(timelineEntries.firstOrNull { it.id == secondId })
            composeTestRule.onNodeWithTag("entry_detail_source_value")
                .assertTextEquals("Texto")
            composeTestRule.onNodeWithText(
                formatEntryDate(secondEntry.capturedAtEpochMillis, secondEntry.timezoneId),
            ).assertIsDisplayed()
            assertEquals(
                2,
                composeTestRule.onAllNodesWithText(
                    formatEntryTime(secondEntry.capturedAtEpochMillis, secondEntry.timezoneId),
                ).fetchSemanticsNodes().size,
            )
            composeTestRule.onNodeWithText(secondEntry.timezoneId).assertIsDisplayed()
            composeTestRule.onNodeWithText("Guardado en el dispositivo").assertIsDisplayed()
            composeTestRule.onNodeWithText(
                "Datos de demostración · esta entrada no está guardada en el dispositivo.",
            ).assertDoesNotExist()

            composeTestRule.onNode(hasText("Atrás") and hasClickAction()).performClick()
            composeTestRule.onNodeWithTag("bottom_nav_today").performClick()
            composeTestRule.onNode(hasText("Hoy") and isHeading()).assertIsDisplayed()

            requireNotNull(database).close()
            database = null
            composeTestRule.activityRule.scenario.recreate()
            val reopenedDatabase = YezviDatabaseFactory.create(context, databaseName)
            database = reopenedDatabase
            repository = RoomMemoryRepository(reopenedDatabase.memoryEntryDao())
            idSequence = AtomicInteger(2)
            captureViewModel = newCaptureViewModel(repository, idSequence)
            setTestContent(repository, captureViewModel)
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("bottom_nav_memory").performClick()
            waitForTag("timeline_entry_$firstId")
            composeTestRule.onNodeWithText(firstText).assertIsDisplayed()
            composeTestRule.onNodeWithText(secondText).assertIsDisplayed()
            composeTestRule.onNodeWithTag("timeline_entry_$secondId").performClick()
            waitForHeading("Detalle")
            composeTestRule.onNodeWithTag("entry_current_text").assertTextEquals(secondText)
            assertEquals(TimeZone.getDefault().id, secondEntry.timezoneId)
        } finally {
            database?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun setTestContent(
        repository: RoomMemoryRepository,
        captureViewModel: CaptureViewModel,
    ) {
        composeTestRule.activity.runOnUiThread {
            composeTestRule.activity.setContent {
                YEZVITheme {
                    YezviApp(
                        captureViewModelOverride = captureViewModel,
                        memoryRepositoryOverride = repository,
                    )
                }
            }
        }
    }

    private fun newCaptureViewModel(
        repository: RoomMemoryRepository,
        idSequence: AtomicInteger,
    ) = CaptureViewModel(repository) {
        "f208-note-${idSequence.incrementAndGet()}"
    }

    private fun saveFromCapture(rawText: String) {
        composeTestRule.onNodeWithTag("bottom_nav_capture").performClick()
        composeTestRule.onNodeWithTag("capture_text_field").performTextInput(rawText)
        Espresso.closeSoftKeyboard()
        composeTestRule.onNode(hasText("Revisar") and hasClickAction()).performClick()
        composeTestRule.onNodeWithTag("capture_save_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodes(hasTestTag("capture_finish_button"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag("capture_finish_button").performClick()
    }

    private fun waitForTag(tag: String) {
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForHeading(text: String) {
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodes(hasText(text) and isHeading())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
}
