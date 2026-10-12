package com.yezvi.assistant.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * F1.11 — Returns true when the device requests reduced motion.
 *
 * Reads the system animator duration scale ("Remove animations" in Android
 * accessibility settings sets it to zero). The value is read once per
 * composition; toggling the setting while the app is open requires a restart.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        scale == 0f
    }
}

/** Collapses a duration to zero when reduced motion is requested. */
fun motionDurationMillis(baseMillis: Int, reduceMotion: Boolean): Int =
    if (reduceMotion) 0 else baseMillis
