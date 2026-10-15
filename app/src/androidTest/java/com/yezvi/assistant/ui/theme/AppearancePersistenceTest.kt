package com.yezvi.assistant.ui.theme

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Separate preference files owned by each test; never touches Room or a personal package. */
@RunWith(AndroidJUnit4::class)
class AppearancePersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun appearanceChoicesSurviveViewModelRecreation() = runBlocking {
        val name = "ux-test-${UUID.randomUUID()}"
        val firstStore = ViewModelStore()
        val secondStore = ViewModelStore()
        try {
            val first = AppearanceViewModel(context, name).also { firstStore.put("appearance", it) }
            assertEquals(AppearancePreferences(), withTimeout(5000) { first.preferences.filterNotNull().first() })
            val selection = AppearancePreferences(ThemeMode.Dark, VisualStyle.Crystal, false,
                VisualDensity.Compact, MotionPreference.Reduced)
            instrumentation.runOnMainSync { first.update(selection) }
            assertEquals(selection, first.preferences.value)
            withTimeout(5000) {
                while (context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("motion", null) != "Reduced") {
                    kotlinx.coroutines.delay(10)
                }
            }
            instrumentation.runOnMainSync { firstStore.clear() }
            val restored = AppearanceViewModel(context, name).also { secondStore.put("appearance", it) }
            assertEquals(selection, withTimeout(5000) { restored.preferences.filterNotNull().first() })
        } finally {
            instrumentation.runOnMainSync { firstStore.clear(); secondStore.clear() }
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun unknownFuturePreferenceValuesFallBackIndependently() = runBlocking {
        val name = "ux-test-${UUID.randomUUID()}"
        val store = ViewModelStore()
        try {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                .putString("theme", "Light").putString("style", "FutureStyle")
                .putString("density", "Unsupported").putString("motion", "Unsupported")
                .putBoolean("dynamic_color", false).commit()
            val viewModel = AppearanceViewModel(context, name).also { store.put("appearance", it) }
            assertEquals(AppearancePreferences(theme = ThemeMode.Light, dynamicColor = false),
                withTimeout(5000) { viewModel.preferences.filterNotNull().first() })
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            context.deleteSharedPreferences(name)
        }
    }
}
