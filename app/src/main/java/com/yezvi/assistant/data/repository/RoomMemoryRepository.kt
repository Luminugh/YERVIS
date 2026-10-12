package com.yezvi.assistant.data.repository

import com.yezvi.assistant.data.local.memory.MemoryEntryDao
import com.yezvi.assistant.data.local.memory.toMemoryEntry
import com.yezvi.assistant.data.local.memory.toMemoryEntryEntity
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed implementation of the storage-independent memory contract. */
class RoomMemoryRepository(
    private val memoryEntryDao: MemoryEntryDao,
) : MemoryRepository {
    override suspend fun save(input: CaptureInput, id: String): MemoryEntry {
        val entity = input.toMemoryEntryEntity(id)
        memoryEntryDao.insert(entity)
        return entity.toMemoryEntry()
    }

    override suspend fun findById(id: String): MemoryEntry? =
        memoryEntryDao.findById(id)?.toMemoryEntry()

    override fun observeById(id: String): Flow<MemoryEntry?> =
        memoryEntryDao.observeById(id).map { entry -> entry?.toMemoryEntry() }

    override fun observeAll(): Flow<List<MemoryEntry>> =
        memoryEntryDao.observeAll().map { entries -> entries.map { it.toMemoryEntry() } }

    override suspend fun deleteById(id: String): Boolean =
        memoryEntryDao.deleteById(id) > 0
}
