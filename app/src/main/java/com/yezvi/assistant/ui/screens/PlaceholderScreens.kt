package com.yezvi.assistant.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yezvi.assistant.R
import com.yezvi.assistant.presentation.actions.AppDestination
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.presentation.capture.SavedMemoryConfirmation
import com.yezvi.assistant.ui.navigation.YezviBottomBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(callbacks: MemoryUiCallbacks, spokenConfirmation: SavedMemoryConfirmation) {
    val confirmationState by spokenConfirmation.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, spokenConfirmation) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> spokenConfirmation.setSettingsVisible(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> spokenConfirmation.setSettingsVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            spokenConfirmation.setSettingsVisible(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            spokenConfirmation.setSettingsVisible(false)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.nav_settings), modifier = Modifier.semantics { heading() })
                },
            )
        },
        bottomBar = {
            YezviBottomBar(
                currentDestination = AppDestination.SETTINGS,
                onNavigateTo = callbacks.onNavigateTo,
            )
        },
    ) { innerPadding ->
        SettingsScreenContent(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            voiceSettings = {
                VoiceSettingsSection(
                    state = confirmationState,
                    onEnabledChange = spokenConfirmation::setEnabled,
                    onVolumeChange = spokenConfirmation::setVolume,
                    onVoiceChange = { spokenConfirmation.setPreferredVoice(it) },
                )
            },
        )
    }
}
