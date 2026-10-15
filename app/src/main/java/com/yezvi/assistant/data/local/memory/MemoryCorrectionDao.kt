package com.yezvi.assistant.data.local.memory

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Insert/read-only DAO surface keeps corrections append-only at the application boundary. */
@Dao
interface MemoryCorrectionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(correction: MemoryEntryCorrectionEntity): Long

    @Query(
        "SELECT * FROM memory_entry_corrections " +
            "WHERE memoryEntryId = :memoryEntryId ORDER BY correctionId ASC",
    )
    suspend fun findHistory(memoryEntryId: String): List<MemoryEntryCorrectionEntity>

    @Query(
        "SELECT memory_entries.id AS memoryEntryId, " +
            "COALESCE(memory_entry_corrections.correctedText, memory_entries.rawText) AS text, " +
            "memory_entry_corrections.correctionId AS correctionId, " +
            "memory_entry_corrections.modifiedAtEpochMillis AS modifiedAtEpochMillis " +
            "FROM memory_entries " +
            "LEFT JOIN memory_entry_corrections " +
            "ON memory_entry_corrections.memoryEntryId = memory_entries.id " +
            "WHERE memory_entries.id = :memoryEntryId " +
            "ORDER BY memory_entry_corrections.correctionId DESC LIMIT 1",
    )
    suspend fun findCurrentVersion(memoryEntryId: String): CurrentMemoryTextRow?

    @Query(
        "SELECT memory_entries.id AS memoryEntryId, " +
            "COALESCE(memory_entry_corrections.correctedText, memory_entries.rawText) AS text, " +
            "memory_entry_corrections.correctionId AS correctionId, " +
            "memory_entry_corrections.modifiedAtEpochMillis AS modifiedAtEpochMillis " +
            "FROM memory_entries " +
            "LEFT JOIN memory_entry_corrections " +
            "ON memory_entry_corrections.correctionId = (" +
            "SELECT MAX(latest.correctionId) FROM memory_entry_corrections AS latest " +
            "WHERE latest.memoryEntryId = memory_entries.id) " +
            "ORDER BY memory_entries.id ASC",
    )
    fun observeCurrentVersions(): Flow<List<CurrentMemoryTextRow>>
}

/** Projection of either the original text or the latest appended correction. */
data class CurrentMemoryTextRow(
    val memoryEntryId: String,
    val text: String,
    val correctionId: Long?,
    val modifiedAtEpochMillis: Long?,
)
