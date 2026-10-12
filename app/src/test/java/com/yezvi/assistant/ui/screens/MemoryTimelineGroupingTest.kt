package com.yezvi.assistant.ui.screens

import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryTimelineGroupingTest {
    @Test
    fun groupsByRecordedLocalDateAndOrdersByUtcThenId() {
        val latestUtc = entry(
            id = "a-latest",
            instant = "2026-10-12T01:00:00Z",
            timezone = "America/Los_Angeles",
        )
        val tiedIdHigh = latestUtc.copy(id = "z-tied")
        val earlierLocalTomorrow = entry(
            id = "b-earlier",
            instant = "2026-10-12T00:30:00Z",
            timezone = "Pacific/Kiritimati",
        )

        val groups = groupEntriesByDate(listOf(earlierLocalTomorrow, latestUtc, tiedIdHigh))

        assertEquals(listOf("2026-10-11", "2026-10-12"), groups.map { it.first })
        assertEquals(listOf("z-tied", "a-latest"), groups[0].second.map { it.id })
        assertEquals(listOf("b-earlier"), groups[1].second.map { it.id })
    }

    @Test
    fun splitsEntriesAtLocalMidnightUsingEachRecordedTimezone() {
        val justBeforeLimaMidnight = entry(
            id = "before-midnight",
            instant = "2026-10-12T04:59:59.999Z",
            timezone = "America/Lima",
        )
        val atLimaMidnight = entry(
            id = "at-midnight",
            instant = "2026-10-12T05:00:00Z",
            timezone = "America/Lima",
        )

        val groups = groupEntriesByDate(listOf(justBeforeLimaMidnight, atLimaMidnight))

        assertEquals(listOf("2026-10-12", "2026-10-11"), groups.map { it.first })
        assertEquals(listOf("at-midnight"), groups[0].second.map { it.id })
        assertEquals(listOf("before-midnight"), groups[1].second.map { it.id })
    }

    @Test
    fun sameUtcInstantCanBelongToDifferentRecordedLocalDates() {
        val lima = entry(
            id = "same-instant-lima",
            instant = "2026-10-12T04:30:00Z",
            timezone = "America/Lima",
        )
        val kiritimati = entry(
            id = "same-instant-kiritimati",
            instant = "2026-10-12T04:30:00Z",
            timezone = "Pacific/Kiritimati",
        )

        val groups = groupEntriesByDate(listOf(lima, kiritimati))

        assertEquals(listOf("2026-10-11", "2026-10-12"), groups.map { it.first })
        assertEquals(listOf("same-instant-lima"), groups[0].second.map { it.id })
        assertEquals(listOf("same-instant-kiritimati"), groups[1].second.map { it.id })
    }

    private fun entry(id: String, instant: String, timezone: String) = MemoryEntry(
        id = id,
        rawText = "Texto sintético $id",
        sourceType = CaptureSourceType.TEXT,
        capturedAtEpochMillis = Instant.parse(instant).toEpochMilli(),
        timezoneId = timezone,
        status = CaptureStatus.SAVED,
    )
}
