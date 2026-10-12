package com.yezvi.assistant.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.data.demo.DemoMemoryFixtures
import com.yezvi.assistant.data.demo.DemoMemoryRepository
import com.yezvi.assistant.presentation.memory.EntryDetailUiState
import com.yezvi.assistant.presentation.memory.MemoryUiState
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.ui.theme.YEZVITheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** F1.12 — Estados de detalle, semántica accesible, fuente grande y objetivos táctiles. */
class EntryDetailAndAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun validEntryDisplaysLoadedState() {
        composeTestRule.setContent {
            YEZVITheme(dynamicColor = false) {
                EntryDetailScreen(
                    state = EntryDetailUiState.Loaded(
                        DemoMemoryRepository.getEntry("demo-2026-10-11-001")!!,
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Ejemplo: revisar el resumen de mañana.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sin guardar").assertIsDisplayed()
        composeTestRule.onNodeWithText("America/Lima").assertIsDisplayed()
    }

    @Test
    fun invalidEntryDisplaysNotFoundState() {
        composeTestRule.setContent {
            YEZVITheme(dynamicColor = false) {
                EntryDetailScreen(
                    state = EntryDetailUiState.NotFound,
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Entrada no encontrada").assertIsDisplayed()
        composeTestRule.onNodeWithText("Volver a Memoria").assertIsDisplayed()
    }

    @Test
    fun memoryScreenShowsLoadingState() {
        composeTestRule.setContent {
            YEZVITheme(dynamicColor = false) {
                MemoryScreen(
                    state = MemoryUiState.Loading,
                    callbacks = noOpCallbacks(),
                    onRetry = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("memory_loading").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cargando recuerdos guardados…").assertIsDisplayed()
    }

    @Test
    fun memoryScreenShowsRepositoryErrorRetry() {
        var retries = 0
        composeTestRule.setContent {
            YEZVITheme(dynamicColor = false) {
                MemoryScreen(
                    state = MemoryUiState.Error,
                    callbacks = noOpCallbacks(),
                    onRetry = { retries++ },
                )
            }
        }
        composeTestRule.onNodeWithTag("memory_error").assertIsDisplayed()
        composeTestRule.onNodeWithText("No se pudo cargar Memoria").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reintentar").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun memoryScreenHasAccessibleHeadingsAndLargeTouchTargets() {
        composeTestRule.setContent {
            YEZVITheme(dynamicColor = false) {
                MemoryScreen(
                    state = MemoryUiState.Content(DemoMemoryFixtures.entries),
                    callbacks = noOpCallbacks(),
                    onRetry = {},
                )
            }
        }
        composeTestRule.onNode(hasText("Memoria") and isHeading()).assertIsDisplayed()
        composeTestRule.onNode(hasText("Domingo 11 de octubre") and isHeading()).assertIsDisplayed()
        val memoryTab = composeTestRule.onNodeWithTag("bottom_nav_memory")
        memoryTab.assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        val buttonHeightPx = memoryTab.fetchSemanticsNode().size.height
        val maxButtonHeightPx = with(composeTestRule.density) { 80.dp.roundToPx() }
        assertTrue(
            "Navigation selection background must stay within the bottom bar",
            buttonHeightPx <= maxButtonHeightPx,
        )
    }

    @Test
    fun memoryScreenWithLargeFontDoesNotClipContent() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                YEZVITheme(dynamicColor = false) {
                    MemoryScreen(
                        state = MemoryUiState.Content(DemoMemoryFixtures.entries),
                        callbacks = noOpCallbacks(),
                        onRetry = {},
                    )
                }
            }
        }
        composeTestRule.onNode(hasText("Memoria") and isHeading()).assertIsDisplayed()
        composeTestRule.onNodeWithText("Domingo 11 de octubre").assertIsDisplayed()
    }

    private fun noOpCallbacks() = MemoryUiCallbacks(
        onCaptureRequested = {},
        onNavigateTo = {},
        onMemoryEntrySelected = {},
    )
}
