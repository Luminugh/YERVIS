package com.yezvi.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.R
import com.yezvi.assistant.domain.speech.LocalSpeechAvailability
import com.yezvi.assistant.presentation.capture.SaveConfirmationState
import kotlin.math.roundToInt

@Composable
internal fun VoiceSettingsSection(
    state: SaveConfirmationState,
    onEnabledChange: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVoiceChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var voiceMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedVoice = state.availableVoices.firstOrNull {
        it.id == (state.preferredVoiceId ?: state.selectedVoiceId)
    }
    val voiceLabel = when {
        selectedVoice != null && state.preferredVoiceId == null ->
            stringResource(R.string.settings_voice_auto_selected, selectedVoice.label)
        selectedVoice != null -> selectedVoice.label
        state.preferredVoiceId != null -> stringResource(R.string.settings_voice_selected_missing)
        state.availableVoices.isEmpty() -> stringResource(R.string.settings_voice_no_options)
        else -> stringResource(R.string.settings_voice_auto)
    }
    val volumePercent = (state.volume * 100).roundToInt()
    val enabledDescription = stringResource(
        if (state.enabled) R.string.settings_voice_on else R.string.settings_voice_off,
    )
    val volumeDescription = stringResource(R.string.settings_voice_volume_value, volumePercent)

    Card(modifier = modifier.fillMaxWidth().testTag("settings_voice_card")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_voice_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.settings_voice_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("settings_voice_enabled_row")
                    .toggleable(
                        value = state.enabled,
                        role = Role.Switch,
                        onValueChange = onEnabledChange,
                    )
                    .semantics { stateDescription = enabledDescription },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.capture_tts_opt_in), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (state.enabled) stringResource(R.string.settings_voice_on)
                        else stringResource(R.string.settings_voice_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = null,
                    modifier = Modifier.testTag("settings_voice_enabled"),
                )
            }

            Text(
                text = stringResource(R.string.settings_voice_volume),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_voice_volume_value, volumePercent),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics {
                    contentDescription = volumeDescription
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .semantics(mergeDescendants = true) { contentDescription = volumeDescription }
                    .testTag("settings_voice_volume_slider"),
                contentAlignment = Alignment.Center,
            ) {
                Slider(
                    value = state.volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = stringResource(R.string.settings_voice_volume_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.settings_voice_choice),
                style = MaterialTheme.typography.titleMedium,
            )
            Box {
                OutlinedButton(
                    onClick = { voiceMenuExpanded = true },
                    enabled = state.availableVoices.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("settings_voice_picker"),
                ) { Text(voiceLabel) }
                DropdownMenu(
                    expanded = voiceMenuExpanded,
                    onDismissRequest = { voiceMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_voice_auto)) },
                        onClick = {
                            voiceMenuExpanded = false
                            onVoiceChange(null)
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("settings_voice_auto_option"),
                    )
                    state.availableVoices.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text(voice.label) },
                            onClick = {
                                voiceMenuExpanded = false
                                onVoiceChange(voice.id)
                            },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("settings_voice_option_${voice.id}"),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.settings_voice_status, voiceAvailabilityLabel(state.availability)),
                modifier = Modifier.testTag("settings_voice_status").semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.availability == LocalSpeechAvailability.FAILED ||
                    state.availability == LocalSpeechAvailability.ENGINE_UNAVAILABLE ||
                    state.availability == LocalSpeechAvailability.LOCAL_VOICE_UNAVAILABLE
                ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun voiceAvailabilityLabel(availability: LocalSpeechAvailability?): String = when (availability) {
    null -> stringResource(R.string.settings_voice_not_checked)
    LocalSpeechAvailability.PREPARING -> stringResource(R.string.settings_voice_preparing)
    LocalSpeechAvailability.READY -> stringResource(R.string.settings_voice_ready)
    LocalSpeechAvailability.ENGINE_UNAVAILABLE -> stringResource(R.string.settings_voice_no_engine)
    LocalSpeechAvailability.LOCAL_VOICE_UNAVAILABLE -> stringResource(R.string.settings_voice_no_local_voice)
    LocalSpeechAvailability.FAILED -> stringResource(R.string.settings_voice_failed)
}
