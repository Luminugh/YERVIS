package com.yezvi.assistant.presentation.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophonePermissionUiStateTest {
    @Test
    fun resolvesNotRequestedGrantedDeniedAndRevokedStates() {
        assertEquals(
            MicrophonePermissionStatus.NOT_REQUESTED,
            resolveMicrophonePermissionStatus(false, false, false),
        )
        assertEquals(
            MicrophonePermissionStatus.GRANTED,
            resolveMicrophonePermissionStatus(true, true, true),
        )
        assertEquals(
            MicrophonePermissionStatus.DENIED,
            resolveMicrophonePermissionStatus(false, true, false),
        )
        assertEquals(
            MicrophonePermissionStatus.REVOKED,
            resolveMicrophonePermissionStatus(false, true, true),
        )
        assertEquals(
            MicrophonePermissionStatus.NOT_REQUESTED,
            resolveMicrophonePermissionStatus(false, false, true),
        )
    }

    @Test
    fun settingsShortcutAppearsOnlyAfterRequestWithoutRationale() {
        assertFalse(shouldOfferMicrophoneSettings(false, false, false))
        assertFalse(shouldOfferMicrophoneSettings(true, false, true))
        assertFalse(shouldOfferMicrophoneSettings(true, true, false))
        assertTrue(shouldOfferMicrophoneSettings(true, false, false))
    }
}
