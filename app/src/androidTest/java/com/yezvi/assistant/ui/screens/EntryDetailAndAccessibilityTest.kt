package com.yezvi.assistant.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.data.demo.DemoMemoryFixtures
import com.yezvi.assistant.data.demo.DemoMemoryRepository
import com.yezvi.assistant.presentation.memory.EntryDetailUiState
import com.yezvi.assistant.presentation.memory.EntryDeleteUiState
import com.yezvi.assistant.presentation.memory.MemoryUiState
import com.yezvi.assistant.presentation.memory.MemorySelectionUiState
import com.yezvi.assistant.presentation.memory.MemoryDeleteSelectionStatus
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.ui.theme.YERVISTheme
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
    fun cancellingDeleteConfirmationDoesNotRequestDeletion() {
        var deleteRequests = 0
        var cancelRequests = 0
        composeTestRule.setContent {
            YERVISTheme(dynamicColor = false) {
                EntryDetailScreen(
                    state = EntryDetailUiState.Loaded(
                        DemoMemoryRepository.getEntry("demo-2026-10-11-001")!!,
                    ),
                    onBack = {},
                    onRetry = {},
                    deleteState = EntryDeleteUiState.Idle,
                    onDelete = { deleteRequests++ },
                    onCancelDelete = { cancelRequests++ },
                )
            }
        }

        composeTestRule.onNodeWithTag("entry_detail_list").performScrollToNode(hasTestTag("entry_delete_open_action"))
        composeTestRule.onNodeWithText("Eliminar recuerdo").performClick()
        composeTestRule.onNodeWithText("¿Eliminar este recuerdo?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancelar").performClick()

        assertEquals(0, deleteRequests)
        assertEquals(1, cancelRequests)
        composeTestRule.onNodeWithTag("entry_detail_list").performScrollToNode(hasTestTag("entry_current_text"))
        composeTestRule.onNodeWithTag("entry_current_text").assertIsDisplayed()
    }

    @Test
    fun deleteSuccessAppearsOnlyAfterDeleteStateReportsSuccess() {
        var deletionState: EntryDeleteUiState by mutableStateOf(EntryDeleteUiState.Idle)
        var deleteRequests = 0
        var backRequests = 0
        composeTestRule.setContent {
            YERVISTheme(dynamicColor = false) {
                EntryDetailScreen(
                    state = EntryDetailUiState.Loaded(
                        DemoMemoryRepository.getEntry("demo-2026-10-11-001")!!,
                    ),
                    onBack = { backRequests++ },
                    onRetry = {},
                    deleteState = deletionState,
                    onDelete = {
                        deleteRequests++
                        deletionState = EntryDeleteUiState.Deleted
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("entry_detail_list").performScrollToNode(hasTestTag("entry_delete_open_action"))
        composeTestRule.onNodeWithText("Eliminar recuerdo").performClick()
        composeTestRule.onNodeWithText("¿Eliminar este recuerdo?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Eliminar", useUnmergedTree = true).performClick()

        assertEquals(1, deleteRequests)
        composeTestRule.onNodeWithText("Recuerdo eliminado").assertIsDisplayed()
        assertEquals(0, backRequests)
        composeTestRule.onNodeWithText("Volver a Memoria").performClick()
        assertEquals(1, backRequests)
    }

    @Test
    fun validEntryDisplaysLoadedState() {
        composeTestRule.setContent {
            YERVISTheme(dynamicColor = false) {
                EntryDetailScreen(
                    state = EntryDetailUiState.Loaded(
                        DemoMemoryRepository.getEntry("demo-2026-10-11-001")!!,
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("entry_original_text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sin guardar").assertIsDisplayed()
        composeTestRule.onNodeWithText("America/Lima").assertIsDisplayed()
    }

    @Test
    fun invalidEntryDisplaysNotFoundState() {
        composeTestRule.setContent {
            YERVISTheme(dynamicColor = false) {
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
            YERVISTheme(dynamicColor = false) {
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
            YERVISTheme(dynamicColor = false) {
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
            YERVISTheme(dynamicColor = false) {
                MemoryScreen(
                    state = MemoryUiState.Content(DemoMemoryFixtures.entries),
                    callbacks = noOpCallbacks(),
                    onRetry = {},
                )
            }
        }
        composeTestRule.onNode(hasText("Recuerdos") and isHeading()).assertIsDisplayed()
        composeTestRule.onNode(hasText("Domingo 11 de octubre") and isHeading()).assertIsDisplayed()
        composeTestRule.onNodeWithTag("timeline_entry_${DemoMemoryFixtures.entries.first().id}")
            .assertHeightIsAtLeast(48.dp).assertIsDisplayed()
    }

    @Test
    fun memoryScreenWithLargeFontDoesNotClipContent() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                YERVISTheme(dynamicColor = false) {
                    MemoryScreen(
                        state = MemoryUiState.Content(DemoMemoryFixtures.entries),
                        callbacks = noOpCallbacks(),
                        onRetry = {},
                    )
                }
            }
        }
        composeTestRule.onNode(hasText("Recuerdos") and isHeading()).assertIsDisplayed()
        composeTestRule.onNodeWithText("Domingo 11 de octubre").assertIsDisplayed()
    }

    @Test
    fun selectionToolbarActionsRemainAccessibleWithLargeFont() {
        val entries = DemoMemoryFixtures.entries.take(2)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                YERVISTheme(dynamicColor = false) {
                    MemoryScreen(
                        state = MemoryUiState.Content(entries),
                        callbacks = noOpCallbacks(),
                        onRetry = {},
                        selectionState = MemorySelectionUiState(setOf(entries.first().id)),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("memory_selection_actions").assertIsDisplayed()
        composeTestRule.onNodeWithTag("memory_select_all").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        composeTestRule.onNodeWithTag("memory_delete_selected").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
    }

    @Test
    fun timelinePreviewUsesCurrentCorrectionAndKeepsOriginalOutOfPreview() {
        val original = DemoMemoryFixtures.entries.first()
        val corrected = "Versión corregida sintética para la vista previa."
        composeTestRule.setContent {
            YERVISTheme(dynamicColor = false) {
                MemoryScreen(
                    state = MemoryUiState.Content(listOf(original), mapOf(original.id to corrected)),
                    callbacks = noOpCallbacks(),
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithText(corrected).assertIsDisplayed()
        composeTestRule.onNodeWithText(original.rawText).assertDoesNotExist()
    }

    @Test
    fun longPressEnablesSelectionAndDeleteRequiresAnExplicitConfirmation() {
        val entries = DemoMemoryFixtures.entries.take(2)
        var selection by mutableStateOf(MemorySelectionUiState())
        var requestedDeletes = 0
        composeTestRule.setContent {
            YERVISTheme(dynamicColor = false) {
                MemoryScreen(
                    state = MemoryUiState.Content(entries),
                    callbacks = noOpCallbacks(),
                    onRetry = {},
                    selectionState = selection,
                    onToggleSelection = { id ->
                        val ids = if (id in selection.selectedIds) selection.selectedIds - id
                        else selection.selectedIds + id
                        selection = MemorySelectionUiState(ids)
                    },
                    onSelectAll = { ids -> selection = MemorySelectionUiState(ids) },
                    onClearSelection = { selection = MemorySelectionUiState() },
                    onRequestDeleteSelected = {
                        selection = selection.copy(deleteStatus = MemoryDeleteSelectionStatus.CONFIRMING)
                    },
                    onDismissDeleteSelected = {
                        selection = selection.copy(deleteStatus = MemoryDeleteSelectionStatus.IDLE)
                    },
                    onDeleteSelected = { requestedDeletes++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Mantén presionado un recuerdo para seleccionarlo.").assertDoesNotExist()
        composeTestRule.onNodeWithTag("timeline_entry_${entries.first().id}")
            .performTouchInput { longClick() }
        assertEquals(setOf(entries.first().id), selection.selectedIds)
        composeTestRule.onNodeWithTag("memory_selection_actions").assertIsDisplayed()
        composeTestRule.onNodeWithTag("memory_select_all").performClick()
        assertEquals(entries.map { it.id }.toSet(), selection.selectedIds)
        composeTestRule.onNodeWithTag("memory_delete_selected").performClick()
        composeTestRule.onNodeWithText("¿Eliminar recuerdos seleccionados?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("memory_delete_selected_cancel").performClick()

        assertEquals(0, requestedDeletes)
        assertEquals(entries.map { it.id }.toSet(), selection.selectedIds)
        assertEquals(MemoryDeleteSelectionStatus.IDLE, selection.deleteStatus)
        composeTestRule.onNodeWithTag("memory_selection_cancel").performClick()
        assertEquals(emptySet<String>(), selection.selectedIds)
    }

    private fun noOpCallbacks() = MemoryUiCallbacks(
        onCaptureRequested = {},
        onNavigateTo = {},
        onMemoryEntrySelected = {},
    )
}
