package com.yezvi.assistant.data.speech

import android.util.Log
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.data.local.memory.YezviDatabaseFactory
import com.yezvi.assistant.data.repository.RoomMemoryRepository
import com.yezvi.assistant.domain.memory.*
import com.yezvi.assistant.domain.speech.LocalConfirmationSpeech
import com.yezvi.assistant.domain.speech.LocalSpeechAvailability
import com.yezvi.assistant.presentation.capture.CaptureSaveState
import com.yezvi.assistant.presentation.capture.CaptureViewModel
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Discovery never calls speak; storage confirmation uses an inaudible fake. */
@RunWith(AndroidJUnit4::class)
class LocalConfirmationSpeechTest {
    @Test fun localVoiceSelectionAndZeroVolumeRemainSilent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val speech = withContext(Dispatchers.Main) { AndroidLocalConfirmationSpeech(context).also { it.initialize() } }
        try {
            val availability = withTimeout(12_000) { speech.availability.first { it != LocalSpeechAvailability.PREPARING } }
            assertEquals("This device must have an installed local Spanish voice for selection coverage", LocalSpeechAvailability.READY, availability)
            val option = speech.voices.value.first()
            assertTrue(option.languageTag.startsWith("es"))
            withContext(Dispatchers.Main) {
                assertTrue(speech.selectVoice(option.id))
                assertEquals(option.id, speech.selectedVoiceId.value)
                assertTrue(speech.announceSaved(0f)) // Adapter stops; never calls system speak at zero.
                assertFalse(speech.selectVoice("f312-deliberately-unavailable-voice"))
                assertEquals(LocalSpeechAvailability.LOCAL_VOICE_UNAVAILABLE, speech.availability.value)
                assertFalse(speech.announceSaved(0.5f))
                assertTrue(speech.selectVoice(null))
                speech.stop()
            }
        } finally { withContext(Dispatchers.Main) { speech.close() } }
    }
    @Test fun systemVoiceDiscoveryIsSilentAndCleanupPreventsSpeech() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val speech = withContext(Dispatchers.Main) {
            AndroidLocalConfirmationSpeech(context).also { it.initialize() }
        }
        try {
            val status = withTimeout(12_000) {
                speech.availability.first { it != LocalSpeechAvailability.PREPARING }
            }
            Log.i("YezviTtsTest", "Silent voice discovery: $status; no synthesis attempted")
            InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
                putString("ttsAvailability", status.name)
            })
            assertTrue(status in listOf(LocalSpeechAvailability.READY,
                LocalSpeechAvailability.ENGINE_UNAVAILABLE, LocalSpeechAvailability.LOCAL_VOICE_UNAVAILABLE,
                LocalSpeechAvailability.FAILED))
        } finally {
            withContext(Dispatchers.Main) { speech.close(); speech.close(); assertFalse(speech.announceSaved()) }
        }
    }

    @Test fun closingDuringInitializationNeverBecomesSpeakable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.Main) {
            val speech = AndroidLocalConfirmationSpeech(context)
            speech.initialize()
            speech.close()
            speech.initialize()
            assertFalse(speech.announceSaved())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun confirmationFollowsRealSqliteCommitAndDoesNotRepeat() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "yezvi-f310-${UUID.randomUUID()}.db"
        val database = YezviDatabaseFactory.create(context, databaseName)
        val repository = RoomMemoryRepository(database.memoryEntryDao())
        val confirmed = CompletableDeferred<Unit>()
        val speech = object : LocalConfirmationSpeech {
            override val availability = MutableStateFlow(LocalSpeechAvailability.READY)
            var calls = 0
            override fun initialize() = Unit
            override fun announceSaved(): Boolean { calls++; confirmed.complete(Unit); return true }
            override fun stop() = Unit
            override fun close() = Unit
        }
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            CaptureViewModel(repository, { speech }) { "synthetic-tts-save" }.also {
                store.put("capture", it)
                it.spokenConfirmation.setForeground(true)
                it.spokenConfirmation.setEnabled(true)
            }
        }
        try {
            withTimeout(5_000) { vm.spokenConfirmation.state.first { it.availability == LocalSpeechAvailability.READY } }
            assertEquals(0, speech.calls)
            val input = CaptureInput("Nota sintética que no debe pronunciarse", CaptureSourceType.VOICE,
                1_791_234_567_890L, "America/Lima")
            withContext(Dispatchers.Main) { vm.save(input) }
            withTimeout(5_000) { confirmed.await() }
            assertTrue(vm.uiState.value is CaptureSaveState.Saved)
            assertEquals(input.rawText, repository.findById("synthetic-tts-save")?.rawText)
            withContext(Dispatchers.Main) { vm.save(input); vm.retry() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(1, speech.calls)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            database.close()
            // Only the unique, synthetic test database created above is removed.
            context.deleteDatabase(databaseName)
        }
    }
}
