package com.yezvi.assistant.presentation.capture

/** Honest UI state for microphone access; permission alone never represents recording. */
internal enum class MicrophonePermissionStatus {
    NOT_REQUESTED,
    GRANTED,
    DENIED,
    REVOKED,
}

internal fun resolveMicrophonePermissionStatus(
    permissionGranted: Boolean,
    permissionRequested: Boolean,
    previouslyGranted: Boolean,
): MicrophonePermissionStatus = when {
    permissionGranted -> MicrophonePermissionStatus.GRANTED
    !permissionRequested -> MicrophonePermissionStatus.NOT_REQUESTED
    previouslyGranted -> MicrophonePermissionStatus.REVOKED
    else -> MicrophonePermissionStatus.DENIED
}

/** Whether to offer an optional settings shortcut after the system stops showing rationale. */
internal fun shouldOfferMicrophoneSettings(
    permissionRequested: Boolean,
    permissionGranted: Boolean,
    shouldShowRationale: Boolean,
): Boolean = permissionRequested && !permissionGranted && !shouldShowRationale
