package com.yezvi.assistant.presentation.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yezvi.assistant.domain.memory.MemoryCorrection
import com.yezvi.assistant.domain.memory.MemoryCorrectionRepository
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryRepository
import com.yezvi.assistant.domain.memory.MemoryTextVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface EntryDetailUiState {
    data object Loading : EntryDetailUiState
    data class Loaded(val entry: MemoryEntry) : EntryDetailUiState
    data object NotFound : EntryDetailUiState
    data object Error : EntryDetailUiState
}

sealed interface EntryDeleteUiState {
    data object Idle : EntryDeleteUiState
    data object Deleting : EntryDeleteUiState
    data object Failed : EntryDeleteUiState
    data object Deleted : EntryDeleteUiState
}

enum class CorrectionSaveStatus {
    IDLE,
    SAVING,
    SAVED,
    FAILED,
}

data class EntryCorrectionUiState(
    val currentVersion: MemoryTextVersion? = null,
    val history: List<MemoryCorrection> = emptyList(),
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val isEditing: Boolean = false,
    val draft: String = "",
    val saveStatus: CorrectionSaveStatus = CorrectionSaveStatus.IDLE,
)

/** Loads one persisted memory entry by its stable navigation ID. */
class EntryDetailViewModel(
    private val memoryRepository: MemoryRepository,
    private val correctionRepository: MemoryCorrectionRepository,
    private val entryId: String,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val retryRequests = MutableStateFlow(0)
    private val _deleteState = MutableStateFlow<EntryDeleteUiState>(EntryDeleteUiState.Idle)
    val deleteState: StateFlow<EntryDeleteUiState> = _deleteState
    private val _correctionState = MutableStateFlow(EntryCorrectionUiState())
    val correctionState: StateFlow<EntryCorrectionUiState> = _correctionState

    init {
        loadCorrections()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<EntryDetailUiState> = retryRequests
        .flatMapLatest {
            flow {
                emit(EntryDetailUiState.Loading)
                emitAll(
                    memoryRepository.observeById(entryId).map { entry ->
                        entry?.let(EntryDetailUiState::Loaded) ?: EntryDetailUiState.NotFound
                    },
                )
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

    fun delete() {
        if (_deleteState.value == EntryDeleteUiState.Deleting ||
            _deleteState.value == EntryDeleteUiState.Deleted ||
            uiState.value !is EntryDetailUiState.Loaded
        ) return

        _deleteState.value = EntryDeleteUiState.Deleting
        viewModelScope.launch {
            try {
                _deleteState.value = if (memoryRepository.deleteById(entryId)) {
                    EntryDeleteUiState.Deleted
                } else {
                    // The record may have disappeared between display and confirmation.
                    EntryDeleteUiState.Idle
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _deleteState.value = EntryDeleteUiState.Failed
            }
        }
    }

    fun dismissDelete() {
        if (_deleteState.value != EntryDeleteUiState.Deleting &&
            _deleteState.value != EntryDeleteUiState.Deleted
        ) {
            _deleteState.value = EntryDeleteUiState.Idle
        }
    }

    fun retryCorrections() {
        if (_correctionState.value.isLoading ||
            _correctionState.value.saveStatus == CorrectionSaveStatus.SAVING
        ) return
        loadCorrections()
    }

    private fun loadCorrections() {
        _correctionState.update { it.copy(isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            try {
                val current = correctionRepository.getCurrentVersion(entryId)
                val history = correctionRepository.getCorrectionHistory(entryId)
                _correctionState.update {
                    it.copy(
                        currentVersion = current,
                        history = history,
                        isLoading = false,
                        loadFailed = current == null,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _correctionState.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }

    fun beginCorrection() {
        val state = _correctionState.value
        val current = state.currentVersion ?: return
        if (state.isLoading || state.loadFailed || state.saveStatus == CorrectionSaveStatus.SAVING) return
        _correctionState.value = state.copy(
            isEditing = true,
            draft = current.text,
            saveStatus = CorrectionSaveStatus.IDLE,
        )
    }

    fun updateCorrectionDraft(draft: String) {
        val state = _correctionState.value
        if (state.isEditing && state.saveStatus != CorrectionSaveStatus.SAVING) {
            _correctionState.value = state.copy(
                draft = draft,
                saveStatus = if (state.saveStatus == CorrectionSaveStatus.SAVED) {
                    CorrectionSaveStatus.IDLE
                } else {
                    state.saveStatus
                },
            )
        }
    }

    fun cancelCorrection() {
        val state = _correctionState.value
        if (!state.isEditing || state.saveStatus == CorrectionSaveStatus.SAVING) return
        _correctionState.value = state.copy(
            isEditing = false,
            draft = "",
            saveStatus = CorrectionSaveStatus.IDLE,
        )
    }

    fun saveCorrection() {
        val state = _correctionState.value
        val current = state.currentVersion ?: return
        if (!state.isEditing || state.saveStatus == CorrectionSaveStatus.SAVING) return
        if (state.draft.isBlank() || state.draft == current.text) return

        _correctionState.value = state.copy(saveStatus = CorrectionSaveStatus.SAVING)
        viewModelScope.launch {
            try {
                val correction = correctionRepository.addCorrection(
                    memoryEntryId = entryId,
                    correctedText = state.draft,
                    modifiedAtEpochMillis = nowEpochMillis(),
                )
                val newVersion = MemoryTextVersion(
                    memoryEntryId = correction.memoryEntryId,
                    text = correction.correctedText,
                    correctionId = correction.correctionId,
                    modifiedAtEpochMillis = correction.modifiedAtEpochMillis,
                )
                _correctionState.update { latest ->
                    latest.copy(
                        currentVersion = newVersion,
                        history = (latest.history + correction).sortedBy(MemoryCorrection::correctionId),
                        isEditing = false,
                        draft = "",
                        saveStatus = CorrectionSaveStatus.SAVED,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _correctionState.update {
                    it.copy(isEditing = true, saveStatus = CorrectionSaveStatus.FAILED)
                }
            }
        }
    }
}
