package com.yezvi.assistant.domain.memory

import kotlinx.coroutines.flow.Flow

/** An immutable, explicitly saved correction to an original memory entry. */
data class MemoryCorrection(
    val correctionId: Long,
    val memoryEntryId: String,
    val correctedText: String,
    val modifiedAtEpochMillis: Long,
) {
    init {
        require(correctionId > 0L) { "correctionId must be positive" }
        require(memoryEntryId.isNotBlank()) { "memoryEntryId must not be blank" }
        require(correctedText.isNotBlank()) { "correctedText must not be blank" }
    }
}

/** The current text for an entry; a null correction ID means the original capture is current. */
data class MemoryTextVersion(
    val memoryEntryId: String,
    val text: String,
    val correctionId: Long?,
    val modifiedAtEpochMillis: Long?,
) {
    init {
        require(memoryEntryId.isNotBlank()) { "memoryEntryId must not be blank" }
        require(text.isNotBlank()) { "text must not be blank" }
        require((correctionId == null) == (modifiedAtEpochMillis == null)) {
            "correction ID and modification time must both be present or absent"
        }
        require(correctionId == null || correctionId > 0L) { "correctionId must be positive" }
    }

    val isCorrection: Boolean
        get() = correctionId != null
}

/** Storage-independent operations for appending and reading correction history. */
interface MemoryCorrectionRepository {
    /** Appends a revision. Missing parent IDs and storage errors propagate to the caller. */
    suspend fun addCorrection(
        memoryEntryId: String,
        correctedText: String,
        modifiedAtEpochMillis: Long,
    ): MemoryCorrection

    /** Returns the corrected text, or the immutable original text if no correction exists. */
    suspend fun getCurrentVersion(memoryEntryId: String): MemoryTextVersion?

    /** Observes the current version for each persisted entry; updates when corrections are added. */
    fun observeCurrentVersions(): Flow<List<MemoryTextVersion>>

    /** Returns revisions in append order, oldest first. */
    suspend fun getCorrectionHistory(memoryEntryId: String): List<MemoryCorrection>
}
