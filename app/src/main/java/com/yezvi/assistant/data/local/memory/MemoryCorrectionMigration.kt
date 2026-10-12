package com.yezvi.assistant.data.local.memory

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL

/** Adds correction history without reading, copying, or changing any v1 capture row. */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.executeSQL(
            "CREATE TABLE IF NOT EXISTS `memory_entry_corrections` (" +
                "`correctionId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`memoryEntryId` TEXT NOT NULL, " +
                "`correctedText` TEXT NOT NULL, " +
                "`modifiedAtEpochMillis` INTEGER NOT NULL, " +
                "FOREIGN KEY(`memoryEntryId`) REFERENCES `memory_entries`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        connection.executeSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_entry_corrections_memoryEntryId_correctionId` " +
                "ON `memory_entry_corrections` (`memoryEntryId`, `correctionId`)",
        )
    }
}
