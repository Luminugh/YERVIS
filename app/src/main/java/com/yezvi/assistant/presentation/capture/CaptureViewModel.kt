package com.yezvi.assistant.presentation.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryRepository
import com.yezvi.assistant.domain.speech.LocalConfirmationSpeech
import com.yezvi.assistant.domain.speech.InMemoryLocalSpeechPreferencesStore
import com.yezvi.assistant.domain.speech.LocalSpeechPreferencesStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Capture persistence states; only [CaptureStatus.SAVED] follows a completed repository write. */
sealed interface CaptureSaveState {
    val status: CaptureStatus

    data object Idle : CaptureSaveState {
        override val status: CaptureStatus = CaptureStatus.IDLE
    }

    data class Saving(
        val input: CaptureInput,
        val id: String,
    ) : CaptureSaveState {
        override val status: CaptureStatus = CaptureStatus.SAVING
    }

    data class Saved(val entry: MemoryEntry) : CaptureSaveState {
        override val status: CaptureStatus = CaptureStatus.SAVED
    }

    /** Keeps the original input and ID in memory so the same capture can be retried. */
    data class Error(
        val input: CaptureInput,
        val id: String,
        val cause: Exception,
    ) : CaptureSaveState {
        override val status: CaptureStatus = CaptureStatus.FAILED
    }
}

/** Persists a capture without depending on UI components or Room. */
class CaptureViewModel(
    private val memoryRepository: MemoryRepository,
    confirmationSpeechFactory: (() -> LocalConfirmationSpeech)? = null,
    speechPreferencesStore: LocalSpeechPreferencesStore = InMemoryLocalSpeechPreferencesStore(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val stateLock = Any()
    private val _uiState = MutableStateFlow<CaptureSaveState>(CaptureSaveState.Idle)

    val uiState: StateFlow<CaptureSaveState> = _uiState.asStateFlow()
    val spokenConfirmation = SavedMemoryConfirmation(
        viewModelScope,
        speechPreferencesStore,
        confirmationSpeechFactory,
    )

    override fun onCleared() {
        spokenConfirmation.close()
        super.onCleared()
    }

    /** Starts one save; duplicate calls while it is running or after the same success are ignored. */
    fun save(input: CaptureInput) {
        val request = synchronized(stateLock) {
            val current = _uiState.value
            when (current) {
                is CaptureSaveState.Saving -> return
                is CaptureSaveState.Error -> if (current.input == input) {
                    SaveRequest(input = current.input, id = current.id)
                } else {
                    newRequest(input)
                }
                is CaptureSaveState.Saved -> if (current.entry.matches(input)) {
                    return
                } else {
                    newRequest(input)
                }
                CaptureSaveState.Idle -> newRequest(input)
            }.also { request ->
                _uiState.value = CaptureSaveState.Saving(request.input, request.id)
            }
        }
        viewModelScope.launch { persist(request) }
    }

    /** Retries the last failed input under its original ID; other states are left untouched. */
    fun retry() {
        val request = synchronized(stateLock) {
            val failed = _uiState.value as? CaptureSaveState.Error ?: return
            SaveRequest(input = failed.input, id = failed.id).also { retry ->
                _uiState.value = CaptureSaveState.Saving(retry.input, retry.id)
            }
        }
        viewModelScope.launch { persist(request) }
    }

    private fun newRequest(input: CaptureInput): SaveRequest {
        val id = idGenerator()
        require(id.isNotBlank()) { "idGenerator must return a non-blank ID" }
        return SaveRequest(input, id)
    }

    private suspend fun persist(request: SaveRequest) {
        try {
            val entry = memoryRepository.save(request.input, request.id)
            val confirmed = synchronized(stateLock) {
                if (_uiState.value.isSaving(request)) {
                    _uiState.value = CaptureSaveState.Saved(entry)
                    true
                } else false
            }
            if (confirmed) spokenConfirmation.onPersisted(entry.id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            synchronized(stateLock) {
                if (_uiState.value.isSaving(request)) {
                    _uiState.value = CaptureSaveState.Error(
                        input = request.input,
                        id = request.id,
                        cause = failure,
                    )
                }
            }
        }
    }

    private fun CaptureSaveState.isSaving(request: SaveRequest): Boolean =
        this is CaptureSaveState.Saving && input == request.input && id == request.id

    private fun MemoryEntry.matches(input: CaptureInput): Boolean =
        rawText == input.rawText &&
            sourceType == input.sourceType &&
            capturedAtEpochMillis == input.capturedAtEpochMillis &&
            timezoneId == input.timezoneId

    private data class SaveRequest(
        val input: CaptureInput,
        val id: String,
    )
}
