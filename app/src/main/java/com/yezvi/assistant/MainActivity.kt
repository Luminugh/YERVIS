package com.yezvi.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yezvi.assistant.ui.theme.YEZVITheme
import com.yezvi.assistant.ui.today.TodayScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YEZVITheme {
                TodayScreen(onCaptureClick = {})
            }
        }
    }
}