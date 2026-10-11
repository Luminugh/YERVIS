package com.yezvi.assistant.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryContractsTest {
    @Test
    fun captureInputPreservesOriginalTextAndCaptureContext() {
        val originalText = "  Reunión el viernes\ntraer el informe  "
        val capturedAt = 1_791_431_400_123L

        val input = CaptureInput(
            rawText = originalText,
            sourceType = CaptureSourceType.TEXT,
            capturedAtEpochMillis = capturedAt,
            timezoneId = "America/Lima",
        )

        assertEquals(originalText, input.rawText)
        assertEquals(CaptureSourceType.TEXT, input.sourceType)
        assertEquals(capturedAt, input.capturedAtEpochMillis)
        assertEquals("America/Lima", input.timezoneId)
    }

    @Test
    fun memoryEntryCarriesStableIdAndCaptureStatus() {
        val entry = MemoryEntry(
            id = "memory-123",
            rawText = "Llamar a Ana",
            sourceType = CaptureSourceType.VOICE,
            capturedAtEpochMillis = 1_791_431_400_123L,
            timezoneId = "America/Lima",
            status = CaptureStatus.SAVED,
        )

        assertEquals("memory-123", entry.id)
        assertEquals("Llamar a Ana", entry.rawText)
        assertEquals(CaptureSourceType.VOICE, entry.sourceType)
        assertEquals(1_791_431_400_123L, entry.capturedAtEpochMillis)
        assertEquals("America/Lima", entry.timezoneId)
        assertEquals(CaptureStatus.SAVED, entry.status)
    }

    @Test
    fun captureContractsRejectBlankOriginalText() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureInput(
                rawText = " \n ",
                sourceType = CaptureSourceType.TEXT,
                capturedAtEpochMillis = 1_791_431_400_123L,
                timezoneId = "America/Lima",
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            MemoryEntry(
                id = "memory-123",
                rawText = " \n ",
                sourceType = CaptureSourceType.TEXT,
                capturedAtEpochMillis = 1_791_431_400_123L,
                timezoneId = "America/Lima",
                status = CaptureStatus.SAVING,
            )
        }
    }

    @Test
    fun memoryEntryRejectsBlankIdentifier() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryEntry(
                id = " ",
                rawText = "Texto original",
                sourceType = CaptureSourceType.TEXT,
                capturedAtEpochMillis = 1_791_431_400_123L,
                timezoneId = "America/Lima",
                status = CaptureStatus.SAVING,
            )
        }
    }

    @Test
    fun captureContractsRejectBlankTimezoneId() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureInput(
                rawText = "Texto original",
                sourceType = CaptureSourceType.TEXT,
                capturedAtEpochMillis = 1_791_431_400_123L,
                timezoneId = " ",
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            MemoryEntry(
                id = "memory-123",
                rawText = "Texto original",
                sourceType = CaptureSourceType.TEXT,
                capturedAtEpochMillis = 1_791_431_400_123L,
                timezoneId = " ",
                status = CaptureStatus.SAVED,
            )
        }
    }
}
