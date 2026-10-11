package com.yezvi.assistant.data.demo

import com.yezvi.assistant.domain.memory.CaptureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DemoMemoryRepositoryTest {
    @Test
    fun fixturesHaveStableDemoIdsAndCoherentLocalDates() {
        val entries = DemoMemoryRepository.getEntries()

        assertEquals(
            listOf(
                "demo-2026-10-11-001",
                "demo-2026-10-11-002",
                "demo-2026-10-10-001",
                "demo-2026-10-09-001",
            ),
            entries.map { it.id },
        )
        assertTrue(entries.all { it.id.startsWith("demo-") })
        assertTrue(entries.all { it.status == CaptureStatus.IDLE })
        assertEquals(
            listOf("2026-10-11", "2026-10-11", "2026-10-10", "2026-10-09"),
            entries.map { entry ->
                Instant.ofEpochMilli(entry.capturedAtEpochMillis)
                    .atZone(ZoneId.of(entry.timezoneId))
                    .toLocalDate()
                    .toString()
            },
        )
        assertTrue(entries.zipWithNext().all { (newer, older) ->
            newer.capturedAtEpochMillis >= older.capturedAtEpochMillis
        })
    }

    @Test
    fun repositoryReturnsFixturesAndFindsAnEntryByStableId() {
        val entries = DemoMemoryRepository.getEntries()

        assertEquals(DemoMemoryFixtures.entries, entries)
        assertEquals(entries[1], DemoMemoryRepository.getEntry("demo-2026-10-11-002"))
    }

    @Test
    fun repositoryReturnsNullForUnknownId() {
        assertNull(DemoMemoryRepository.getEntry("missing-entry"))
    }
}
