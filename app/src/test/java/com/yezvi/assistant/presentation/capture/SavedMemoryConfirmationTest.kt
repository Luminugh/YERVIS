package com.yezvi.assistant.presentation.capture

import com.yezvi.assistant.domain.speech.LocalConfirmationSpeech
import com.yezvi.assistant.domain.speech.LocalSpeechAvailability
import com.yezvi.assistant.domain.speech.LocalSpeechVoiceOption
import com.yezvi.assistant.domain.speech.InMemoryLocalSpeechPreferencesStore
import com.yezvi.assistant.domain.speech.isInstalledLocalSpanishVoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedMemoryConfirmationTest {
    @Test fun selectedLocalVoiceAndVolumeReachAdapterWithoutReplayingSave() = runTest {
        val speech = FakeSpeech().apply {
            voices.value = listOf(LocalSpeechVoiceOption("installed-es", "Español local", "es-ES"))
        }
        val store = InMemoryLocalSpeechPreferencesStore()
        val controller = SavedMemoryConfirmation(backgroundScope, store) { speech }
        controller.setSettingsVisible(true); runCurrent()
        assertTrue(controller.setPreferredVoice("installed-es")); runCurrent()
        assertEquals("installed-es", speech.selectedVoiceId.value)
        assertEquals("installed-es", store.preferences.value.voiceId)
        assertFalse(controller.setPreferredVoice("network-or-missing"))
        controller.setEnabled(true)
        controller.setForeground(true)
        controller.setVolume(0.3f)
        controller.onPersisted("saved-once"); runCurrent()
        assertEquals(listOf(0.3f), speech.volumes)
        controller.setVolume(0.9f)
        controller.setPreferredVoice(null)
        controller.onPersisted("saved-once"); runCurrent()
        assertEquals(1, speech.announcements)
        assertNull(speech.selectedVoiceId.value)
        controller.close()
    }

    @Test fun mutingAfterCommitButBeforeDispatchCancelsQueuedSpeech() = runTest {
        val speech = FakeSpeech()
        val controller = SavedMemoryConfirmation(backgroundScope) { speech }
        controller.setEnabled(true); controller.setForeground(true); runCurrent()
        controller.onPersisted("queued-to-be-muted")
        controller.setVolume(0f)
        runCurrent()
        assertEquals(0, speech.announcements)
        assertTrue(speech.stops > 0)
        controller.close()
    }
    @Test fun onlyInstalledExplicitlyLocalSpanishPassesPolicy() {
        assertTrue(isInstalledLocalSpanishVoice("es", false, false, false))
        assertFalse(isInstalledLocalSpanishVoice("en", false, false, false))
        assertFalse(isInstalledLocalSpanishVoice("es", true, false, false))
        assertFalse(isInstalledLocalSpanishVoice("es", false, true, false))
        assertFalse(isInstalledLocalSpanishVoice("es", false, false, true))
    }

    @Test fun defaultOffDoesNotInitializeOrReplaySavesWhenEnabled() = runTest {
        val speech = FakeSpeech()
        val controller = SavedMemoryConfirmation(backgroundScope) { speech }
        controller.setForeground(true)
        controller.onPersisted("earlier")
        runCurrent()
        assertEquals(0, speech.initializations)
        controller.setEnabled(true)
        runCurrent()
        controller.onPersisted("earlier")
        runCurrent()
        assertEquals(0, speech.announcements)
        controller.onPersisted("new")
        runCurrent()
        assertEquals(1, speech.announcements)
        controller.close()
    }

    @Test fun missingEngineAndMissingOrNetworkOnlyVoiceStaySilent() = runTest {
        val missing = SavedMemoryConfirmation(backgroundScope, speechFactory = null)
        missing.setForeground(true)
        missing.setEnabled(true)
        runCurrent()
        missing.onPersisted("missing")
        assertEquals(LocalSpeechAvailability.ENGINE_UNAVAILABLE, missing.state.value.availability)
        missing.close()
        for (status in listOf(LocalSpeechAvailability.ENGINE_UNAVAILABLE, LocalSpeechAvailability.LOCAL_VOICE_UNAVAILABLE, LocalSpeechAvailability.FAILED)) {
            val speech = FakeSpeech(status)
            val controller = SavedMemoryConfirmation(backgroundScope) { speech }
            controller.setForeground(true)
            controller.setEnabled(true)
            runCurrent()
            controller.onPersisted("silent")
            runCurrent()
            assertEquals(0, speech.announcements)
            controller.close()
        }
    }

    @Test fun initializingDoesNotDelayOrReplaySuccessAndDuplicatesAreConsumed() = runTest {
        val speech = FakeSpeech(LocalSpeechAvailability.PREPARING)
        val controller = SavedMemoryConfirmation(backgroundScope) { speech }
        controller.setForeground(true)
        controller.setEnabled(true)
        runCurrent()
        controller.onPersisted("during-init")
        speech.availability.value = LocalSpeechAvailability.READY
        runCurrent()
        controller.onPersisted("during-init")
        controller.onPersisted("next")
        controller.onPersisted("next")
        runCurrent()
        assertEquals(1, speech.announcements)
        controller.close()
    }

    @Test fun leavingForegroundClosesAndLateCallbacksCannotAnnounceOrReplay() = runTest {
        val old = FakeSpeech(LocalSpeechAvailability.PREPARING)
        val fresh = FakeSpeech()
        var factories = 0
        val controller = SavedMemoryConfirmation(backgroundScope) { if (factories++ == 0) old else fresh }
        controller.setForeground(true)
        controller.setEnabled(true)
        runCurrent()
        controller.setForeground(false)
        old.availability.value = LocalSpeechAvailability.READY
        controller.onPersisted("background-save")
        runCurrent()
        assertEquals(1, old.closes)
        assertEquals(0, old.announcements)
        controller.setForeground(true)
        runCurrent()
        controller.onPersisted("background-save")
        runCurrent()
        assertEquals(0, fresh.announcements)
        controller.onPersisted("foreground-save")
        runCurrent()
        assertEquals(1, fresh.announcements)
        controller.close()
        controller.close()
        assertEquals(1, fresh.closes)
    }

    @Test fun disablingOrInterruptingCancelsQueuedAnnouncementAndShutdownIsFinal() = runTest {
        val speech = FakeSpeech()
        val controller = SavedMemoryConfirmation(backgroundScope) { speech }
        controller.setForeground(true)
        controller.setEnabled(true)
        runCurrent()
        controller.onPersisted("queued")
        controller.interrupt()
        runCurrent()
        assertEquals(0, speech.announcements)
        controller.onPersisted("queued-2")
        controller.setEnabled(false)
        runCurrent()
        assertEquals(0, speech.announcements)
        assertEquals(1, speech.closes)
        controller.close()
        controller.setEnabled(true)
        controller.onPersisted("closed")
        runCurrent()
        assertFalse(controller.state.value.enabled)
    }

    @Test fun speechFailureDoesNotThrowAndDoesNotRetrySameSave() = runTest {
        val speech = FakeSpeech().apply { throwOnSpeak = true }
        val controller = SavedMemoryConfirmation(backgroundScope) { speech }
        controller.setForeground(true)
        controller.setEnabled(true)
        runCurrent()
        controller.onPersisted("one")
        runCurrent()
        assertEquals(LocalSpeechAvailability.FAILED, controller.state.value.availability)
        controller.onPersisted("one")
        runCurrent()
        assertEquals(1, speech.announcements)
        controller.close()
    }

    @Test fun settingsSharePersistentPreferencesButNeverSpeakOutsideCapture() = runTest {
        val speech = FakeSpeech()
        val store = InMemoryLocalSpeechPreferencesStore()
        val controller = SavedMemoryConfirmation(backgroundScope, store) { speech }
        controller.setSettingsVisible(true)
        runCurrent()
        assertFalse(controller.state.value.enabled)
        assertEquals(1, speech.initializations)
        assertEquals(LocalSpeechAvailability.READY, controller.state.value.availability)

        controller.setEnabled(true)
        assertTrue(store.preferences.value.enabled)
        controller.setVolume(0.37f)
        controller.onPersisted("settings-only")
        runCurrent()
        assertEquals(0, speech.announcements)

        assertFalse(controller.setPreferredVoice("not-a-listed-local-voice"))
        controller.setSettingsVisible(false)
        controller.setForeground(true)
        runCurrent()
        controller.onPersisted("capture-save")
        runCurrent()
        assertEquals(listOf(0.37f), speech.volumes)
        controller.close()
    }

    private class FakeSpeech(status: LocalSpeechAvailability = LocalSpeechAvailability.READY) : LocalConfirmationSpeech {
        override val availability = MutableStateFlow(status)
        var initializations = 0
        var announcements = 0
        var closes = 0
        var throwOnSpeak = false
        var stops = 0
        val volumes = mutableListOf<Float>()
        private val mutableVoices = MutableStateFlow<List<com.yezvi.assistant.domain.speech.LocalSpeechVoiceOption>>(emptyList())
        private val mutableSelectedVoice = MutableStateFlow<String?>(null)
        override val voices = mutableVoices
        override val selectedVoiceId = mutableSelectedVoice
        override fun selectVoice(voiceId: String?): Boolean {
            if (voiceId != null && voices.value.none { it.id == voiceId }) return false
            selectedVoiceId.value = voiceId
            return true
        }
        override fun initialize() { initializations++ }
        override fun announceSaved(): Boolean {
            announcements++
            if (throwOnSpeak) error("synthetic speech error")
            return true
        }
        override fun announceSaved(volume: Float): Boolean {
            volumes += volume
            return announceSaved()
        }
        override fun stop() { stops++ }
        override fun close() { closes++ }
    }
}
