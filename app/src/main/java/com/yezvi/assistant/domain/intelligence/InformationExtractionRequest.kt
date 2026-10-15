package com.yezvi.assistant.domain.intelligence

import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryTextVersion
import java.security.MessageDigest
import java.util.Collections

/** Absolute Kotlin String indices: inclusive start, exclusive end, measured in UTF-16 units. */
data class Utf16TextRange(val startUtf16: Int, val endUtf16: Int) {
    init { require(startUtf16 >= 0 && endUtf16 > startUtf16) }
}

sealed interface SourceTextRevision {
    data object Original : SourceTextRevision
    data class Correction(val correctionId: Long) : SourceTextRevision {
        init { require(correctionId > 0L) }
    }
}

/** Longs remain Long in Kotlin; the JSON transport represents them as canonical decimal strings. */
@ConsistentCopyVisibility
data class ExtractionSourceSnapshot internal constructor(
    val sourceEntryId: String,
    val revision: SourceTextRevision,
    val sourceType: CaptureSourceType,
    val capturedAtEpochMillis: Long,
    val timezoneId: String,
    val textSha256: String,
    val textLengthUtf16: Int,
)

@ConsistentCopyVisibility
data class SelectedTextFragment internal constructor(val range: Utf16TextRange, val text: String) {
    override fun toString(): String = "SelectedTextFragment(range=$range)"
}

/** Immutable host context. Only explicitly selected fragments are retained, not other raw text. */
class InformationExtractionSource private constructor(
    val snapshot: ExtractionSourceSnapshot,
    fragments: List<SelectedTextFragment>,
) {
    val fragments: List<SelectedTextFragment> = Collections.unmodifiableList(ArrayList(fragments))

    override fun equals(other: Any?): Boolean = other is InformationExtractionSource &&
        snapshot == other.snapshot && fragments == other.fragments

    override fun hashCode(): Int = 31 * snapshot.hashCode() + fragments.hashCode()

    override fun toString(): String = "InformationExtractionSource(fragmentCount=${fragments.size})"

    companion object {
        /**
         * Prepares selected text from real domain types; does not read Room or grant cloud consent.
         * Invalid host selections are programming errors, not model output validation (F4.04).
         */
        fun from(
            entry: MemoryEntry,
            version: MemoryTextVersion? = null,
            ranges: List<Utf16TextRange>? = null,
        ): InformationExtractionSource {
            require(entry.id.codePointCount(0, entry.id.length) <= 128) { "Source identifier exceeds the schema budget" }
            require(entry.timezoneId.length <= 128 && entry.timezoneId.matches(Regex("[A-Za-z0-9_+/-]+"))) {
                "Source zone does not fit the wire representation"
            }
            if (version != null) {
                require(version.memoryEntryId == entry.id) { "Text version belongs to another entry" }
                require(version.isCorrection || version.text == entry.rawText) { "Original version must match rawText" }
            }
            val text = version?.text ?: entry.rawText
            require(text.length in 1..8192) { "Source text exceeds the extraction budget" }
            val selected = ranges?.toList() ?: listOf(Utf16TextRange(0, text.length))
            require(selected.size in 1..8) { "Select one to eight text windows" }
            var previousEnd = 0
            val fragments = selected.map { range ->
                require(range.startUtf16 >= previousEnd && range.endUtf16 <= text.length) {
                    "Source windows must be ordered, disjoint and within the selected revision"
                }
                require(!splitsSurrogate(text, range.startUtf16) && !splitsSurrogate(text, range.endUtf16)) {
                    "Source window splits a UTF-16 surrogate pair"
                }
                previousEnd = range.endUtf16
                SelectedTextFragment(range, text.substring(range.startUtf16, range.endUtf16))
            }
            val revision = version?.correctionId?.let { SourceTextRevision.Correction(it) } ?: SourceTextRevision.Original
            val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return InformationExtractionSource(
                ExtractionSourceSnapshot(entry.id, revision, entry.sourceType, entry.capturedAtEpochMillis,
                    entry.timezoneId, hash, text.length),
                fragments,
            )
        }

        private fun splitsSurrogate(text: String, position: Int): Boolean =
            position > 0 && position < text.length && text[position - 1].isHighSurrogate() && text[position].isLowSurrogate()
    }
}

/** Immutable request; constructing it is not consent to transmit it to any service. */
class InformationExtractionRequest(
    val requestId: String,
    val extractionId: String,
    sources: List<InformationExtractionSource>,
    val schemaVersion: String = InformationProposalSchema.VERSION,
) {
    val sources: List<InformationExtractionSource> = Collections.unmodifiableList(ArrayList(sources))

    init {
        val idPattern = Regex("[a-z][a-z0-9_-]{0,63}")
        require(requestId.matches(idPattern) && extractionId.matches(idPattern)) { "Invalid extraction correlation IDs" }
        require(this.sources.size in 1..16) { "Select one to sixteen source revisions" }
        require(this.sources.sumOf { source -> source.fragments.sumOf { it.text.length } } <= 32768) {
            "Selected text exceeds the request budget"
        }
        require(this.sources.map { it.snapshot.sourceEntryId to it.snapshot.revision }.distinct().size == this.sources.size) {
            "Source revisions must be unique"
        }
    }

    override fun equals(other: Any?): Boolean = other is InformationExtractionRequest &&
        requestId == other.requestId && extractionId == other.extractionId &&
        schemaVersion == other.schemaVersion && sources == other.sources

    override fun hashCode(): Int = arrayOf(requestId, extractionId, schemaVersion, sources).contentHashCode()

    override fun toString(): String = "InformationExtractionRequest(schemaVersion=$schemaVersion, sourceCount=${sources.size})"
}
