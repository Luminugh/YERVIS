package com.yezvi.assistant.ui.screens

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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import com.yezvi.assistant.MainActivity
import org.junit.Rule
import org.junit.Test

/** F1.12 — Captura textual: revisión del borrador y diálogo de descarte. */
class CaptureDraftFlowTest {

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

    private fun waitForTag(testTag: String) {
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodes(hasTestTag(testTag))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun typingDraftReviewAndDiscardFlow() {
        composeTestRule.apply {
            waitForIdle()
            waitForHeading("Hoy")
            // Navegamos desde la barra inferior porque el clic en el FAB no es estable
            // en este emulador API 37 con Espresso 3.7.0 (NO_INPUT_CHANNEL al inicio).
            onNodeWithTag("bottom_nav_capture").performClick()
            waitForTag("capture_text_field")

            onNodeWithTag("capture_text_field")
                .performTextInput("  Revisar el informe mañana  ")
            Espresso.closeSoftKeyboard()

            onNode(hasText("Revisar") and hasClickAction()).performClick()
            onNodeWithText("Revisar borrador").assertIsDisplayed()
            onNodeWithTag("capture_review_body")
                .assertIsDisplayed()
                .assertTextEquals("  Revisar el informe mañana  ")

            onNode(hasText("Cerrar") and hasClickAction()).performClick()
            Espresso.pressBack()

            onNodeWithText("¿Descartar borrador?").assertIsDisplayed()
            onNode(hasText("Descartar") and hasClickAction()).performClick()
            onNode(hasText("Hoy") and isHeading()).assertIsDisplayed()
        }
    }
}
