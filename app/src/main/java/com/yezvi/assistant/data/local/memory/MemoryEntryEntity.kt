package com.yezvi.assistant.data.local.memory

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.yezvi.assistant.domain.memory.CaptureSourceType

/** Room representation of the original, locally persisted capture. */
@Entity(
    tableName = "memory_entries",
    indices = [Index(value = ["capturedAtEpochMillis"])],
)
data class MemoryEntryEntity(
    @PrimaryKey
    val id: String,
    val rawText: String,
    val capturedAtEpochMillis: Long,
    val timezoneId: String,
    val sourceType: CaptureSourceType,
)
