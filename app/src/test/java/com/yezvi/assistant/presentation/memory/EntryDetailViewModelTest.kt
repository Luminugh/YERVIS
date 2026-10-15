package com.yezvi.assistant.presentation.memory

import androidx.lifecycle.ViewModelStore
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryCorrection
import com.yezvi.assistant.domain.memory.MemoryCorrectionRepository
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryRepository
import com.yezvi.assistant.domain.memory.MemoryTextVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntryDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun deleteWaitsForRepositoryAndRepeatedTapsStartOnlyOneOperation() =
        runTest(mainDispatcherRule.dispatcher) {
            val entry = syntheticEntry()
            val deleteResult = CompletableDeferred<Boolean>()
            val repository = FakeMemoryRepository(entry) { _, _ -> deleteResult.await() }
            val viewModel = EntryDetailViewModel(
                repository,
                FakeMemoryCorrectionRepository(entry.id, entry.rawText),
                entry.id,
            )
            val states = collectStates(viewModel)

            runCurrent()
            assertEquals(EntryDetailUiState.Loaded(entry), viewModel.uiState.value)

            viewModel.delete()
            viewModel.delete()
            assertEquals(EntryDeleteUiState.Deleting, viewModel.deleteState.value)
            runCurrent()

            assertEquals(listOf(entry.id), repository.deleteCalls)
            assertEquals(EntryDetailUiState.Loaded(entry), viewModel.uiState.value)
            assertEquals(EntryDeleteUiState.Deleting, viewModel.deleteState.value)

            repository.entry.value = null
            deleteResult.complete(true)
            advanceUntilIdle()

            assertEquals(EntryDeleteUiState.Deleted, viewModel.deleteState.value)
            assertEquals(EntryDetailUiState.NotFound, viewModel.uiState.value)
            assertTrue(states.contains(EntryDetailUiState.NotFound))
        }

    @Test
    fun failureKeepsEntryAndRetryCanDeleteIt() = runTest(mainDispatcherRule.dispatcher) {
        val entry = syntheticEntry()
        var attempts = 0
        val repository = FakeMemoryRepository(entry) { fake, _ ->
            attempts += 1
            if (attempts == 1) error("Synthetic SQLite failure")
            fake.entry.value = null
            true
        }
        val viewModel = EntryDetailViewModel(
            repository,
            FakeMemoryCorrectionRepository(entry.id, entry.rawText),
            entry.id,
        )
        collectStates(viewModel)
        runCurrent()

        viewModel.delete()
        advanceUntilIdle()

        assertEquals(EntryDeleteUiState.Failed, viewModel.deleteState.value)
        assertEquals(EntryDetailUiState.Loaded(entry), viewModel.uiState.value)

        viewModel.delete()
        advanceUntilIdle()

        assertEquals(EntryDeleteUiState.Deleted, viewModel.deleteState.value)
        assertEquals(EntryDetailUiState.NotFound, viewModel.uiState.value)
        assertEquals(listOf(entry.id, entry.id), repository.deleteCalls)
    }

    @Test
    fun cancellationIsNotReportedAsDeleteFailure() = runTest(mainDispatcherRule.dispatcher) {
        val entry = syntheticEntry()
        val repository = FakeMemoryRepository(entry) { _, _ -> awaitCancellation() }
        val viewModel = EntryDetailViewModel(
            repository,
            FakeMemoryCorrectionRepository(entry.id, entry.rawText),
            entry.id,
        )
        val store = ViewModelStore().apply { put("detail", viewModel) }
        collectStates(viewModel)
        runCurrent()

        viewModel.delete()
        runCurrent()
        store.clear()
        advanceUntilIdle()

        assertEquals(EntryDeleteUiState.Deleting, viewModel.deleteState.value)
        assertEquals(EntryDetailUiState.Loaded(entry), viewModel.uiState.value)
        assertEquals(listOf(entry.id), repository.deleteCalls)
    }

    @Test
    fun correctionAppendsRevisionAndLeavesOriginalEntryUntouched() = runTest(mainDispatcherRule.dispatcher) {
        val entry = syntheticEntry()
        val corrections = FakeMemoryCorrectionRepository(entry.id, entry.rawText)
        val viewModel = EntryDetailViewModel(
            FakeMemoryRepository(entry) { _, _ -> error("Not used") },
            corrections,
            entry.id,
            nowEpochMillis = { 1_791_234_600_000L },
        )
        val states = collectCorrectionStates(viewModel)
        runCurrent()

        assertEquals(entry.rawText, viewModel.correctionState.value.currentVersion?.text)
        viewModel.beginCorrection()
        viewModel.updateCorrectionDraft("  Texto corregido sintético.\nSegunda línea.  ")
        viewModel.saveCorrection()
        advanceUntilIdle()

        val state = viewModel.correctionState.value
        assertEquals(CorrectionSaveStatus.SAVED, state.saveStatus)
        assertFalse(state.isEditing)
        assertEquals("  Texto corregido sintético.\nSegunda línea.  ", state.currentVersion?.text)
        assertEquals(1L, state.currentVersion?.correctionId)
        assertEquals(1_791_234_600_000L, state.currentVersion?.modifiedAtEpochMillis)
        assertEquals(1, state.history.size)
        assertEquals(entry.rawText, corrections.originalText)
        assertEquals(1, corrections.addCalls.size)
        assertTrue(states.any { it.isEditing && it.saveStatus == CorrectionSaveStatus.SAVING })
    }

    @Test
    fun cancelDoesNotPersistAndFailureKeepsDraftForRetryWithoutDuplicateTap() =
        runTest(mainDispatcherRule.dispatcher) {
            val entry = syntheticEntry()
            val writeCompletion = CompletableDeferred<MemoryCorrection>()
            var failFirstAttempt = true
            val corrections = FakeMemoryCorrectionRepository(entry.id, entry.rawText) { _, _, _ ->
                if (failFirstAttempt) {
                    failFirstAttempt = false
                    error("Synthetic SQLite failure")
                }
                writeCompletion.await()
            }
            val viewModel = EntryDetailViewModel(
                FakeMemoryRepository(entry) { _, _ -> error("Not used") },
                corrections,
                entry.id,
                nowEpochMillis = { 1_791_234_600_000L },
            )
            runCurrent()

            viewModel.beginCorrection()
            viewModel.updateCorrectionDraft("Corrección sintética que se conserva.")
            viewModel.cancelCorrection()
            assertTrue(corrections.addCalls.isEmpty())
            assertNull(viewModel.correctionState.value.history.firstOrNull())

            viewModel.beginCorrection()
            viewModel.updateCorrectionDraft("Corrección sintética que se conserva.")
            viewModel.saveCorrection()
            advanceUntilIdle()
            assertEquals(CorrectionSaveStatus.FAILED, viewModel.correctionState.value.saveStatus)
            assertEquals("Corrección sintética que se conserva.", viewModel.correctionState.value.draft)
            assertTrue(viewModel.correctionState.value.isEditing)

            viewModel.saveCorrection()
            viewModel.saveCorrection()
            runCurrent()
            assertEquals(2, corrections.addCalls.size)
            assertEquals(CorrectionSaveStatus.SAVING, viewModel.correctionState.value.saveStatus)

            writeCompletion.complete(
                MemoryCorrection(
                    correctionId = 1L,
                    memoryEntryId = entry.id,
                    correctedText = "Corrección sintética que se conserva.",
                    modifiedAtEpochMillis = 1_791_234_600_000L,
                ),
            )
            advanceUntilIdle()

            assertEquals(CorrectionSaveStatus.SAVED, viewModel.correctionState.value.saveStatus)
            assertEquals(2, corrections.addCalls.size)
            assertEquals(entry.rawText, corrections.originalText)
        }

    @Test
    fun correctionCancellationIsNotReportedAsStorageFailure() = runTest(mainDispatcherRule.dispatcher) {
        val entry = syntheticEntry()
        val corrections = FakeMemoryCorrectionRepository(entry.id, entry.rawText) { _, _, _ ->
            awaitCancellation()
        }
        val viewModel = EntryDetailViewModel(
            FakeMemoryRepository(entry) { _, _ -> error("Not used") },
            corrections,
            entry.id,
        )
        val store = ViewModelStore().apply { put("detail", viewModel) }
        runCurrent()
        viewModel.beginCorrection()
        viewModel.updateCorrectionDraft("Corrección en espera.")
        viewModel.saveCorrection()
        runCurrent()

        assertEquals(CorrectionSaveStatus.SAVING, viewModel.correctionState.value.saveStatus)
        store.clear()
        advanceUntilIdle()

        assertEquals(CorrectionSaveStatus.SAVING, viewModel.correctionState.value.saveStatus)
        assertEquals("Corrección en espera.", viewModel.correctionState.value.draft)
    }

    private fun kotlinx.coroutines.test.TestScope.collectStates(
        viewModel: EntryDetailViewModel,
    ): MutableList<EntryDetailUiState> {
        val states = mutableListOf<EntryDetailUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect(states::add)
        }
        return states
    }

    private fun kotlinx.coroutines.test.TestScope.collectCorrectionStates(
        viewModel: EntryDetailViewModel,
    ): MutableList<EntryCorrectionUiState> {
        val states = mutableListOf<EntryCorrectionUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.correctionState.collect(states::add)
        }
        return states
    }

    private fun syntheticEntry() = MemoryEntry(
        id = "synthetic-entry-delete",
        rawText = "Recuerdo sintético para pruebas de borrado.",
        sourceType = CaptureSourceType.TEXT,
        capturedAtEpochMillis = 1_791_234_567_890L,
        timezoneId = "America/Lima",
        status = CaptureStatus.SAVED,
    )

    private class FakeMemoryRepository(
        initialEntry: MemoryEntry?,
        private val deleteBehavior: suspend (FakeMemoryRepository, String) -> Boolean,
    ) : MemoryRepository {
        val entry = MutableStateFlow(initialEntry)
        val deleteCalls = mutableListOf<String>()

        override suspend fun save(input: CaptureInput, id: String): MemoryEntry =
            error("Not used by this test")

        override suspend fun findById(id: String): MemoryEntry? =
            entry.value?.takeIf { it.id == id }

        override fun observeById(id: String): Flow<MemoryEntry?> =
            entry.map { it?.takeIf { current -> current.id == id } }

        override fun observeAll(): Flow<List<MemoryEntry>> =
            entry.map { listOfNotNull(it) }

        override suspend fun deleteById(id: String): Boolean {
            deleteCalls += id
            return deleteBehavior(this, id)
        }

        override suspend fun deleteByIds(ids: Set<String>): Int = ids.count { deleteById(it) }
    }

    private class FakeMemoryCorrectionRepository(
        private val entryId: String,
        val originalText: String,
        private val addBehavior: (suspend (String, String, Long) -> MemoryCorrection)? = null,
    ) : MemoryCorrectionRepository {
        val addCalls = mutableListOf<String>()
        private val corrections = mutableListOf<MemoryCorrection>()

        override suspend fun addCorrection(
            memoryEntryId: String,
            correctedText: String,
            modifiedAtEpochMillis: Long,
        ): MemoryCorrection {
            addCalls += correctedText
            val correction = addBehavior?.invoke(
                memoryEntryId,
                correctedText,
                modifiedAtEpochMillis,
            ) ?: MemoryCorrection(
                correctionId = (corrections.size + 1).toLong(),
                memoryEntryId = memoryEntryId,
                correctedText = correctedText,
                modifiedAtEpochMillis = modifiedAtEpochMillis,
            )
            corrections += correction
            return correction
        }

        override suspend fun getCurrentVersion(memoryEntryId: String): MemoryTextVersion? {
            if (memoryEntryId != entryId) return null
            val current = corrections.lastOrNull()
            return MemoryTextVersion(
                memoryEntryId = entryId,
                text = current?.correctedText ?: originalText,
                correctionId = current?.correctionId,
                modifiedAtEpochMillis = current?.modifiedAtEpochMillis,
            )
        }

        override suspend fun getCorrectionHistory(memoryEntryId: String): List<MemoryCorrection> =
            corrections.toList()

        override fun observeCurrentVersions(): Flow<List<MemoryTextVersion>> {
            val latest = corrections.lastOrNull()
            return flowOf(
                listOf(
                    MemoryTextVersion(
                        memoryEntryId = entryId,
                        text = latest?.correctedText ?: originalText,
                        correctionId = latest?.correctionId,
                        modifiedAtEpochMillis = latest?.modifiedAtEpochMillis,
                    ),
                ),
            )
        }
    }
}
