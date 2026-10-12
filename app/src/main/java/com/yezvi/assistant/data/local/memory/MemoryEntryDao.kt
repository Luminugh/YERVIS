package com.yezvi.assistant.data.local.memory

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

/** Typed access to persisted memory entries; database ownership is defined in F2.04. */
@Dao
interface MemoryEntryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: MemoryEntryEntity)

    @Query("SELECT * FROM memory_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MemoryEntryEntity?

    @Query(
        "SELECT * FROM memory_entries " +
            "ORDER BY capturedAtEpochMillis DESC, id DESC",
    )
    fun observeAll(): Flow<List<MemoryEntryEntity>>

    @Update
    suspend fun update(entry: MemoryEntryEntity): Int

    @Query("DELETE FROM memory_entries WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
