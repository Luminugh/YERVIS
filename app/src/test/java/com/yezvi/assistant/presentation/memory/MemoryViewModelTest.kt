package com.yezvi.assistant.presentation.memory

import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryCorrection
import com.yezvi.assistant.domain.memory.MemoryCorrectionRepository
import com.yezvi.assistant.domain.memory.MemoryRepository
import com.yezvi.assistant.domain.memory.MemoryTextVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

                override fun observeById(id: String): Flow<MemoryEntry?> = flowOf(null)

                override suspend fun deleteById(id: String): Boolean = false
                override suspend fun deleteByIds(ids: Set<String>): Int = 0
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

    @Test
    fun correctedVersionUpdatesTimelinePreviewWithoutChangingOriginalEntry() = runTest(mainDispatcherRule.dispatcher) {
        val entry = MemoryEntry(
            id = "memory-correction-preview",
            rawText = "Texto original e inmutable.",
            sourceType = CaptureSourceType.TEXT,
            capturedAtEpochMillis = 1_791_234_567_890L,
            timezoneId = "America/Lima",
            status = CaptureStatus.SAVED,
        )
        val entries = MutableStateFlow(listOf(entry))
        val corrections = MutableStateFlow(
            listOf(MemoryTextVersion(entry.id, entry.rawText, correctionId = null, modifiedAtEpochMillis = null)),
        )
        val repository = object : MemoryRepository {
            override suspend fun save(input: CaptureInput, id: String): MemoryEntry = error("Unused")
            override suspend fun findById(id: String): MemoryEntry? = entries.value.firstOrNull { it.id == id }
            override fun observeById(id: String): Flow<MemoryEntry?> = flowOf(entries.value.firstOrNull { it.id == id })
            override fun observeAll(): Flow<List<MemoryEntry>> = entries
            override suspend fun deleteById(id: String): Boolean = false
            override suspend fun deleteByIds(ids: Set<String>): Int = 0
        }
        val correctionRepository = object : MemoryCorrectionRepository {
            override suspend fun addCorrection(
                memoryEntryId: String,
                correctedText: String,
                modifiedAtEpochMillis: Long,
            ): MemoryCorrection = error("Unused")
            override suspend fun getCurrentVersion(memoryEntryId: String): MemoryTextVersion? =
                corrections.value.firstOrNull { it.memoryEntryId == memoryEntryId }
            override fun observeCurrentVersions(): Flow<List<MemoryTextVersion>> = corrections
            override suspend fun getCorrectionHistory(memoryEntryId: String): List<MemoryCorrection> = emptyList()
        }
        val viewModel = MemoryViewModel(repository, correctionRepository)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        runCurrent()
        assertEquals(
            MemoryUiState.Content(listOf(entry), mapOf(entry.id to entry.rawText)),
            viewModel.uiState.value,
        )

        corrections.value = listOf(
            MemoryTextVersion(entry.id, "Texto corregido por el usuario.", 1L, 1_791_234_600_000L),
        )
        runCurrent()

        assertEquals(
            MemoryUiState.Content(listOf(entry), mapOf(entry.id to "Texto corregido por el usuario.")),
            viewModel.uiState.value,
        )
        assertEquals("Texto original e inmutable.", entry.rawText)
        collector.cancel()
    }

    @Test
    fun selectedDeleteRequiresConfirmationAndKeepsSelectionForRetryAfterFailure() =
        runTest(mainDispatcherRule.dispatcher) {
            val entries = listOf("memory-selected-a", "memory-selected-b").mapIndexed { index, id ->
                MemoryEntry(
                    id = id,
                    rawText = "Recuerdo sintético $index",
                    sourceType = CaptureSourceType.TEXT,
                    capturedAtEpochMillis = 1_791_234_567_890L + index,
                    timezoneId = "America/Lima",
                    status = CaptureStatus.SAVED,
                )
            }
            var failNextDelete = true
            val deleteCalls = mutableListOf<Set<String>>()
            val repository = object : MemoryRepository {
                override suspend fun save(input: CaptureInput, id: String): MemoryEntry = error("Unused")
                override suspend fun findById(id: String): MemoryEntry? = entries.firstOrNull { it.id == id }
                override fun observeById(id: String): Flow<MemoryEntry?> = flowOf(entries.firstOrNull { it.id == id })
                override fun observeAll(): Flow<List<MemoryEntry>> = flowOf(entries)
                override suspend fun deleteById(id: String): Boolean = false
                override suspend fun deleteByIds(ids: Set<String>): Int {
                    deleteCalls += ids
                    if (failNextDelete) {
                        failNextDelete = false
                        error("Synthetic SQLite failure")
                    }
                    return ids.size
                }
            }
            val viewModel = MemoryViewModel(repository)

            viewModel.toggleSelection(entries[0].id)
            assertEquals(setOf(entries[0].id), viewModel.selectionState.value.selectedIds)
            viewModel.selectAll(entries.map { it.id }.toSet())
            assertEquals(entries.map { it.id }.toSet(), viewModel.selectionState.value.selectedIds)
            viewModel.requestDeleteSelected()
            assertEquals(MemoryDeleteSelectionStatus.CONFIRMING, viewModel.selectionState.value.deleteStatus)
            assertEquals(entries.map { it.id }.toSet(), viewModel.selectionState.value.selectedIds)

            viewModel.dismissDeleteConfirmation()
            assertEquals(MemoryDeleteSelectionStatus.IDLE, viewModel.selectionState.value.deleteStatus)
            assertEquals(entries.map { it.id }.toSet(), viewModel.selectionState.value.selectedIds)

            viewModel.requestDeleteSelected()
            viewModel.deleteSelected()
            runCurrent()
            assertEquals(MemoryDeleteSelectionStatus.FAILED, viewModel.selectionState.value.deleteStatus)
            assertEquals(entries.map { it.id }.toSet(), viewModel.selectionState.value.selectedIds)

            viewModel.deleteSelected()
            runCurrent()
            assertEquals(MemorySelectionUiState(), viewModel.selectionState.value)
            assertEquals(listOf(entries.map { it.id }.toSet(), entries.map { it.id }.toSet()), deleteCalls)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
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
