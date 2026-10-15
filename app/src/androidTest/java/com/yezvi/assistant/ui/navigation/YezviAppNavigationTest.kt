package com.yezvi.assistant.ui.navigation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import com.yezvi.assistant.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

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
            onNode(hasText("Inicio") and isHeading()).assertIsDisplayed()

            onNodeWithTag("bottom_nav_memory").performClick()
            waitForHeading("Recuerdos")
            waitForMemoryLoaded()

            onNodeWithTag("bottom_nav_capture").performClick()
            waitForHeading("Captura")
            onNodeWithTag("capture_text_field").assertIsNotFocused()
            Espresso.closeSoftKeyboard()

            onNodeWithTag("bottom_nav_settings").performClick()
            waitForHeading("Ajustes")
            if (proposalReviewDemoEnabled()) {
                onNodeWithTag("proposal_review_open").performScrollTo().performClick()
                waitForHeading("Revisar propuestas")
                waitUntil(timeoutMillis = 10000) {
                    onAllNodes(hasTestTag("proposal_review_demo_notice")).fetchSemanticsNodes().isNotEmpty() ||
                        onAllNodes(hasTestTag("proposal_review_error")).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("proposal_review_demo_notice").assertIsDisplayed()
                Espresso.pressBack()
                waitForHeading("Ajustes")
            }

            onNodeWithTag("bottom_nav_today").performClick()
            waitForHeading("Inicio")

            onNodeWithTag("today_capture_fab").performClick()
            waitForHeading("Captura")
            onNodeWithTag("capture_text_field").assertIsNotFocused()
        }
    }

    @Test
    fun memoryTimelineLoadsPersistedOrEmptyStateWithoutDemoData() {
        composeTestRule.apply {
            waitForIdle()
            onNodeWithTag("bottom_nav_memory").performClick()
            waitForHeading("Recuerdos")
            waitForMemoryLoaded()
            onNodeWithText("Datos de demostración · aún no se guardan entradas reales")
                .assertDoesNotExist()
        }
    }

    @Test
    fun proposalReviewShowsEvidenceAndTemporalAmbiguityWithoutActivatingActions() {
        composeTestRule.apply {
            onNodeWithTag("bottom_nav_settings").performClick()
            waitForHeading("Ajustes")
            onNodeWithTag("proposal_review_open").performScrollTo().performClick()
            waitForHeading("Revisar propuestas")
            waitUntil(timeoutMillis = 15000) {
                onAllNodes(hasTestTag("proposal_review_demo_notice")).fetchSemanticsNodes().isNotEmpty() ||
                    onAllNodes(hasTestTag("proposal_review_error")).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("proposal_review_demo_notice").assertIsDisplayed()
            onNodeWithTag("proposal_review_no_alarms").assertIsDisplayed()

            onNodeWithTag("proposal_review_list").performScrollToNode(
                hasTestTag("proposal_review_temporal_el viernes por la tarde"),
            )
            onNodeWithTag("proposal_review_temporal_el viernes por la tarde").assertIsDisplayed()

            onNodeWithTag("proposal_review_list").performScrollToNode(
                hasTestTag("proposal_review_observed_hypothesis"),
            )
            onNodeWithTag("proposal_review_observed_hypothesis").assertIsDisplayed()
            onNodeWithTag("proposal_review_tracking_enable").assertDoesNotExist()
        }
    }

    @Test
    fun proposalCorrectionAndConfirmationStayInTheLocalReviewSession() {
        composeTestRule.apply {
            onNodeWithTag("bottom_nav_settings").performClick()
            waitForHeading("Ajustes")
            onNodeWithTag("proposal_review_open").performScrollTo().performClick()
            waitForHeading("Revisar propuestas")
            waitUntil(timeoutMillis = 15000) {
                onAllNodes(hasTestTag("proposal_review_demo_notice"))
                    .fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithTag("proposal_review_list").performScrollToNode(
                hasTestTag("proposal_review_edit_extraction-01-multiple/ana_/proposals/0/data/title"),
            )
            onNodeWithTag("proposal_review_edit_extraction-01-multiple/ana_/proposals/0/data/title").performClick()
            onNodeWithTag("proposal_review_editor_extraction-01-multiple/ana_/proposals/0/data/title")
                .performTextReplacement("Ana revisada en la demostración")
            onNodeWithTag("proposal_review_save_edit_extraction-01-multiple/ana_/proposals/0/data/title").performClick()
            onNodeWithTag("proposal_review_edit_not_persisted_extraction-01-multiple/ana_/proposals/0/data/title")
                .assertIsDisplayed()

            onNodeWithTag("proposal_review_list").performScrollToNode(
                hasTestTag("proposal_review_confirm_extraction-01-multiple/ana"),
            )
            onNodeWithTag("proposal_review_confirm_extraction-01-multiple/ana").performClick()
            onNodeWithTag("proposal_review_confirm_decision").assertIsDisplayed()
            onNodeWithTag("proposal_review_confirm_decision").performClick()
            onNodeWithTag("proposal_review_list").performScrollToNode(
                hasTestTag("proposal_review_status_extraction-01-multiple/ana"),
            )
            onNodeWithText("Confirmada en esta demostración · no guardada").assertIsDisplayed()

            onNodeWithTag("proposal_review_list").performScrollToNode(
                hasTestTag("proposal_review_reject_extraction-01-multiple/atlas"),
            )
            onNodeWithTag("proposal_review_reject_extraction-01-multiple/atlas").performClick()
            onNodeWithTag("proposal_review_reject_decision").assertIsDisplayed()
            onNodeWithTag("proposal_review_reject_decision").performClick()
            onNodeWithTag("proposal_review_list").performScrollToNode(
                hasTestTag("proposal_review_status_extraction-01-multiple/atlas"),
            )
            onNodeWithText("Rechazada en esta demostración · no guardada").assertIsDisplayed()
        }
    }

    @Test
    fun proposalStateIsAccessibleAndDecisionTargetIsAtLeast48Dp() {
        composeTestRule.apply {
            onNodeWithTag("bottom_nav_settings").performClick()
            waitForHeading("Ajustes")
            onNodeWithTag("proposal_review_open").performScrollTo().performClick()
            waitForHeading("Revisar propuestas")
            waitUntil(timeoutMillis = 15000) {
                onAllNodes(hasTestTag("proposal_review_demo_notice")).fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithTag("proposal_review_list").performScrollToNode(hasTestTag("proposal_review_category_EVENT"))
            val category = onNodeWithTag("proposal_review_category_EVENT").fetchSemanticsNode()
            assertEquals(
                "Pendiente de revisión",
                category.config[SemanticsProperties.StateDescription],
            )

            onNodeWithTag("proposal_review_list").performScrollToNode(
                hasTestTag("proposal_review_confirm_extraction-01-multiple/ana"),
            )
            val confirmation = onNodeWithTag(
                "proposal_review_confirm_extraction-01-multiple/ana",
            ).fetchSemanticsNode()
            assertTrue(
                "El objetivo táctil de confirmar debe medir al menos 48 dp",
                confirmation.boundsInRoot.height >= 48f * composeTestRule.density.density,
            )
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
