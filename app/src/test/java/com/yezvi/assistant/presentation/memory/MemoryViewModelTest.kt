package com.yezvi.assistant.presentation.memory

import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun emitsLoadingEmptyErrorThenRetriesToPersistedContent() =
        runTest(mainDispatcherRule.dispatcher) {
            val entry = MemoryEntry(
                id = "memory-retry-test",
                rawText = "Recuerdo sintético de prueba.",
                sourceType = CaptureSourceType.TEXT,
                capturedAtEpochMillis = 1_791_234_567_890L,
                timezoneId = "America/Lima",
                status = CaptureStatus.SAVED,
            )
            var observationCount = 0
            val repository = object : MemoryRepository {
                override fun observeAll(): Flow<List<MemoryEntry>> {
                    val currentObservation = observationCount++
                    return flow {
                        when (currentObservation) {
                            0 -> {
                                emit(emptyList())
                                awaitCancellation()
                            }
                            1 -> error("Synthetic repository failure")
                            else -> {
                                emit(listOf(entry))
                                awaitCancellation()
                            }
                        }
                    }
                }

                override suspend fun save(input: CaptureInput, id: String): MemoryEntry =
                    error("Not used by this test")

                override suspend fun findById(id: String): MemoryEntry? = null

                override suspend fun update(entry: MemoryEntry): Boolean = false

                override suspend fun deleteById(id: String): Boolean = false
            }
            val viewModel = MemoryViewModel(repository)
            val observedStates = mutableListOf<MemoryUiState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect(observedStates::add)
            }

            runCurrent()
            assertTrue(observedStates.contains(MemoryUiState.Loading))
            assertEquals(MemoryUiState.Empty, viewModel.uiState.value)

            viewModel.retry()
            runCurrent()
            assertEquals(MemoryUiState.Error, viewModel.uiState.value)

            viewModel.retry()
            runCurrent()

            assertEquals(MemoryUiState.Content(listOf(entry)), viewModel.uiState.value)
            assertEquals(3, observationCount)
            collector.cancel()
        }
}

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
