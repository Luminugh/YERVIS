package com.yezvi.assistant.presentation.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryCorrectionRepository
import com.yezvi.assistant.domain.memory.MemoryRepository
import com.yezvi.assistant.domain.memory.MemoryTextVersion
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface MemoryUiState {
    data object Loading : MemoryUiState
    data object Empty : MemoryUiState
    data class Content(
        val entries: List<MemoryEntry>,
        /** Current corrected previews keyed by ID; originals remain unchanged in [MemoryEntry]. */
        val currentTextById: Map<String, String> = emptyMap(),
    ) : MemoryUiState
    data object Error : MemoryUiState
}

enum class MemoryDeleteSelectionStatus { IDLE, CONFIRMING, DELETING, FAILED }

data class MemorySelectionUiState(
    val selectedIds: Set<String> = emptySet(),
    val deleteStatus: MemoryDeleteSelectionStatus = MemoryDeleteSelectionStatus.IDLE,
)

/** Lifecycle-scoped presentation of persisted memory entries. */
class MemoryViewModel(
    private val memoryRepository: MemoryRepository,
    private val correctionRepository: MemoryCorrectionRepository? = null,
) : ViewModel() {
    private val retryRequests = MutableStateFlow(0)
    private val mutableSelectionState = MutableStateFlow(MemorySelectionUiState())
    val selectionState: StateFlow<MemorySelectionUiState> = mutableSelectionState

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<MemoryUiState> = retryRequests
        .flatMapLatest {
            observeEntries()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = MemoryUiState.Loading,
        )

    fun retry() {
        retryRequests.update { it + 1 }
    }

    fun toggleSelection(entryId: String) {
        if (mutableSelectionState.value.deleteStatus == MemoryDeleteSelectionStatus.DELETING) return
        mutableSelectionState.update { current ->
            val nextIds = if (entryId in current.selectedIds) current.selectedIds - entryId
            else current.selectedIds + entryId
            MemorySelectionUiState(nextIds)
        }
    }

    fun selectAll(entryIds: Set<String>) {
        mutableSelectionState.update { current ->
            if (current.deleteStatus != MemoryDeleteSelectionStatus.IDLE) current
            else current.copy(selectedIds = entryIds.filter(String::isNotBlank).toSet())
        }
    }

    fun clearSelection() {
        if (mutableSelectionState.value.deleteStatus != MemoryDeleteSelectionStatus.DELETING) {
            mutableSelectionState.value = MemorySelectionUiState()
        }
    }

    fun requestDeleteSelected() {
        mutableSelectionState.update { current ->
            if (current.selectedIds.isEmpty() || current.deleteStatus == MemoryDeleteSelectionStatus.DELETING) {
                current
            } else {
                current.copy(deleteStatus = MemoryDeleteSelectionStatus.CONFIRMING)
            }
        }
    }

    fun dismissDeleteConfirmation() {
        mutableSelectionState.update { current ->
            if (current.deleteStatus == MemoryDeleteSelectionStatus.DELETING) current
            else current.copy(deleteStatus = MemoryDeleteSelectionStatus.IDLE)
        }
    }

    fun deleteSelected() {
        val request = mutableSelectionState.value
        if (request.selectedIds.isEmpty() || request.deleteStatus !in setOf(
                MemoryDeleteSelectionStatus.CONFIRMING,
                MemoryDeleteSelectionStatus.FAILED,
            )
        ) return

        mutableSelectionState.value = request.copy(deleteStatus = MemoryDeleteSelectionStatus.DELETING)
        viewModelScope.launch {
            try {
                memoryRepository.deleteByIds(request.selectedIds)
                mutableSelectionState.update { current ->
                    if (current.selectedIds == request.selectedIds) MemorySelectionUiState()
                    else current.copy(
                        selectedIds = current.selectedIds - request.selectedIds,
                        deleteStatus = MemoryDeleteSelectionStatus.IDLE,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                mutableSelectionState.update { current ->
                    current.copy(deleteStatus = MemoryDeleteSelectionStatus.FAILED)
                }
            }
        }
    }

    private fun observeEntries(): Flow<MemoryUiState> = kotlinx.coroutines.flow.flow {
        emit(MemoryUiState.Loading)
        emitAll(
            combine(
                memoryRepository.observeAll(),
                correctionRepository?.observeCurrentVersions() ?: flowOf(emptyList<MemoryTextVersion>()),
            ) { entries, versions ->
                if (entries.isEmpty()) {
                    MemoryUiState.Empty
                } else {
                    MemoryUiState.Content(
                        entries = entries,
                        currentTextById = versions.associate { it.memoryEntryId to it.text },
                    )
                }
            },
        )
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(MemoryUiState.Error)
    }
}
