package com.yezvi.assistant.data.local.memory

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [MemoryEntryEntity::class, MemoryEntryCorrectionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class YezviDatabase : RoomDatabase() {
    abstract fun memoryEntryDao(): MemoryEntryDao

    abstract fun memoryCorrectionDao(): MemoryCorrectionDao
}
