package com.yezvi.assistant.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.data.local.memory.YezviDatabaseFactory
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMemoryRepositoryTest {
    @Test
    fun deleteCascadesCorrectionsUpdatesFlowsAndPersistsAfterReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "yezvi-f210-cascade-test.db"
        context.deleteDatabase(databaseName)
        var database = YezviDatabaseFactory.create(context, databaseName)
        val repository = RoomMemoryRepository(database.memoryEntryDao())
        val correctionRepository = RoomMemoryCorrectionRepository(database.memoryCorrectionDao())
        val input = CaptureInput(
            rawText = "Original sintético con historial de correcciones.",
            sourceType = CaptureSourceType.TEXT,
            capturedAtEpochMillis = 1_791_234_567_890L,
            timezoneId = "America/Lima",
        )
        val entry = repository.save(input, id = "synthetic-delete-with-history")
        val corrections = listOf(
            correctionRepository.addCorrection(
                entry.id,
                "Primera corrección sintética.",
                1_791_234_600_000L,
            ),
            correctionRepository.addCorrection(
                entry.id,
                "Segunda corrección sintética.",
                1_791_234_700_000L,
            ),
        )
        val memoryEmissions = Channel<List<MemoryEntry>>(Channel.UNLIMITED)
        val detailEmissions = Channel<MemoryEntry?>(Channel.UNLIMITED)
        val memoryObserver = launch { repository.observeAll().collect(memoryEmissions::send) }
        val detailObserver = launch { repository.observeById(entry.id).collect(detailEmissions::send) }

        try {
            assertEquals(listOf(entry), memoryEmissions.receive())
            assertEquals(entry, detailEmissions.receive())
            assertEquals(corrections, correctionRepository.getCorrectionHistory(entry.id))

            assertTrue(repository.deleteById(entry.id))

            assertEquals(emptyList<MemoryEntry>(), memoryEmissions.receive())
            assertNull(detailEmissions.receive())
            assertNull(repository.findById(entry.id))
            assertTrue(correctionRepository.getCorrectionHistory(entry.id).isEmpty())
            assertNull(correctionRepository.getCurrentVersion(entry.id))

            database.close()
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                sqlite.rawQuery(
                    "SELECT COUNT(*) FROM memory_entry_corrections WHERE memoryEntryId = ?",
                    arrayOf(entry.id),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("No correction rows should remain orphaned", 0, cursor.getInt(0))
                }
                sqlite.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                    assertFalse("The v2 database must have no foreign-key violations", cursor.moveToFirst())
                }
            }
            database = YezviDatabaseFactory.create(context, databaseName)
            val reopenedRepository = RoomMemoryRepository(database.memoryEntryDao())
            val reopenedCorrections = RoomMemoryCorrectionRepository(database.memoryCorrectionDao())
            assertNull(reopenedRepository.findById(entry.id))
            assertEquals(emptyList<MemoryEntry>(), reopenedRepository.observeAll().first())
            assertTrue(reopenedCorrections.getCorrectionHistory(entry.id).isEmpty())
            assertNull(reopenedCorrections.getCurrentVersion(entry.id))
        } finally {
            memoryObserver.cancelAndJoin()
            detailObserver.cancelAndJoin()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun failedStorageDeletePropagatesAndPreservesEntryAndCorrectionHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "yezvi-f210-delete-error-test.db"
        context.deleteDatabase(databaseName)
        var database = YezviDatabaseFactory.create(context, databaseName)
        val repository = RoomMemoryRepository(database.memoryEntryDao())
        val correctionRepository = RoomMemoryCorrectionRepository(database.memoryCorrectionDao())
        val entry = repository.save(
            CaptureInput(
                rawText = "Recuerdo sintético que debe conservarse ante error.",
                sourceType = CaptureSourceType.TEXT,
                capturedAtEpochMillis = 1_791_234_567_890L,
                timezoneId = "America/Lima",
            ),
            id = "synthetic-delete-error",
        )
        val correction = correctionRepository.addCorrection(
            entry.id,
            "Corrección sintética que también debe conservarse.",
            1_791_234_600_000L,
        )

        try {
            database.close()
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.execSQL(
                    "CREATE TRIGGER block_synthetic_memory_delete " +
                        "BEFORE DELETE ON memory_entries BEGIN " +
                        "SELECT RAISE(ABORT, 'synthetic delete failure'); END",
                )
            }
            database = YezviDatabaseFactory.create(context, databaseName)
            val failingRepository = RoomMemoryRepository(database.memoryEntryDao())
            val failingCorrections = RoomMemoryCorrectionRepository(database.memoryCorrectionDao())
            val failure = try {
                failingRepository.deleteById(entry.id)
                null
            } catch (exception: SQLiteConstraintException) {
                exception
            }
            assertNotNull("El trigger debe impedir y hacer fallar el borrado", failure)
            assertEquals(entry, failingRepository.findById(entry.id))
            assertEquals(listOf(correction), failingCorrections.getCorrectionHistory(entry.id))

            database.close()
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite -> sqlite.execSQL("DROP TRIGGER block_synthetic_memory_delete") }
            database = YezviDatabaseFactory.create(context, databaseName)
            val reopenedRepository = RoomMemoryRepository(database.memoryEntryDao())
            val reopenedCorrections = RoomMemoryCorrectionRepository(database.memoryCorrectionDao())
            assertEquals(entry, reopenedRepository.findById(entry.id))
            assertEquals(listOf(correction), reopenedCorrections.getCorrectionHistory(entry.id))
            assertTrue(reopenedRepository.deleteById(entry.id))
            assertNull(reopenedRepository.findById(entry.id))
            assertTrue(reopenedCorrections.getCorrectionHistory(entry.id).isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun repositoryPreservesFieldsPerformsCrudAndEmitsChronologicalUpdates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "yezvi-room-f205-crud-test.db"
        context.deleteDatabase(databaseName)
        val database = YezviDatabaseFactory.create(context, databaseName)
        val repository = RoomMemoryRepository(database.memoryEntryDao())
        val emissions = Channel<List<MemoryEntry>>(Channel.UNLIMITED)
        val observer = launch { repository.observeAll().collect { emissions.send(it) } }

        try {
            assertEquals(emptyList<MemoryEntry>(), emissions.receive())

            val first = repository.save(
                CaptureInput(
                    rawText = "  Texto original sintético A\ncon saltos y espacios.  ",
                    sourceType = CaptureSourceType.VOICE,
                    capturedAtEpochMillis = 1_791_234_567_890L,
                    timezoneId = "America/Lima",
                ),
                id = "memory-a",
            )
            assertEquals(
                MemoryEntry(
                    id = "memory-a",
                    rawText = "  Texto original sintético A\ncon saltos y espacios.  ",
                    sourceType = CaptureSourceType.VOICE,
                    capturedAtEpochMillis = 1_791_234_567_890L,
                    timezoneId = "America/Lima",
                    status = CaptureStatus.SAVED,
                ),
                first,
            )
            assertEquals(listOf(first), emissions.receive())

            val second = repository.save(
                CaptureInput(
                    rawText = "Registro sintético B.",
                    sourceType = CaptureSourceType.TEXT,
                    capturedAtEpochMillis = first.capturedAtEpochMillis,
                    timezoneId = "Etc/UTC",
                ),
                id = "memory-b",
            )
            assertEquals(listOf(second, first), emissions.receive())
            assertEquals(first, repository.findById(first.id))
            assertNull(repository.findById("missing-memory"))

            val latest = repository.save(
                CaptureInput(
                    rawText = "Registro sintético posterior en otra zona.",
                    sourceType = CaptureSourceType.TEXT,
                    capturedAtEpochMillis = first.capturedAtEpochMillis + 1L,
                    timezoneId = "Asia/Tokyo",
                ),
                id = "memory-c",
            )
            assertEquals(listOf(latest, second, first), emissions.receive())
            assertEquals("Asia/Tokyo", repository.findById(latest.id)?.timezoneId)

            assertTrue(repository.deleteById(second.id))
            assertEquals(listOf(latest, first), emissions.receive())
            assertFalse(repository.deleteById(second.id))
            assertNull(repository.findById(second.id))
        } finally {
            observer.cancelAndJoin()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun consecutiveCorrectionsPreserveOriginalAndHistoryAfterReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "yezvi-f212-consecutive-corrections.db"
        context.deleteDatabase(databaseName)
        var database = YezviDatabaseFactory.create(context, databaseName)
        val entryRepository = RoomMemoryRepository(database.memoryEntryDao())
        val input = CaptureInput(
            rawText = "  Original sintético exacto\ncon espacios.  ",
            sourceType = CaptureSourceType.VOICE,
            capturedAtEpochMillis = 1_791_234_567_890L,
            timezoneId = "America/Lima",
        )
        val original = entryRepository.save(input, id = "f212-correction-parent")
        var correctionRepository = RoomMemoryCorrectionRepository(database.memoryCorrectionDao())

        try {
            val first = correctionRepository.addCorrection(
                original.id,
                "Primera revisión sintética.",
                1_791_234_700_000L,
            )
            val second = correctionRepository.addCorrection(
                original.id,
                "  Segunda revisión vigente\ncon formato.  ",
                1_791_234_600_000L,
            )
            val third = correctionRepository.addCorrection(
                original.id,
                "Tercera revisión final.",
                1_791_234_800_000L,
            )

            assertTrue(first.correctionId < second.correctionId)
            assertTrue(second.correctionId < third.correctionId)
            assertEquals(listOf(first, second, third), correctionRepository.getCorrectionHistory(original.id))
            assertEquals("Tercera revisión final.", correctionRepository.getCurrentVersion(original.id)?.text)
            assertEquals(original, entryRepository.findById(original.id))

            database.close()
            database = YezviDatabaseFactory.create(context, databaseName)
            val reopenedEntries = RoomMemoryRepository(database.memoryEntryDao())
            correctionRepository = RoomMemoryCorrectionRepository(database.memoryCorrectionDao())

            assertEquals(original, reopenedEntries.findById(original.id))
            assertEquals(listOf(first, second, third), correctionRepository.getCorrectionHistory(original.id))
            assertEquals(original.rawText, reopenedEntries.findById(original.id)?.rawText)
            assertEquals(
                "Tercera revisión final.",
                correctionRepository.getCurrentVersion(original.id)?.text,
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun duplicateIdFailurePropagatesFromSqlite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "yezvi-room-f205-constraint-test.db"
        context.deleteDatabase(databaseName)
        val database = YezviDatabaseFactory.create(context, databaseName)
        val repository = RoomMemoryRepository(database.memoryEntryDao())
        val input = CaptureInput(
            rawText = "Entrada sintética para comprobar colisiones.",
            sourceType = CaptureSourceType.TEXT,
            capturedAtEpochMillis = 1_791_234_567_890L,
            timezoneId = "America/Lima",
        )

        try {
            repository.save(input, id = "duplicate-memory")

            try {
                repository.save(input.copy(rawText = "Segundo contenido sintético."), id = "duplicate-memory")
                throw AssertionError("La colisión de ID debe propagarse como error SQLite")
            } catch (_: SQLiteConstraintException) {
                // The repository preserves Room/SQLite's ABORT failure instead of reporting success.
            }
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
