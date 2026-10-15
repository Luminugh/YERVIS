package com.yezvi.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.R
import com.yezvi.assistant.ui.components.YervisIcon
import com.yezvi.assistant.ui.components.YervisPanel
import com.yezvi.assistant.ui.components.YervisSymbol
import com.yezvi.assistant.ui.theme.LocalVisualMetrics

@Composable internal fun PrivacySettingsSummary() {
    YervisPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(LocalVisualMetrics.current.panelPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            YervisIcon(YervisSymbol.Shield, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.ux_settings_privacy), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ux_settings_privacy_detail), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
