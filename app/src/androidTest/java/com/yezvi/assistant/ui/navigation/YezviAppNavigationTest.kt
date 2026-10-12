package com.yezvi.assistant.ui.navigation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import com.yezvi.assistant.MainActivity
import org.junit.Rule
import org.junit.Test

/** F1.12 — Navegación principal y flujo Memoria → Detalle. */
class YezviAppNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun waitForHeading(text: String) {
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodes(hasText(text) and isHeading())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun mainNavigationTabsSwitchScreens() {
        composeTestRule.apply {
            waitForIdle()
            onNode(hasText("Hoy") and isHeading()).assertIsDisplayed()

            onNodeWithTag("bottom_nav_memory").performClick()
            waitForHeading("Memoria")
            waitForMemoryLoaded()

            onNodeWithTag("bottom_nav_capture").performClick()
            waitForHeading("Capturar")
            onNodeWithTag("capture_text_field").assertExists()
            Espresso.closeSoftKeyboard()

            onNodeWithTag("bottom_nav_settings").performClick()
            waitForHeading("Ajustes")

            onNodeWithTag("bottom_nav_today").performClick()
            waitForHeading("Hoy")
        }
    }

    @Test
    fun memoryTimelineLoadsPersistedOrEmptyStateWithoutDemoData() {
        composeTestRule.apply {
            waitForIdle()
            onNodeWithTag("bottom_nav_memory").performClick()
            waitForHeading("Memoria")
            waitForMemoryLoaded()
            onNodeWithText("Datos de demostración · aún no se guardan entradas reales")
                .assertDoesNotExist()
        }
    }

    private fun waitForMemoryLoaded() {
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            listOf("memory_empty", "memory_content_loaded", "memory_error").any { tag ->
                composeTestRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}
