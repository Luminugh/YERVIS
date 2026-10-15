package com.yezvi.assistant.domain.intelligence

/** Resolution is derived alongside the exchange document; it never edits or accepts a proposal. */
enum class TemporalResolutionStatus { RESOLVED, PARTIALLY_RESOLVED, AMBIGUOUS, INVALID }

enum class TemporalResolutionReason {
    RELATIVE_DATE, EXPLICIT_DATE, EXPLICIT_INTERVAL, EXPLICIT_TIME, DAY_PART,
    YEAR_NOT_STATED, WEEKDAY_NEEDS_REFERENCE, NEXT_WEEKDAY_NEEDS_POLICY,
    MONTH_BOUNDARY_UNDEFINED, WEEK_PERIOD_UNDEFINED, AMBIGUOUS_HOUR,
    DAY_PART_HAS_NO_CLOCK_TIME, CONFLICTING_EVIDENCE, EXPRESSION_NOT_LOCATED,
    UNSUPPORTED_EXPRESSION, INVALID_SOURCE_ZONE, INVALID_DATE,
    INVALID_LOCAL_TIME, DST_GAP, DST_OVERLAP, INTERVAL_ORDER, PROPOSED_RESOLUTION_CONFLICT,
}

/** Exact validated source evidence. Ranges are absolute UTF-16 offsets in the named revision. */
data class TemporalEvidenceReference(
    val sourceEntryId: String,
    val revision: SourceTextRevision,
    val quote: String,
    val range: Utf16TextRange,
)

/** Typed, read-only interpretation for one temporal object in schema 1.0.0. */
data class TemporalResolution(
    val proposalId: String,
    val category: String,
    /** JSON Pointer into the unchanged schema document; the path retains the temporal role. */
    val jsonPointer: String,
    val originalExpression: String,
    val evidence: List<TemporalEvidenceReference>,
    val anchorSourceEntryId: String,
    val anchorRevision: SourceTextRevision,
    val capturedAtEpochMillis: Long,
    val captureTimezoneId: String,
    val status: TemporalResolutionStatus,
    val reason: TemporalResolutionReason,
    val localDate: String? = null,
    val endLocalDate: String? = null,
    val monthDayWithoutYear: String? = null,
    val localTime: String? = null,
    val endLocalTime: String? = null,
    val dayPart: String? = null,
    /** Explicit zone if stated, otherwise the original capture zone used as context. */
    val resolvedTimezoneId: String? = null,
    /** Only present when date + time + zone identify exactly one valid instant. */
    val instantEpochMillis: Long? = null,
    /** True when a pre-filled model resolution disagrees with recomputation from source evidence. */
    val proposedResolutionConflict: Boolean = false,
    /** Values echoed from the unchanged schema document; they are not trusted as computed output. */
    val reportedPrecision: String = "UNSPECIFIED",
    val reportedUncertainty: String = "UNRESOLVED",
)

sealed interface TemporalResolutionResult {
    data class Completed(
        val validatedDocument: ValidatedProposalDocument,
        val resolutions: List<TemporalResolution>,
    ) : TemporalResolutionResult

    data class Rejected(val error: ProposalValidationError) : TemporalResolutionResult
}

/** Revalidates against the supplied authorized source window before deriving any temporal value. */
fun interface TemporalExpressionResolver {
    fun resolve(request: InformationExtractionRequest, document: UnvalidatedProposalDocument): TemporalResolutionResult
}
