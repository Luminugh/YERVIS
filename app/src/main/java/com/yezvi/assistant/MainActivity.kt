package com.yezvi.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.view.WindowCompat
import com.yezvi.assistant.ui.navigation.YezviApp
import com.yezvi.assistant.ui.theme.YERVISTheme
import com.yezvi.assistant.ui.theme.AppearancePreferences
import com.yezvi.assistant.ui.theme.AppearanceViewModel
import com.yezvi.assistant.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearanceViewModel: AppearanceViewModel = viewModel(factory = viewModelFactory {
                initializer { AppearanceViewModel(applicationContext) }
            })
            val appearance by appearanceViewModel.preferences.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkBars = when (appearance?.theme ?: ThemeMode.System) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkBars
                    isAppearanceLightNavigationBars = !darkBars
                }
            }
            YERVISTheme(
                appearance = appearance ?: AppearancePreferences(),
                onAppearanceChange = appearanceViewModel::update,
            ) {
                if (appearance != null) YezviApp()
                else Surface(modifier = Modifier.fillMaxSize()) { }
            }
        }
    }
}
