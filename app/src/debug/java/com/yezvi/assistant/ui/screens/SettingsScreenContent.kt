package com.yezvi.assistant.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.developer.LocalSpeechDiagnosticsActivity

@Composable
internal fun SettingsScreenContent(
    modifier: Modifier = Modifier,
    voiceSettings: @Composable () -> Unit,
    appearanceSettings: @Composable () -> Unit = {},
    proposalReview: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        appearanceSettings()
        voiceSettings()
        proposalReview()
        PrivacySettingsSummary()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Desarrollador",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Diagnóstico de voz local", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Abre el diagnóstico local Vosk / Sherpa. Puedes iniciarlo con o sin conectividad; el audio y el reconocimiento permanecen en el dispositivo. No guarda audio ni recuerdos.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            context.startActivity(Intent(context, LocalSpeechDiagnosticsActivity::class.java))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Abrir prueba STT offline")
                    }
                }
            }
        }
    }
}
