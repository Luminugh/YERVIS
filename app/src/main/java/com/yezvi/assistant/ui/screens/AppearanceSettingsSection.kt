package com.yezvi.assistant.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.R
import com.yezvi.assistant.ui.components.YervisIcon
import com.yezvi.assistant.ui.components.YervisPanel
import com.yezvi.assistant.ui.components.YervisSymbol
import com.yezvi.assistant.ui.theme.LocalAppearance
import com.yezvi.assistant.ui.theme.LocalAppearanceChange
import com.yezvi.assistant.ui.theme.LocalVisualMetrics
import com.yezvi.assistant.ui.theme.MotionPreference
import com.yezvi.assistant.ui.theme.ThemeMode
import com.yezvi.assistant.ui.theme.VisualDensity
import com.yezvi.assistant.ui.theme.VisualStyle

@Composable
internal fun AppearanceSettingsSection(modifier: Modifier = Modifier) {
    val preferences = LocalAppearance.current
    val update = LocalAppearanceChange.current
    val metrics = LocalVisualMetrics.current
    YervisPanel(modifier.fillMaxWidth().testTag("appearance_settings"), highlighted = true) {
        Column(Modifier.padding(metrics.panelPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                YervisIcon(YervisSymbol.Palette, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.ux_appearance), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
            }
            Text(stringResource(R.string.ux_appearance_description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppearanceLabel(R.string.ux_theme)
            AppearanceChoices {
                ThemeMode.entries.forEach { theme ->
                    Choice(preferences.theme == theme, stringResource(when (theme) {
                        ThemeMode.System -> R.string.ux_system
                        ThemeMode.Light -> R.string.ux_light
                        ThemeMode.Dark -> R.string.ux_dark
                    }), "appearance_theme_${theme.name}") { update(preferences.copy(theme = theme)) }
                }
            }
            AppearanceLabel(R.string.ux_visual_style)
            AppearanceChoices {
                Choice(preferences.style == VisualStyle.Classic, stringResource(R.string.ux_classic),
                    "appearance_style_Classic") { update(preferences.copy(style = VisualStyle.Classic)) }
                Choice(preferences.style == VisualStyle.Crystal, stringResource(R.string.ux_crystal),
                    "appearance_style_Crystal") { update(preferences.copy(style = VisualStyle.Crystal)) }
            }
            Text(stringResource(if (preferences.style == VisualStyle.Crystal) R.string.ux_crystal_description
                else R.string.ux_classic_description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(preferences.dynamicColor, enabled = dynamicAvailable, role = Role.Switch,
                    onValueChange = { update(preferences.copy(dynamicColor = it)) })
                .testTag("appearance_dynamic_color"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ux_dynamic_color), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(if (dynamicAvailable) R.string.ux_dynamic_color_description
                        else R.string.ux_dynamic_color_unavailable), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(preferences.dynamicColor, onCheckedChange = null, enabled = dynamicAvailable)
            }
            AppearanceLabel(R.string.ux_density)
            AppearanceChoices {
                Choice(preferences.density == VisualDensity.Comfortable, stringResource(R.string.ux_comfortable),
                    "appearance_density_Comfortable") { update(preferences.copy(density = VisualDensity.Comfortable)) }
                Choice(preferences.density == VisualDensity.Compact, stringResource(R.string.ux_compact),
                    "appearance_density_Compact") { update(preferences.copy(density = VisualDensity.Compact)) }
            }
            AppearanceLabel(R.string.ux_motion)
            AppearanceChoices {
                Choice(preferences.motion == MotionPreference.System, stringResource(R.string.ux_motion_system),
                    "appearance_motion_System") { update(preferences.copy(motion = MotionPreference.System)) }
                Choice(preferences.motion == MotionPreference.Reduced, stringResource(R.string.ux_motion_reduced),
                    "appearance_motion_Reduced") { update(preferences.copy(motion = MotionPreference.Reduced)) }
            }
            Text(stringResource(R.string.ux_motion_description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun AppearanceLabel(resource: Int) {
    Text(stringResource(resource), style = MaterialTheme.typography.titleMedium)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AppearanceChoices(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = { content() })
}

@Composable private fun Choice(selected: Boolean, label: String, tag: String, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(label) },
        leadingIcon = if (selected) ({ YervisIcon(YervisSymbol.Check, modifier = Modifier.size(18.dp)) }) else null,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag))
}
