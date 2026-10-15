package com.yezvi.assistant.ui.navigation

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryCorrection
import com.yezvi.assistant.domain.memory.MemoryCorrectionRepository
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryRepository
import com.yezvi.assistant.domain.memory.MemoryTextVersion
import com.yezvi.assistant.presentation.capture.CaptureViewModel
import com.yezvi.assistant.ui.components.YervisBackdrop
import com.yezvi.assistant.ui.theme.AppearancePreferences
import com.yezvi.assistant.ui.theme.MotionPreference
import com.yezvi.assistant.ui.theme.ThemeMode
import com.yezvi.assistant.ui.theme.VisualStyle
import com.yezvi.assistant.ui.theme.YERVISTheme
import com.yezvi.assistant.ui.theme.rememberReduceMotion
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.After
import androidx.lifecycle.ViewModelStore

/** UI-only fixtures: no Activity launcher, Room database, microphone, or cloud access. */
class VisualRenewalTest {
    @get:Rule val compose = createComposeRule()
    private var appearance by mutableStateOf(AppearancePreferences(dynamicColor = false, motion = MotionPreference.Reduced))
    private var fontScale by mutableStateOf(1f)
    private var reduced = false
    private var foreground = Color.Black
    private var background = Color.White
    private val repository = SyntheticMemories()
    private val captureStore = ViewModelStore()

    private fun launch() {
        val captureViewModel = CaptureViewModel(repository).also { captureStore.put("capture", it) }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                YERVISTheme(appearance = appearance, onAppearanceChange = { appearance = it }) {
                    val colors = androidx.compose.material3.MaterialTheme.colorScheme
                    val isReduced = rememberReduceMotion()
                    SideEffect { foreground = colors.onSurface; background = colors.surfaceContainerLow; reduced = isReduced }
                    YervisBackdrop {
                        YezviApp(captureViewModelOverride = captureViewModel,
                            memoryRepositoryOverride = repository, correctionRepositoryOverride = repository)
                    }
                }
            }
        }
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("today_memory_count")).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun releaseSyntheticController() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { captureStore.clear() }
    }

    @Test fun defaultBodyTextUsesTheDarkThemeForegroundInBothStyles() {
        launch()
        for (style in VisualStyle.entries) {
            compose.runOnIdle { appearance = appearance.copy(theme = ThemeMode.Dark, style = style) }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Tu memoria, a mano.").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
            assertTrue(layouts.isNotEmpty())
            assertEquals(foreground, layouts.first().layoutInput.style.color)
            val light = layouts.first().layoutInput.style.color.luminance()
            val dark = background.luminance()
            assertTrue((light + .05f) / (dark + .05f) >= 4.5f)
        }
    }

    @Test fun tabsStayAlignedAndUsableAtTwoHundredPercent() {
        launch()
        compose.runOnIdle { fontScale = 2f }
        for (tab in listOf("memory", "capture", "settings", "today")) {
            compose.onNodeWithTag("bottom_nav_$tab").performClick().assertIsSelected().assertHeightIsAtLeast(48.dp)
            compose.waitForIdle()
            val selected = compose.onNodeWithTag("bottom_nav_$tab").fetchSemanticsNode().boundsInRoot
            val indicator = compose.onNodeWithTag("bottom_nav_indicator", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(selected.center.x, indicator.center.x, 3f * compose.density.density)
            assertTrue(indicator.top <= selected.center.y && indicator.bottom >= selected.center.y)
        }
        snapshot("font200-home")
    }

    @Test fun changingAppearancePreservesEditorTextFocusAndSelection() {
        launch()
        compose.onNodeWithTag("bottom_nav_capture").performClick()
        val editor = compose.onNodeWithTag("capture_text_field")
        editor.performScrollTo().performClick().performTextInput("Texto sintético con selección estable")
        editor.performTextInputSelection(TextRange(6, 15))
        val before = editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        compose.runOnIdle { appearance = appearance.copy(style = VisualStyle.Crystal, theme = ThemeMode.Dark) }
        editor.assertIsFocused().assertTextContains("Texto sintético con selección estable")
        assertEquals(before, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        Espresso.closeSoftKeyboard()
        snapshot("crystal-dark-editor")
    }

    @Test fun appearanceControlsAreReversibleAndMaintainReadableThemes() {
        launch()
        compose.onNodeWithTag("bottom_nav_settings").performClick()
        compose.onNodeWithTag("appearance_style_Crystal").performScrollTo().performClick().assertIsSelected()
        assertEquals(VisualStyle.Crystal, appearance.style)
        compose.onNodeWithTag("appearance_style_Classic").performScrollTo().performClick().assertIsSelected()
        assertEquals(VisualStyle.Classic, appearance.style)
        for (theme in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            compose.onNodeWithTag("appearance_theme_${theme.name}").performScrollTo().performClick()
            compose.waitForIdle()
            val light = maxOf(foreground.luminance(), background.luminance())
            val dark = minOf(foreground.luminance(), background.luminance())
            assertTrue("Body contrast must reach 4.5:1", (light + .05f) / (dark + .05f) >= 4.5f)
        }
        compose.runOnIdle { appearance = appearance.copy(motion = MotionPreference.Reduced) }
        compose.waitForIdle()
        assertTrue(reduced)
        snapshot("classic-dark-settings")
    }

    @Test fun syntheticScreenshotMatrixAndDetailNavigation() {
        launch()
        for (theme in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            for (style in VisualStyle.entries) {
                compose.runOnIdle { appearance = appearance.copy(theme = theme, style = style) }
                for (tab in listOf("today", "memory", "capture", "settings")) {
                    compose.onNodeWithTag("bottom_nav_$tab").performClick()
                    compose.waitForIdle()
                    snapshot("${theme.name}-${style.name}-$tab")
                }
            }
        }
        compose.onNodeWithTag("bottom_nav_today").performClick()
        compose.onNodeWithTag("today_memory_synthetic-0").performScrollTo().performClick()
        compose.onNodeWithTag("entry_current_text").assertIsDisplayed()
        snapshot("detail-synthetic")
    }

    private fun snapshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "ux-renewal").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private class SyntheticMemories : MemoryRepository, MemoryCorrectionRepository {
        private val entries = MutableStateFlow(List(120) { index ->
            MemoryEntry("synthetic-$index", "Recuerdo sintético $index. Una idea conservada con su contexto original.",
                if (index % 2 == 0) CaptureSourceType.TEXT else CaptureSourceType.VOICE,
                1_792_094_400_000L - index * 3_600_000L, "America/Lima", CaptureStatus.SAVED)
        })
        override suspend fun save(input: CaptureInput, id: String): MemoryEntry = error("This visual fixture does not save")
        override suspend fun findById(id: String) = entries.value.firstOrNull { it.id == id }
        override fun observeById(id: String) = entries.map { list -> list.firstOrNull { it.id == id } }
        override fun observeAll() = entries
        override suspend fun deleteById(id: String): Boolean = error("This visual fixture does not delete")
        override suspend fun deleteByIds(ids: Set<String>): Int = error("This visual fixture does not delete")
        override suspend fun addCorrection(memoryEntryId: String, correctedText: String, modifiedAtEpochMillis: Long): MemoryCorrection =
            error("This visual fixture does not save corrections")
        override suspend fun getCurrentVersion(memoryEntryId: String) = findById(memoryEntryId)?.let {
            MemoryTextVersion(it.id, it.rawText, null, null)
        }
        override fun observeCurrentVersions() = entries.map { list -> list.map { MemoryTextVersion(it.id, it.rawText, null, null) } }
        override suspend fun getCorrectionHistory(memoryEntryId: String) = emptyList<MemoryCorrection>()
    }
}
