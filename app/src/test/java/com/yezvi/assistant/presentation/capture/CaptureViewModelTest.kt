package com.yezvi.assistant.presentation.capture

import androidx.lifecycle.ViewModelStore
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun successfulSaveEmitsIdleSavingSavedAndIgnoresRepeatedTaps() =
        runTest(mainDispatcherRule.dispatcher) {
            val input = syntheticInput()
            val result = syntheticEntry(id = "capture-stable-1", input = input)
            val completion = CompletableDeferred<MemoryEntry>()
            val repository = FakeMemoryRepository { _, _ -> completion.await() }
            var generatedIds = 0
            val viewModel = CaptureViewModel(repository) {
                generatedIds += 1
                "capture-stable-$generatedIds"
            }
            val states = collectStates(viewModel)

            viewModel.save(input)
            viewModel.save(input)
            assertEquals(
                CaptureSaveState.Saving(input = input, id = "capture-stable-1"),
                viewModel.uiState.value,
            )

            runCurrent()
            assertEquals(1, repository.saveCalls.size)
            assertEquals(input, repository.saveCalls.single().input)
            assertEquals("capture-stable-1", repository.saveCalls.single().id)

            completion.complete(result)
            advanceUntilIdle()
            viewModel.save(input)
            advanceUntilIdle()

            assertEquals(CaptureSaveState.Saved(result), viewModel.uiState.value)
            assertEquals(1, repository.saveCalls.size)
            assertEquals(1, generatedIds)
            assertEquals(
                listOf(
                    CaptureSaveState.Idle,
                    CaptureSaveState.Saving(input, "capture-stable-1"),
                    CaptureSaveState.Saved(result),
                ),
                List(3) { states.receive() },
            )
        }

    @Test
    fun failureKeepsInputAndRetryReusesIdUntilSuccess() = runTest(mainDispatcherRule.dispatcher) {
        val input = syntheticInput()
        val expectedEntry = syntheticEntry(id = "capture-retry-1", input = input)
        val failure = IllegalStateException("Synthetic storage failure")
        var attempts = 0
        val repository = FakeMemoryRepository { attemptInput, id ->
            attempts += 1
            if (attempts == 1) throw failure
            syntheticEntry(id = id, input = attemptInput)
        }
        var generatedIds = 0
        val viewModel = CaptureViewModel(repository) {
            generatedIds += 1
            "capture-retry-$generatedIds"
        }
        val states = collectStates(viewModel)

        viewModel.save(input)
        advanceUntilIdle()

        val error = viewModel.uiState.value as CaptureSaveState.Error
        assertEquals(CaptureStatus.FAILED, error.status)
        assertEquals(input, error.input)
        assertEquals("capture-retry-1", error.id)
        assertEquals(failure, error.cause)

        viewModel.retry()
        assertEquals(
            CaptureSaveState.Saving(input = input, id = error.id),
            viewModel.uiState.value,
        )
        advanceUntilIdle()

        assertEquals(CaptureSaveState.Saved(expectedEntry), viewModel.uiState.value)
        assertEquals(listOf("capture-retry-1", "capture-retry-1"), repository.saveCalls.map { it.id })
        assertEquals(1, generatedIds)
        assertEquals(
            listOf(
                CaptureSaveState.Idle,
                CaptureSaveState.Saving(input, "capture-retry-1"),
                CaptureSaveState.Error(input, "capture-retry-1", failure),
                CaptureSaveState.Saving(input, "capture-retry-1"),
                CaptureSaveState.Saved(expectedEntry),
            ),
            List(5) { states.receive() },
        )
    }

    @Test
    fun cancellationFromViewModelScopePropagatesToRepositoryAndIsNotAnError() =
        runTest(mainDispatcherRule.dispatcher) {
            val input = syntheticInput()
            val cancellationObserved = CompletableDeferred<Unit>()
            val repository = FakeMemoryRepository { _, _ ->
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancellationObserved.complete(Unit) }
                }
            }
            val viewModel = CaptureViewModel(repository) { "capture-cancelled" }
            val viewModelStore = ViewModelStore().apply { put("capture", viewModel) }

            viewModel.save(input)
            runCurrent()
            assertEquals(1, repository.saveCalls.size)
            assertEquals(CaptureStatus.SAVING, viewModel.uiState.value.status)

            viewModelStore.clear()
            advanceUntilIdle()

            assertTrue(cancellationObserved.isCompleted)
            assertTrue(viewModel.uiState.value is CaptureSaveState.Saving)
            assertFalse(viewModel.uiState.value is CaptureSaveState.Error)
        }

    private fun kotlinx.coroutines.test.TestScope.collectStates(
        viewModel: CaptureViewModel,
    ): Channel<CaptureSaveState> {
        val collected = Channel<CaptureSaveState>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { state -> collected.send(state) }
        }
        return collected
    }

    private fun syntheticInput() = CaptureInput(
        rawText = "Texto sintético original\npara pruebas.",
        sourceType = CaptureSourceType.TEXT,
        capturedAtEpochMillis = 1_791_234_567_890L,
        timezoneId = "America/Lima",
    )

    private fun syntheticEntry(id: String, input: CaptureInput) = MemoryEntry(
        id = id,
        rawText = input.rawText,
        sourceType = input.sourceType,
        capturedAtEpochMillis = input.capturedAtEpochMillis,
        timezoneId = input.timezoneId,
        status = CaptureStatus.SAVED,
    )

    private class FakeMemoryRepository(
        private val saveBehavior: suspend (CaptureInput, String) -> MemoryEntry,
    ) : MemoryRepository {
        val saveCalls = mutableListOf<SaveCall>()

        override suspend fun save(input: CaptureInput, id: String): MemoryEntry {
            saveCalls += SaveCall(input, id)
            return saveBehavior(input, id)
        }

        override suspend fun findById(id: String): MemoryEntry? = null

        override fun observeAll(): Flow<List<MemoryEntry>> = flowOf(emptyList())

        override suspend fun update(entry: MemoryEntry): Boolean = false

        override suspend fun deleteById(id: String): Boolean = false
    }

    private data class SaveCall(val input: CaptureInput, val id: String)

    class MainDispatcherRule(
        val dispatcher: TestDispatcher = StandardTestDispatcher(),
    ) : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(dispatcher)
        }

        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
}
