package com.yezvi.assistant.data.repository

import com.yezvi.assistant.data.local.memory.MemoryCorrectionDao
import com.yezvi.assistant.data.local.memory.MemoryEntryCorrectionEntity
import com.yezvi.assistant.data.local.memory.toMemoryCorrection
import com.yezvi.assistant.data.local.memory.toMemoryTextVersion
import com.yezvi.assistant.domain.memory.MemoryCorrection
import com.yezvi.assistant.domain.memory.MemoryCorrectionRepository
import com.yezvi.assistant.domain.memory.MemoryTextVersion

/** Room adapter for explicit text revisions; original entries are only read by its DAO. */
class RoomMemoryCorrectionRepository(
    private val memoryCorrectionDao: MemoryCorrectionDao,
) : MemoryCorrectionRepository {
    override suspend fun addCorrection(
        memoryEntryId: String,
        correctedText: String,
        modifiedAtEpochMillis: Long,
    ): MemoryCorrection {
        require(memoryEntryId.isNotBlank()) { "memoryEntryId must not be blank" }
        require(correctedText.isNotBlank()) { "correctedText must not be blank" }

        val entity = MemoryEntryCorrectionEntity(
            memoryEntryId = memoryEntryId,
            correctedText = correctedText,
            modifiedAtEpochMillis = modifiedAtEpochMillis,
        )
        val correctionId = memoryCorrectionDao.insert(entity)
        return entity.copy(correctionId = correctionId).toMemoryCorrection()
    }

    override suspend fun getCurrentVersion(memoryEntryId: String): MemoryTextVersion? =
        memoryCorrectionDao.findCurrentVersion(memoryEntryId)?.toMemoryTextVersion()

    override suspend fun getCorrectionHistory(memoryEntryId: String): List<MemoryCorrection> =
        memoryCorrectionDao.findHistory(memoryEntryId).map { it.toMemoryCorrection() }
}
