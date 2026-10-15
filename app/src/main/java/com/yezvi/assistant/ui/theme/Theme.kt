package com.yezvi.assistant.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    secondary = TealGrey80,
    onSecondary = TealGrey20,
    secondaryContainer = TealGrey30,
    onSecondaryContainer = TealGrey90,
    tertiary = Amber80,
    onTertiary = Amber20,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    background = Color(0xFF0D1D25), surface = Color(0xFF101F27),
    onBackground = Color(0xFFE1EEF0), onSurface = Color(0xFFE1EEF0),
    onSurfaceVariant = Color(0xFFB8CCD0), surfaceVariant = Color(0xFF34464D),
    outline = Color(0xFF84999E), outlineVariant = Color(0xFF344A52),
    surfaceContainerLowest = Color(0xFF09171E), surfaceContainerLow = Color(0xFF14252E),
    surfaceContainer = Color(0xFF192C35), surfaceContainerHigh = Color(0xFF20343D),
    surfaceContainerHighest = Color(0xFF283D46),
)

private val LightColorScheme = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    secondary = TealGrey40,
    onSecondary = Color.White,
    secondaryContainer = TealGrey90,
    onSecondaryContainer = TealGrey20,
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,
    background = Color(0xFFF5F9FA), surface = Color(0xFFF8FAFA),
    onBackground = Color(0xFF193239), onSurface = Color(0xFF193239),
    onSurfaceVariant = Color(0xFF425D64), surfaceVariant = Color(0xFFD9E6E8),
    outline = Color(0xFF708588), outlineVariant = Color(0xFFC2D5D7),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEFF5F5),
    surfaceContainer = Color(0xFFE9F1F2), surfaceContainerHigh = Color(0xFFE1ECEE),
    surfaceContainerHighest = Color(0xFFDCE7E9),
)

@Composable
fun YERVISTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    appearance: AppearancePreferences = AppearancePreferences(),
    onAppearanceChange: (AppearancePreferences) -> Unit = {},
    content: @Composable () -> Unit
) {
    val effectiveDarkTheme = when (appearance.theme) {
        ThemeMode.System -> darkTheme
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val colorScheme = remember(context, effectiveDarkTheme, dynamicColor, appearance.dynamicColor) {
        when {
            dynamicColor && appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (effectiveDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            effectiveDarkTheme -> DarkColorScheme
            else -> LightColorScheme
        }
    }
    val systemReduceMotion = rememberSystemReduceMotion()
    val metrics = remember(appearance.density) {
        if (appearance.density == VisualDensity.Compact) VisualMetrics(16.dp, 8.dp, 16.dp)
        else VisualMetrics()
    }
    CompositionLocalProvider(
        LocalAppearance provides appearance,
        LocalAppearanceChange provides onAppearanceChange,
        LocalVisualMetrics provides metrics,
        LocalReduceMotion provides (systemReduceMotion || appearance.motion == MotionPreference.Reduced),
    ) { MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = YervisShapes,
        content = {
            CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground, content = content)
        }
    ) }
}
