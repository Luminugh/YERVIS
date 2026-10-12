package com.yezvi.assistant.data.local.memory

import com.yezvi.assistant.domain.memory.MemoryCorrection
import com.yezvi.assistant.domain.memory.MemoryTextVersion

fun MemoryEntryCorrectionEntity.toMemoryCorrection(): MemoryCorrection = MemoryCorrection(
    correctionId = correctionId,
    memoryEntryId = memoryEntryId,
    correctedText = correctedText,
    modifiedAtEpochMillis = modifiedAtEpochMillis,
)

fun CurrentMemoryTextRow.toMemoryTextVersion(): MemoryTextVersion = MemoryTextVersion(
    memoryEntryId = memoryEntryId,
    text = text,
    correctionId = correctionId,
    modifiedAtEpochMillis = modifiedAtEpochMillis,
)
