package com.yezvi.assistant.data.local.memory

import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryEntryMappersTest {
    @Test
    fun `entity mapping preserves original text and metadata`() {
        val input = CaptureInput(
            rawText = "  Revisar contrato con el equipo.\nNo resumir ni corregir.  ",
            sourceType = CaptureSourceType.VOICE,
            capturedAtEpochMillis = 1_791_234_567_890L,
            timezoneId = "America/Lima",
        )

        val entity = input.toMemoryEntryEntity(id = "entry-01")
        val entry = entity.toMemoryEntry()

        assertEquals("entry-01", entry.id)
        assertEquals(input.rawText, entry.rawText)
        assertEquals(input.sourceType, entry.sourceType)
        assertEquals(input.capturedAtEpochMillis, entry.capturedAtEpochMillis)
        assertEquals(input.timezoneId, entry.timezoneId)
        assertEquals(CaptureStatus.SAVED, entry.status)
    }
}
