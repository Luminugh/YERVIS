package com.yezvi.assistant.data.local.memory

import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry

/** Assigns the caller-provided stable ID while preserving the capture exactly. */
fun CaptureInput.toMemoryEntryEntity(id: String): MemoryEntryEntity {
    require(id.isNotBlank()) { "id must not be blank" }
    return MemoryEntryEntity(
        id = id,
        rawText = rawText,
        capturedAtEpochMillis = capturedAtEpochMillis,
        timezoneId = timezoneId,
        sourceType = sourceType,
    )
}

/** A row read from the persistent store represents a successfully saved entry. */
fun MemoryEntryEntity.toMemoryEntry(): MemoryEntry = MemoryEntry(
    id = id,
    rawText = rawText,
    sourceType = sourceType,
    capturedAtEpochMillis = capturedAtEpochMillis,
    timezoneId = timezoneId,
    status = CaptureStatus.SAVED,
)
