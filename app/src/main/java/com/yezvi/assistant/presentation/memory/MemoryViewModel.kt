package com.yezvi.assistant.presentation.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

sealed interface MemoryUiState {
    data object Loading : MemoryUiState
    data object Empty : MemoryUiState
    data class Content(val entries: List<MemoryEntry>) : MemoryUiState
    data object Error : MemoryUiState
}

/** Lifecycle-scoped presentation of persisted memory entries. */
class MemoryViewModel(
    private val memoryRepository: MemoryRepository,
) : ViewModel() {
    private val retryRequests = MutableStateFlow(0)

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

    private fun observeEntries(): Flow<MemoryUiState> = kotlinx.coroutines.flow.flow {
        emit(MemoryUiState.Loading)
        emitAll(
            memoryRepository.observeAll().map { entries ->
                if (entries.isEmpty()) MemoryUiState.Empty else MemoryUiState.Content(entries)
            },
        )
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(MemoryUiState.Error)
    }
}
