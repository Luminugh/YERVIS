package com.yezvi.assistant.presentation.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

sealed interface EntryDetailUiState {
    data object Loading : EntryDetailUiState
    data class Loaded(val entry: MemoryEntry) : EntryDetailUiState
    data object NotFound : EntryDetailUiState
    data object Error : EntryDetailUiState
}

/** Loads one persisted memory entry by its stable navigation ID. */
class EntryDetailViewModel(
    private val memoryRepository: MemoryRepository,
    private val entryId: String,
) : ViewModel() {
    private val retryRequests = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<EntryDetailUiState> = retryRequests
        .flatMapLatest {
            flow {
                emit(EntryDetailUiState.Loading)
                val entry = memoryRepository.findById(entryId)
                emit(entry?.let(EntryDetailUiState::Loaded) ?: EntryDetailUiState.NotFound)
            }.catch { failure ->
                if (failure is CancellationException) throw failure
                emit(EntryDetailUiState.Error)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = EntryDetailUiState.Loading,
        )

    fun retry() {
        retryRequests.update { it + 1 }
    }
}
