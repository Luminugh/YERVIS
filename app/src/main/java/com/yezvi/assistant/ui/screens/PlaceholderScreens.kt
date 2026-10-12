package com.yezvi.assistant.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.R
import com.yezvi.assistant.presentation.actions.AppDestination
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.ui.navigation.YezviBottomBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(callbacks: MemoryUiCallbacks) {
    PlaceholderScaffold(titleRes = R.string.nav_settings, callbacks = callbacks)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScaffold(
    titleRes: Int,
    callbacks: MemoryUiCallbacks,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(titleRes),
                        modifier = Modifier.semantics { heading() },
                    )
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = stringResource(R.string.placeholder_title, stringResource(titleRes)),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}
