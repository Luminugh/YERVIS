package com.yezvi.assistant.data.demo

import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry

/** Synthetic, deterministic records for previews and demo screens. Never represents persisted data. */
internal object DemoMemoryFixtures {
    val entries: List<MemoryEntry> = listOf(
        MemoryEntry(
            id = "demo-2026-10-11-001",
            rawText = "Ejemplo: revisar el resumen de mañana.",
            sourceType = CaptureSourceType.TEXT,
            capturedAtEpochMillis = 1_791_731_700_000L, // 2026-10-11 10:15 in America/Lima
            timezoneId = "America/Lima",
            status = CaptureStatus.IDLE,
        ),
        MemoryEntry(
            id = "demo-2026-10-11-002",
            rawText = "Ejemplo: probar una captura de voz sin conexión.",
            sourceType = CaptureSourceType.VOICE,
            capturedAtEpochMillis = 1_791_723_900_000L, // 2026-10-11 08:05 in America/Lima
            timezoneId = "America/Lima",
            status = CaptureStatus.IDLE,
        ),
        MemoryEntry(
            id = "demo-2026-10-10-001",
            rawText = "Ejemplo: cita el jueves a las 18:30.",
            sourceType = CaptureSourceType.TEXT,
            capturedAtEpochMillis = 1_791_668_400_000L, // 2026-10-10 16:40 in America/Lima
            timezoneId = "America/Lima",
            status = CaptureStatus.IDLE,
        ),
        MemoryEntry(
            id = "demo-2026-10-09-001",
            rawText = "Ejemplo: llevar una libreta al taller.",
            sourceType = CaptureSourceType.VOICE,
            capturedAtEpochMillis = 1_791_588_600_000L, // 2026-10-09 18:30 in America/Lima
            timezoneId = "America/Lima",
            status = CaptureStatus.IDLE,
        ),
    )
}
