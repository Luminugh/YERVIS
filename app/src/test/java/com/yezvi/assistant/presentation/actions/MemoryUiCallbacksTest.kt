package com.yezvi.assistant.presentation.actions

import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MemoryUiCallbacksTest {
    @Test
    fun captureRequestForwardsTheExistingCaptureInput() {
        val input = CaptureInput(
            rawText = "  Llamar a Ana\nantes de las 17:00  ",
            sourceType = CaptureSourceType.TEXT,
            capturedAtEpochMillis = 1_791_431_400_123L,
            timezoneId = "America/Lima",
        )
        var received: CaptureInput? = null
        val callbacks = MemoryUiCallbacks(
            onCaptureRequested = { received = it },
            onNavigateTo = {},
            onMemoryEntrySelected = {},
        )

        callbacks.onCaptureRequested(input)

        assertSame(input, received)
    }

    @Test
    fun navigationRequestForwardsTopLevelDestination() {
        var received: AppDestination? = null
        val callbacks = MemoryUiCallbacks(
            onCaptureRequested = {},
            onNavigateTo = { received = it },
            onMemoryEntrySelected = {},
        )

        callbacks.onNavigateTo(AppDestination.MEMORY)

        assertEquals(AppDestination.MEMORY, received)
    }

    @Test
    fun memorySelectionForwardsStableEntryId() {
        var receivedId: String? = null
        val callbacks = MemoryUiCallbacks(
            onCaptureRequested = {},
            onNavigateTo = {},
            onMemoryEntrySelected = { receivedId = it },
        )

        callbacks.onMemoryEntrySelected("memory-123")

        assertEquals("memory-123", receivedId)
    }
}
