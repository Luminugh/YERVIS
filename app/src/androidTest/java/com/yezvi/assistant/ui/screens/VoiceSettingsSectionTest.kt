package com.yezvi.assistant.ui.screens

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.center
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yezvi.assistant.MainActivity
import com.yezvi.assistant.domain.speech.LocalConfirmationSpeech
import com.yezvi.assistant.domain.speech.LocalSpeechAvailability
import com.yezvi.assistant.domain.speech.LocalSpeechVoiceOption
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.presentation.capture.SaveConfirmationState
import com.yezvi.assistant.presentation.capture.SavedMemoryConfirmation
import com.yezvi.assistant.ui.theme.YERVISTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceSettingsSectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun defaultOffAccessibleControlsAndOnlyEnumeratedVoicesAreShown() {
        val state = mutableStateOf(
            SaveConfirmationState(
                availability = LocalSpeechAvailability.READY,
                availableVoices = listOf(LocalSpeechVoiceOption("es-es-local", "Español · calidad 400", "es-ES")),
                selectedVoiceId = "es-es-local",
            ),
        )
        val enabledChanges = mutableListOf<Boolean>()
        val selected = mutableListOf<String?>()
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                YERVISTheme {
                    VoiceSettingsSection(
                        state = state.value,
                        onEnabledChange = { enabledChanges += it; state.value = state.value.copy(enabled = it) },
                        onVolumeChange = { state.value = state.value.copy(volume = it) },
                        onVoiceChange = selected::add,
                    )
                }
            }
        }

        compose.onNodeWithTag("settings_voice_card").assertIsDisplayed()
        compose.onNodeWithTag("settings_voice_enabled_row").assertIsOff()
        compose.onNodeWithTag("settings_voice_enabled_row").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("settings_voice_enabled_row").assertIsOn()
        compose.runOnIdle { assertEquals(listOf(true), enabledChanges) }

        compose.onNodeWithTag("settings_voice_volume_slider").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("settings_voice_volume_slider").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0.5f, state.value.volume, 0.12f) }
        compose.onNodeWithTag("settings_voice_picker").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Español · calidad 400").assertIsDisplayed()
        compose.onNodeWithTag("settings_voice_option_es-es-local").performClick()
        compose.runOnIdle { assertEquals(listOf("es-es-local"), selected) }
        compose.onNodeWithTag("settings_voice_status").assertTextEquals("Disponibilidad: Voz española local lista")
    }

    @Test fun unavailableLocalVoiceDisablesVoicePickerAndStatusIsHonest() {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                YERVISTheme {
                    VoiceSettingsSection(
                        state = SaveConfirmationState(
                            enabled = true,
                            availability = LocalSpeechAvailability.LOCAL_VOICE_UNAVAILABLE,
                        ),
                        onEnabledChange = {}, onVolumeChange = {}, onVoiceChange = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("settings_voice_status").assertTextEquals(
            "Disponibilidad: No hay una voz española instalada declarada local",
        )
        compose.onNodeWithTag("settings_voice_picker").assertIsNotEnabled()
    }

    @Test fun sectionRemainsReadableAtLargeFontScale() {
        val density = compose.activity.resources.displayMetrics.density
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = density, fontScale = 1.8f)) {
                    YERVISTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            VoiceSettingsSection(
                                state = SaveConfirmationState(availability = LocalSpeechAvailability.PREPARING),
                                onEnabledChange = {}, onVolumeChange = {}, onVoiceChange = {},
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Voz y confirmaciones").assertIsDisplayed()
        compose.onNodeWithTag("settings_voice_status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_voice_enabled_row").assertHeightIsAtLeast(48.dp)
    }

    @Test fun actualSettingsRouteUsesSharedControllerAndNeverAnnounces() {
        val speech = SilentSpeech()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: SavedMemoryConfirmation
        compose.activity.runOnUiThread {
            controller = SavedMemoryConfirmation(scope) { speech }
            compose.activity.setContent {
                YERVISTheme { SettingsScreen(MemoryUiCallbacks({}, {}, {}), controller) }
            }
        }
        compose.waitUntil(5_000) { speech.initializations == 1 }
        compose.onNodeWithTag("settings_voice_card").assertIsDisplayed()
        compose.onNodeWithTag("settings_voice_enabled_row").assertIsOff()
        compose.onNodeWithTag("settings_voice_enabled_row").performClick()
        compose.waitUntil(5_000) { controller.state.value.enabled }
        compose.runOnIdle {
            assertEquals(0, speech.announcements)
            assertEquals(LocalSpeechAvailability.READY, controller.state.value.availability)
        }
        compose.activity.runOnUiThread { controller.close(); scope.cancel() }
    }

    private class SilentSpeech : LocalConfirmationSpeech {
        override val availability = MutableStateFlow(LocalSpeechAvailability.READY)
        var initializations = 0
        var announcements = 0
        override fun initialize() { initializations++ }
        override fun announceSaved(): Boolean { announcements++; return true }
        override fun stop() = Unit
        override fun close() = Unit
    }
}
