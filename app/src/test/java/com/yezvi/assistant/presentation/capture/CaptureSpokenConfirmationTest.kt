package com.yezvi.assistant.presentation.capture

import androidx.lifecycle.ViewModelStore
import com.yezvi.assistant.domain.memory.*
import com.yezvi.assistant.domain.speech.LocalConfirmationSpeech
import com.yezvi.assistant.domain.speech.LocalSpeechAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSpokenConfirmationTest {
    @get:Rule val main = CaptureViewModelTest.MainDispatcherRule()
    private val input = CaptureInput("Contenido sintético que nunca se pronuncia", CaptureSourceType.VOICE, 1791234567890L, "America/Lima")

    @Test fun onlyCompletedSuccessSpeaksAndRetryUsesSameIdWithoutDuplicate() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        val speech = Speech()
        val vm = CaptureViewModel(Repository { value, id ->
            attempts++
            if (attempts == 1) { gate.await(); error("synthetic storage error") }
            MemoryEntry(id, value.rawText, value.sourceType, value.capturedAtEpochMillis, value.timezoneId, CaptureStatus.SAVED)
        }, { speech }) { "stable-id" }
        vm.spokenConfirmation.setEnabled(true)
        vm.spokenConfirmation.setForeground(true)
        runCurrent()
        assertEquals(0, speech.calls)
        vm.save(input)
        vm.save(input)
        runCurrent()
        assertEquals(0, speech.calls)
        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.uiState.value is CaptureSaveState.Error)
        assertEquals(0, speech.calls)
        vm.retry()
        runCurrent()
        assertEquals("stable-id", (vm.uiState.value as CaptureSaveState.Saved).entry.id)
        assertEquals(1, speech.calls)
        vm.retry()
        vm.save(input)
        runCurrent()
        assertEquals(1, speech.calls)
        assertEquals(2, attempts)
        vm.spokenConfirmation.close()
    }

    @Test fun cancelledStorageAndViewModelCleanupCannotSpeak() = runTest(main.dispatcher) {
        val speech = Speech()
        val vm = CaptureViewModel(Repository { _, _ -> awaitCancellation() }, { speech }) { "cancelled-id" }
        val store = ViewModelStore().apply { put("capture", vm) }
        vm.spokenConfirmation.setEnabled(true)
        vm.spokenConfirmation.setForeground(true)
        runCurrent()
        vm.save(input)
        runCurrent()
        store.clear()
        runCurrent()
        assertEquals(0, speech.calls)
        assertEquals(1, speech.closed)
        assertFalse(vm.uiState.value is CaptureSaveState.Saved)
    }

    private class Speech : LocalConfirmationSpeech {
        override val availability = MutableStateFlow(LocalSpeechAvailability.READY)
        var calls = 0
        var closed = 0
        override fun initialize() = Unit
        override fun announceSaved(): Boolean { calls++; return true }
        override fun stop() = Unit
        override fun close() { closed++ }
    }

    private class Repository(val save: suspend (CaptureInput, String) -> MemoryEntry) : MemoryRepository {
        override suspend fun save(input: CaptureInput, id: String) = save.invoke(input, id)
        override suspend fun findById(id: String): MemoryEntry? = null
        override fun observeById(id: String) = flowOf<MemoryEntry?>(null)
        override fun observeAll() = flowOf(emptyList<MemoryEntry>())
        override suspend fun deleteById(id: String) = false
        override suspend fun deleteByIds(ids: Set<String>) = 0
    }
}
