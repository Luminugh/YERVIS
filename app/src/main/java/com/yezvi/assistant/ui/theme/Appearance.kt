package com.yezvi.assistant.ui.theme

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ThemeMode { System, Light, Dark }
enum class VisualStyle { Classic, Crystal }
enum class VisualDensity { Comfortable, Compact }
enum class MotionPreference { System, Reduced }

/** Presentation preferences only, independent of Room and speech preferences. */
@Immutable
data class AppearancePreferences(
    val theme: ThemeMode = ThemeMode.System,
    val style: VisualStyle = VisualStyle.Classic,
    val dynamicColor: Boolean = true,
    val density: VisualDensity = VisualDensity.Comfortable,
    val motion: MotionPreference = MotionPreference.System,
)

@Immutable
data class VisualMetrics(
    val screenPadding: Dp = 20.dp,
    val itemSpacing: Dp = 12.dp,
    val panelPadding: Dp = 20.dp,
)

val LocalAppearance = staticCompositionLocalOf { AppearancePreferences() }
val LocalAppearanceChange = staticCompositionLocalOf<(AppearancePreferences) -> Unit> { {} }
val LocalVisualMetrics = staticCompositionLocalOf { VisualMetrics() }

internal inline fun <reified T : Enum<T>> appearanceEnum(value: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

/** Loads on IO; serial, conflated writes keep the latest choice without blocking a tap. */
internal class AppearanceViewModel(context: Context, private val storeName: String = "yervis_appearance") : ViewModel() {
    private val applicationContext = context.applicationContext
    private val mutablePreferences = MutableStateFlow<AppearancePreferences?>(null)
    val preferences = mutablePreferences.asStateFlow()
    private val updates = Channel<AppearancePreferences>(Channel.CONFLATED)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val store = applicationContext.getSharedPreferences(storeName, Context.MODE_PRIVATE)
            val defaults = AppearancePreferences()
            mutablePreferences.value = runCatching {
                AppearancePreferences(
                    theme = appearanceEnum(store.getString("theme", null), defaults.theme),
                    style = appearanceEnum(store.getString("style", null), defaults.style),
                    dynamicColor = store.getBoolean("dynamic_color", true),
                    density = appearanceEnum(store.getString("density", null), defaults.density),
                    motion = appearanceEnum(store.getString("motion", null), defaults.motion),
                )
            }.getOrDefault(defaults)
            for (value in updates) {
                store.edit().putString("theme", value.theme.name).putString("style", value.style.name)
                    .putBoolean("dynamic_color", value.dynamicColor).putString("density", value.density.name)
                    .putString("motion", value.motion.name).apply()
            }
        }
    }

    fun update(value: AppearancePreferences) {
        if (mutablePreferences.value == null) return
        mutablePreferences.value = value
        updates.trySend(value)
    }
}
