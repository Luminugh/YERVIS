package com.yezvi.assistant.data.local.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YezviDatabaseTest {
    @Test
    fun databasePerformsCrudAndPersistsAfterReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "yezvi-room-f204-test.db"
        context.deleteDatabase(databaseName)

        var database = YezviDatabaseFactory.create(context, databaseName)
        try {
            val dao = database.memoryEntryDao()
            val capturedAt = 1_791_234_567_890L
            val firstEntry = MemoryEntryEntity(
                id = "synthetic-memory-a",
                rawText = "Texto sintético original A; no contiene datos personales.",
                capturedAtEpochMillis = capturedAt,
                timezoneId = "America/Lima",
                sourceType = com.yezvi.assistant.domain.memory.CaptureSourceType.TEXT,
            )
            val secondEntry = firstEntry.copy(
                id = "synthetic-memory-b",
                rawText = "Texto sintético original B; no contiene datos personales.",
            )

            dao.insert(firstEntry)
            dao.insert(secondEntry)

            assertEquals(firstEntry, dao.findById(firstEntry.id))
            assertEquals(
                listOf(secondEntry, firstEntry),
                dao.observeAll().first(),
            )

            assertEquals(firstEntry, dao.findById(firstEntry.id))

            database.close()
            database = YezviDatabaseFactory.create(context, databaseName)
            val reopenedDao = database.memoryEntryDao()
            assertEquals(firstEntry, reopenedDao.findById(firstEntry.id))
            assertEquals(secondEntry, reopenedDao.findById(secondEntry.id))

            assertEquals(1, reopenedDao.deleteById(secondEntry.id))
            assertNull(reopenedDao.findById(secondEntry.id))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
