package com.yezvi.assistant.domain.speech

import kotlinx.coroutines.flow.StateFlow

enum class LocalSpeechAvailability {
    PREPARING, READY, ENGINE_UNAVAILABLE, LOCAL_VOICE_UNAVAILABLE, FAILED,
}

data class LocalSpeechVoiceOption(
    val id: String,
    val label: String,
    val languageTag: String,
)

data class LocalSpeechPreferences(
    val enabled: Boolean = false,
    val volume: Float = 1f,
    /** Null selects the best installed local Spanish voice; non-null must match a listed option. */
    val voiceId: String? = null,
) {
    fun normalized(): LocalSpeechPreferences = copy(
        volume = volume.coerceIn(0f, 1f),
        voiceId = voiceId?.takeIf(String::isNotBlank),
    )
}

/** Small app-private preference store; settings are not part of the memory database. */
interface LocalSpeechPreferencesStore {
    val preferences: StateFlow<LocalSpeechPreferences>
    fun update(value: LocalSpeechPreferences)
}

class InMemoryLocalSpeechPreferencesStore(
    initial: LocalSpeechPreferences = LocalSpeechPreferences(),
) : LocalSpeechPreferencesStore {
    private val mutablePreferences = kotlinx.coroutines.flow.MutableStateFlow(initial.normalized())
    override val preferences: StateFlow<LocalSpeechPreferences> = mutablePreferences

    override fun update(value: LocalSpeechPreferences) {
        mutablePreferences.value = value.normalized()
    }
}

/** Speaks only a fixed, non-private save acknowledgement. Calls belong to the main thread. */
interface LocalConfirmationSpeech {
    val availability: StateFlow<LocalSpeechAvailability>
    val voices: StateFlow<List<LocalSpeechVoiceOption>> get() = emptyVoiceOptions
    val selectedVoiceId: StateFlow<String?> get() = emptySelectedVoice
    fun initialize()
    /** Optional extension for adapters that can apply a selected installed voice. */
    fun initialize(preferredVoiceId: String?) {
        initialize()
        if (preferredVoiceId != null) selectVoice(preferredVoiceId)
    }
    fun selectVoice(voiceId: String?): Boolean = voiceId == null
    /** [volume] is per utterance and must never change the device's media volume. */
    fun announceSaved(): Boolean
    fun announceSaved(volume: Float): Boolean = announceSaved()
    fun stop()
    fun close()
}

private val emptyVoiceOptions = kotlinx.coroutines.flow.MutableStateFlow<List<LocalSpeechVoiceOption>>(emptyList())
private val emptySelectedVoice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

/** Provider declaration; absence of download/network flags is required, never inferred by name. */
internal fun isInstalledLocalSpanishVoice(
    language: String,
    requiresNetwork: Boolean,
    notInstalled: Boolean,
    networkFeature: Boolean,
): Boolean = language == "es" && !requiresNetwork && !notInstalled && !networkFeature
