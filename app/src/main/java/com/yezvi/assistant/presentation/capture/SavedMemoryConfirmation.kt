package com.yezvi.assistant.presentation.capture

import com.yezvi.assistant.domain.speech.InMemoryLocalSpeechPreferencesStore
import com.yezvi.assistant.domain.speech.LocalConfirmationSpeech
import com.yezvi.assistant.domain.speech.LocalSpeechAvailability
import com.yezvi.assistant.domain.speech.LocalSpeechPreferences
import com.yezvi.assistant.domain.speech.LocalSpeechPreferencesStore
import com.yezvi.assistant.domain.speech.LocalSpeechVoiceOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class SaveConfirmationState(
    val enabled: Boolean = false,
    val availability: LocalSpeechAvailability? = null,
    val volume: Float = 1f,
    val preferredVoiceId: String? = null,
    val selectedVoiceId: String? = null,
    val availableVoices: List<LocalSpeechVoiceOption> = emptyList(),
)

/** Owns one TTS adapter and shared preferences; only Capturar can trigger an announcement. */
class SavedMemoryConfirmation(
    private val scope: CoroutineScope,
    private val preferences: LocalSpeechPreferencesStore = InMemoryLocalSpeechPreferencesStore(),
    private val speechFactory: (() -> LocalConfirmationSpeech)? = null,
) {
    private val initialPreferences = preferences.preferences.value.normalized()
    private val mutableState = MutableStateFlow(initialPreferences.toState())
    val state = mutableState.asStateFlow()
    private val consumedIds = mutableSetOf<String>()
    private var currentPreferences = initialPreferences
    private var captureForeground = false
    private var settingsVisible = false
    private var closed = false
    private var speech: LocalConfirmationSpeech? = null
    private var preparation: Job? = null
    private var preferenceCollection: Job? = null
    private var announcement: Job? = null

    init {
        preferenceCollection = scope.launch {
            preferences.preferences.collect { updateFromStore(it) }
        }
    }

    fun setEnabled(enabled: Boolean) = updatePreferences { copy(enabled = enabled) }

    fun setVolume(volume: Float) {
        updatePreferences { copy(volume = volume.coerceIn(0f, 1f)) }
        if (volume <= 0f) interrupt()
    }

    /** Null means automatic selection; a non-null ID is accepted only from current local options. */
    fun setPreferredVoice(voiceId: String?): Boolean {
        if (voiceId != null && state.value.availableVoices.none { it.id == voiceId }) return false
        updatePreferences { copy(voiceId = voiceId?.takeIf(String::isNotBlank)) }
        return true
    }

    /** Called by Capturar. This foreground bit is the only gate that permits speech. */
    fun setForeground(active: Boolean) {
        if (closed || captureForeground == active) return
        captureForeground = active
        if (!active) interrupt()
        reconcileEngine()
    }

    /** Settings may inspect local voice availability while TTS remains off and silent. */
    fun setSettingsVisible(visible: Boolean) {
        if (closed || settingsVisible == visible) return
        settingsVisible = visible
        reconcileEngine()
    }

    /** Invoked only after CaptureViewModel's repository operation has succeeded. */
    fun onPersisted(memoryId: String) {
        if (closed || !consumedIds.add(memoryId)) return
        if (!captureForeground || !state.value.enabled || state.value.availability != LocalSpeechAvailability.READY) return
        val owner = speech ?: return
        val volume = currentPreferences.volume
        announcement?.cancel()
        announcement = scope.launch {
            // Let persistence publish and return before contacting the system speech service.
            yield()
            if (!captureForeground || !state.value.enabled || speech !== owner) return@launch
            try {
                if (!owner.announceSaved(volume) && owner.availability.value == LocalSpeechAvailability.READY) {
                    mutableState.value = state.value.copy(availability = LocalSpeechAvailability.FAILED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(availability = LocalSpeechAvailability.FAILED)
            }
        }
    }

    fun interrupt() {
        announcement?.cancel()
        announcement = null
        runCatching { speech?.stop() }
    }

    fun close() {
        if (closed) return
        closed = true
        preferenceCollection?.cancel()
        preferenceCollection = null
        captureForeground = false
        settingsVisible = false
        release()
    }

    private fun updatePreferences(transform: LocalSpeechPreferences.() -> LocalSpeechPreferences) {
        if (closed) return
        val updated = currentPreferences.transform().normalized()
        if (updated == currentPreferences) return
        preferences.update(updated)
        updateFromStore(updated)
    }

    private fun updateFromStore(value: LocalSpeechPreferences) {
        if (closed) return
        val updated = value.normalized()
        val previous = currentPreferences
        currentPreferences = updated
        mutableState.value = state.value.copy(
            enabled = updated.enabled,
            volume = updated.volume,
            preferredVoiceId = updated.voiceId,
        )
        if (previous.enabled && !updated.enabled) interrupt()
        if (previous.voiceId != updated.voiceId) {
            runCatching { speech?.selectVoice(updated.voiceId) }
        }
        reconcileEngine()
    }

    private fun shouldOwnEngine(): Boolean =
        settingsVisible || (captureForeground && currentPreferences.enabled)

    private fun reconcileEngine() {
        if (closed) return
        if (shouldOwnEngine()) prepareIfNeeded() else {
            release()
            mutableState.value = state.value.copy(
                availability = null,
                selectedVoiceId = null,
                availableVoices = emptyList(),
            )
        }
    }

    private fun prepareIfNeeded() {
        if (speech != null || preparation?.isActive == true) return
        mutableState.value = state.value.copy(availability = LocalSpeechAvailability.PREPARING)
        preparation = scope.launch {
            try {
                val owner = speechFactory?.invoke()
                if (owner == null) {
                    mutableState.value = state.value.copy(availability = LocalSpeechAvailability.ENGINE_UNAVAILABLE)
                    return@launch
                }
                speech = owner
                owner.initialize(currentPreferences.voiceId)
                combine(owner.availability, owner.voices, owner.selectedVoiceId) { availability, voices, selected ->
                    Triple(availability, voices, selected)
                }.collect { (availability, voices, selected) ->
                    if (speech === owner && shouldOwnEngine() && !closed) {
                        mutableState.value = state.value.copy(
                            availability = availability,
                            availableVoices = voices,
                            selectedVoiceId = selected,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                release()
                mutableState.value = state.value.copy(availability = LocalSpeechAvailability.FAILED)
            }
        }
    }

    private fun release() {
        preparation?.cancel()
        preparation = null
        interrupt()
        val owner = speech
        speech = null
        runCatching { owner?.close() }
    }

    private fun LocalSpeechPreferences.toState() = SaveConfirmationState(
        enabled = enabled,
        volume = volume,
        preferredVoiceId = voiceId,
    )
}
