package com.yezvi.assistant.data.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.domain.speech.LocalSpeechPreferences
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLocalSpeechPreferencesStoreTest {
    @Test fun settingsPersistLocallyWithoutRoomAndNormalizeVolume() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "speech-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        try {
            val first = AndroidLocalSpeechPreferencesStore(context, name)
            assertEquals(LocalSpeechPreferences(), first.preferences.value)
            val configured = LocalSpeechPreferences(enabled = true, volume = 0.42f, voiceId = "installed-es")
            first.update(configured)

            assertEquals(configured, AndroidLocalSpeechPreferencesStore(context, name).preferences.value)
            first.update(configured.copy(volume = 1.8f))
            assertEquals(1f, AndroidLocalSpeechPreferencesStore(context, name).preferences.value.volume)
        } finally {
            prefs.edit().clear().commit()
            context.deleteSharedPreferences(name)
        }
    }
}
