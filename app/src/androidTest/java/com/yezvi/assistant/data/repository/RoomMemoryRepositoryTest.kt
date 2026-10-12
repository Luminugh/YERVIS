package com.yezvi.assistant.data.repository

import android.database.sqlite.SQLiteConstraintException
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMemoryRepositoryTest {
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

            val updated = first.copy(
                rawText = "Texto original actualizado sintéticamente.\n",
                capturedAtEpochMillis = first.capturedAtEpochMillis + 1_000L,
                timezoneId = "Pacific/Auckland",
            )
            assertTrue(repository.update(updated))
            assertEquals(listOf(updated, second), emissions.receive())
            assertEquals(updated, repository.findById(updated.id))
            assertFalse(repository.update(updated.copy(id = "missing-memory")))

            assertTrue(repository.deleteById(second.id))
            assertEquals(listOf(updated), emissions.receive())
            assertFalse(repository.deleteById(second.id))
            assertNull(repository.findById(second.id))
        } finally {
            observer.cancelAndJoin()
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
