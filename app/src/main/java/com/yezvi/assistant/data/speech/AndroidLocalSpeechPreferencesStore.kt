package com.yezvi.assistant.data.speech

import android.content.Context
import com.yezvi.assistant.domain.speech.LocalSpeechPreferences
import com.yezvi.assistant.domain.speech.LocalSpeechPreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists only the user's TTS opt-in, per-utterance volume, and installed voice ID. */
class AndroidLocalSpeechPreferencesStore(
    context: Context,
    preferenceName: String = PREFERENCES_NAME,
) : LocalSpeechPreferencesStore {
    private val sharedPreferences = context.applicationContext
        .getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val mutablePreferences = MutableStateFlow(read())
    override val preferences = mutablePreferences.asStateFlow()

    @Synchronized
    override fun update(value: LocalSpeechPreferences) {
        val normalized = value.normalized()
        sharedPreferences.edit()
            .putBoolean(KEY_ENABLED, normalized.enabled)
            .putFloat(KEY_VOLUME, normalized.volume)
            .apply {
                if (normalized.voiceId == null) remove(KEY_VOICE_ID)
                else putString(KEY_VOICE_ID, normalized.voiceId)
            }
            .apply()
        mutablePreferences.value = normalized
    }

    private fun read() = LocalSpeechPreferences(
        enabled = sharedPreferences.getBoolean(KEY_ENABLED, false),
        volume = sharedPreferences.getFloat(KEY_VOLUME, 1f),
        voiceId = sharedPreferences.getString(KEY_VOICE_ID, null),
    ).normalized()

    private companion object {
        const val PREFERENCES_NAME = "local_speech_preferences"
        const val KEY_ENABLED = "confirmation_enabled"
        const val KEY_VOLUME = "confirmation_volume"
        const val KEY_VOICE_ID = "confirmation_voice_id"
    }
}
