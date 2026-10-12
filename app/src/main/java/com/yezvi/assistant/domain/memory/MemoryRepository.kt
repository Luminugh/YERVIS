package com.yezvi.assistant.domain.memory

import kotlinx.coroutines.flow.Flow

/** Persistence operations for original memory captures, independent of the storage engine. */
interface MemoryRepository {
    /** Saves [input] under a stable caller-provided [id] and returns it after storage succeeds. */
    suspend fun save(input: CaptureInput, id: String): MemoryEntry

    /** Returns the persisted entry for [id], or `null` when it does not exist. */
    suspend fun findById(id: String): MemoryEntry?

    /** Observes persisted entries in descending capture-time order with a stable ID tie-break. */
    fun observeAll(): Flow<List<MemoryEntry>>

    /** Replaces a persisted entry; returns `false` when no row with its ID exists. */
    suspend fun update(entry: MemoryEntry): Boolean

    /** Deletes the row for [id]; returns `false` when it does not exist. */
    suspend fun deleteById(id: String): Boolean
}
