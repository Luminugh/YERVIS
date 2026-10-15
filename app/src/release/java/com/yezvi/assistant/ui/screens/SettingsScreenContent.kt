package com.yezvi.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreenContent(
    modifier: Modifier = Modifier,
    voiceSettings: @Composable () -> Unit,
    appearanceSettings: @Composable () -> Unit = {},
    proposalReview: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        appearanceSettings()
        voiceSettings()
        proposalReview()
        PrivacySettingsSummary()
    }
}
