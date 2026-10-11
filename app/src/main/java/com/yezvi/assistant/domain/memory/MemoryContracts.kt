package com.yezvi.assistant.domain.memory

/** The channel through which the original text entered YEZVI. */
enum class CaptureSourceType {
    TEXT,
    VOICE,
}

/** Lifecycle state for a capture while it is being accepted locally. */
enum class CaptureStatus {
    IDLE,
    SAVING,
    SAVED,
    FAILED,
}

/**
 * Text and capture context supplied to the memory layer before a memory entry is created.
 *
 * [rawText] is kept exactly as received. [capturedAtEpochMillis] is a UTC instant measured from
 * the Unix epoch; [timezoneId] records the IANA time-zone context in which it was captured.
 */
data class CaptureInput(
    val rawText: String,
    val sourceType: CaptureSourceType,
    val capturedAtEpochMillis: Long,
    val timezoneId: String,
) {
    init {
        require(rawText.isNotBlank()) { "rawText must not be blank" }
        require(timezoneId.isNotBlank()) { "timezoneId must not be blank" }
    }
}

/**
 * A memory record with a stable identifier and the original captured text.
 *
 * The identifier is assigned when the input becomes a record. This model does not prescribe its
 * format or generation mechanism.
 */
data class MemoryEntry(
    val id: String,
    val rawText: String,
    val sourceType: CaptureSourceType,
    val capturedAtEpochMillis: Long,
    val timezoneId: String,
    val status: CaptureStatus,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(rawText.isNotBlank()) { "rawText must not be blank" }
        require(timezoneId.isNotBlank()) { "timezoneId must not be blank" }
    }
}
