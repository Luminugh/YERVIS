package com.yezvi.assistant.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** F1.12 — Verifica el helper de duración respetando el ajuste de movimiento reducido. */
class MotionSupportTest {

    @Test
    fun motionDurationMillisReturnsBaseWhenNotReduced() {
        assertEquals(200, motionDurationMillis(200, reduceMotion = false))
    }

    @Test
    fun motionDurationMillisReturnsZeroWhenReduced() {
        assertEquals(0, motionDurationMillis(200, reduceMotion = true))
    }
}
