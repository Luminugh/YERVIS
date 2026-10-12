package com.yezvi.assistant.data.local.memory

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** One append-only revision; the referenced entry remains the authoritative original capture. */
@Entity(
    tableName = "memory_entry_corrections",
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryEntryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["memoryEntryId", "correctionId"])],
)
data class MemoryEntryCorrectionEntity(
    @PrimaryKey(autoGenerate = true)
    val correctionId: Long = 0L,
    val memoryEntryId: String,
    val correctedText: String,
    val modifiedAtEpochMillis: Long,
)
