package com.yezvi.assistant.data.local.memory

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

/** Typed access to persisted memory entries; database ownership is defined in F2.04. */
@Dao
interface MemoryEntryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: MemoryEntryEntity)

    @Query("SELECT * FROM memory_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MemoryEntryEntity?

    @Query("SELECT * FROM memory_entries WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<MemoryEntryEntity?>

    @Query(
        "SELECT * FROM memory_entries " +
            "ORDER BY capturedAtEpochMillis DESC, id DESC",
    )
    fun observeAll(): Flow<List<MemoryEntryEntity>>

    @Query("DELETE FROM information_proposals WHERE extractionId IN (" +
        "SELECT DISTINCT extractionId FROM information_proposal_sources WHERE sourceEntryId = :id)")
    suspend fun deleteProposalExtractionsForSource(id: String): Int

    @Query("DELETE FROM information_proposals WHERE extractionId IN (" +
        "SELECT DISTINCT extractionId FROM information_proposal_sources WHERE sourceEntryId IN (:ids))")
    suspend fun deleteProposalExtractionsForSources(ids: List<String>): Int

    @Query("DELETE FROM memory_entries WHERE id = :id")
    suspend fun deleteEntryRow(id: String): Int

    @Query("DELETE FROM memory_entries WHERE id IN (:ids)")
    suspend fun deleteEntryRows(ids: List<String>): Int

    /** Remove dependent derived history first, then original and corrections atomically. */
    @Transaction
    suspend fun deleteById(id: String): Int {
        deleteProposalExtractionsForSource(id)
        return deleteEntryRow(id)
    }

    /** All proposals in an extraction share its authorized source set; remove the set as a unit. */
    @Transaction
    suspend fun deleteByIds(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        deleteProposalExtractionsForSources(ids)
        return deleteEntryRows(ids)
    }
}
