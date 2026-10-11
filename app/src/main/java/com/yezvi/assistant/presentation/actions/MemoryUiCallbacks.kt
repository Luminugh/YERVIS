package com.yezvi.assistant.presentation.actions

import com.yezvi.assistant.domain.memory.CaptureInput

/** Initial top-level destinations requested by the UI. */
enum class AppDestination {
    TODAY,
    MEMORY,
    CAPTURE,
    SETTINGS,
}

/**
 * UI requests handled by the presentation or app host.
 *
 * These callbacks describe intent only; they do not implement navigation or persistence.
 */
class MemoryUiCallbacks(
    val onCaptureRequested: (CaptureInput) -> Unit,
    val onNavigateTo: (AppDestination) -> Unit,
    val onMemoryEntrySelected: (String) -> Unit,
)
