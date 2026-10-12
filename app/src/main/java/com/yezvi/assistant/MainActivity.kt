package com.yezvi.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yezvi.assistant.ui.navigation.YezviApp
import com.yezvi.assistant.ui.theme.YEZVITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YEZVITheme {
                YezviApp()
            }
        }
    }
}