package com.yezvi.assistant.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.data.local.memory.MemoryEntryEntity
import com.yezvi.assistant.data.local.memory.YezviDatabaseFactory
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.MemoryCorrection
import com.yezvi.assistant.domain.memory.MemoryTextVersion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryCorrectionMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun migratesV1PreservesOriginalAndPersistsCorrectionHistoryAfterReopen() = runBlocking {
        val context = instrumentation.targetContext
        val databaseName = "yezvi-f209-migration-success.db"
        context.deleteDatabase(databaseName)
        val original = syntheticEntry()
        createV1Database(databaseName)
        seedV1Entry(databaseName, original)

        var database = YezviDatabaseFactory.create(context, databaseName)
        try {
            assertEquals(original, database.memoryEntryDao().findById(original.id))
            val repository = RoomMemoryCorrectionRepository(database.memoryCorrectionDao())
            assertEquals(
                MemoryTextVersion(
                    memoryEntryId = original.id,
                    text = original.rawText,
                    correctionId = null,
                    modifiedAtEpochMillis = null,
                ),
                repository.getCurrentVersion(original.id),
            )
            assertTrue(repository.getCorrectionHistory(original.id).isEmpty())

            val first = repository.addCorrection(
                memoryEntryId = original.id,
                correctedText = " Primera corrección sintética.\n",
                modifiedAtEpochMillis = 1_810_000_000_000L,
            )
            val second = repository.addCorrection(
                memoryEntryId = original.id,
                correctedText = "Segunda versión vigente sintética.",
                modifiedAtEpochMillis = 1_800_000_000_000L,
            )
            assertEquals(
                listOf(
                    MemoryCorrection(
                        first.correctionId,
                        original.id,
                        " Primera corrección sintética.\n",
                        1_810_000_000_000L,
                    ),
                    MemoryCorrection(
                        second.correctionId,
                        original.id,
                        "Segunda versión vigente sintética.",
                        1_800_000_000_000L,
                    ),
                ),
                repository.getCorrectionHistory(original.id),
            )
            assertTrue(second.correctionId > first.correctionId)
            assertEquals(
                MemoryTextVersion(
                    memoryEntryId = original.id,
                    text = "Segunda versión vigente sintética.",
                    correctionId = second.correctionId,
                    modifiedAtEpochMillis = 1_800_000_000_000L,
                ),
                repository.getCurrentVersion(original.id),
            )
            assertEquals(original, database.memoryEntryDao().findById(original.id))

            try {
                repository.addCorrection(
                    memoryEntryId = "missing-synthetic-memory",
                    correctedText = "Esta escritura debe fallar.",
                    modifiedAtEpochMillis = 1_820_000_000_000L,
                )
                throw AssertionError("La FK debe rechazar una corrección sin recuerdo padre")
            } catch (_: SQLiteConstraintException) {
                // The SQLite foreign key rejects the insert and the repository propagates it.
            }
            assertTrue(repository.getCorrectionHistory("missing-synthetic-memory").isEmpty())
            assertEquals(original, database.memoryEntryDao().findById(original.id))

            database.close()
            database = YezviDatabaseFactory.create(context, databaseName)
            val reopenedCorrections = RoomMemoryCorrectionRepository(database.memoryCorrectionDao())
            assertEquals(original, database.memoryEntryDao().findById(original.id))
            assertEquals(second, reopenedCorrections.getCorrectionHistory(original.id).last())
            assertEquals(
                "Segunda versión vigente sintética.",
                reopenedCorrections.getCurrentVersion(original.id)?.text,
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun failedMigrationLeavesV1DataAndVersionIntact() = runBlocking {
        val context = instrumentation.targetContext
        val databaseName = "yezvi-f209-migration-failure.db"
        context.deleteDatabase(databaseName)
        val original = syntheticEntry().copy(id = "synthetic-memory-before-failed-migration")
        createV1Database(databaseName)
        seedV1Entry(databaseName, original, createBlockingView = true)

        val database = YezviDatabaseFactory.create(context, databaseName)
        var migrationFailure: Throwable? = null
        try {
            database.memoryEntryDao().findById(original.id)
        } catch (failure: Exception) {
            migrationFailure = failure
        } finally {
            database.close()
        }

        try {
            assertNotNull("La vista conflictiva debe hacer fallar la migración", migrationFailure)
            val sqlite = SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            try {
                sqlite.rawQuery("PRAGMA user_version", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                sqlite.rawQuery(
                    "SELECT id, rawText, capturedAtEpochMillis, timezoneId, sourceType " +
                        "FROM memory_entries WHERE id = ?",
                    arrayOf(original.id),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(original.id, cursor.getString(0))
                    assertEquals(original.rawText, cursor.getString(1))
                    assertEquals(original.capturedAtEpochMillis, cursor.getLong(2))
                    assertEquals(original.timezoneId, cursor.getString(3))
                    assertEquals(original.sourceType.name, cursor.getString(4))
                }
                sqlite.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name = 'memory_entry_corrections'",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            } finally {
                sqlite.close()
                context.deleteDatabase(databaseName)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun createV1Database(databaseName: String) {
        val databaseFile = instrumentation.targetContext.getDatabasePath(databaseName)
        val sqlite = SQLiteDatabase.openOrCreateDatabase(databaseFile.absolutePath, null)
        try {
            sqlite.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_entries` (" +
                    "`id` TEXT NOT NULL, `rawText` TEXT NOT NULL, " +
                    "`capturedAtEpochMillis` INTEGER NOT NULL, `timezoneId` TEXT NOT NULL, " +
                    "`sourceType` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            sqlite.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_memory_entries_capturedAtEpochMillis` " +
                    "ON `memory_entries` (`capturedAtEpochMillis`)",
            )
            sqlite.execSQL(
                "CREATE TABLE IF NOT EXISTS `room_master_table` " +
                    "(id INTEGER PRIMARY KEY,identity_hash TEXT)",
            )
            sqlite.execSQL(
                "INSERT OR REPLACE INTO `room_master_table` (id, identity_hash) " +
                    "VALUES (42, 'b4fcf059fa27f1912523322722ef937e')",
            )
            sqlite.execSQL("PRAGMA user_version = 1")
        } finally {
            sqlite.close()
        }
    }

    private fun seedV1Entry(
        databaseName: String,
        entry: MemoryEntryEntity,
        createBlockingView: Boolean = false,
    ) {
        val sqlite = SQLiteDatabase.openDatabase(
            instrumentation.targetContext.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            sqlite.execSQL(
                "INSERT INTO memory_entries " +
                    "(id, rawText, capturedAtEpochMillis, timezoneId, sourceType) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    entry.id,
                    entry.rawText,
                    entry.capturedAtEpochMillis,
                    entry.timezoneId,
                    entry.sourceType.name,
                ),
            )
            if (createBlockingView) {
                sqlite.execSQL(
                    "CREATE VIEW memory_entry_corrections AS SELECT 1 AS migration_blocker",
                )
            }
        } finally {
            sqlite.close()
        }
    }

    private fun syntheticEntry() = MemoryEntryEntity(
        id = "synthetic-memory-v1",
        rawText = "  Original sintético v1; conserva saltos\ny espacios.  ",
        capturedAtEpochMillis = 1_791_234_567_890L,
        timezoneId = "America/Lima",
        sourceType = CaptureSourceType.VOICE,
    )
}
