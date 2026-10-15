package com.yezvi.assistant.domain.intelligence

/** Explicit trust boundary after extraction. Validation grants no persistence or action authority. */
fun interface InformationProposalValidator {
    fun validate(request: InformationExtractionRequest, document: UnvalidatedProposalDocument): ProposalValidationResult
}

enum class ProposalRejectionCode {
    PAYLOAD_LIMIT, PROCESSING_LIMIT, MALFORMED_JSON, DUPLICATE_KEY, INVALID_UNICODE,
    UNSUPPORTED_VERSION, SCHEMA_CONFIGURATION, STRUCTURE, CORRELATION, DOCUMENT_METADATA,
    INT64_RANGE, SOURCE_NOT_AUTHORIZED, REVISION_MISMATCH, SNAPSHOT_MISMATCH,
    EVIDENCE_LIMIT, EVIDENCE_RANGE, EVIDENCE_QUOTE, DUPLICATE_ID, INVALID_REFERENCE,
    REFERENCE_TYPE, REFERENCE_CYCLE, DUPLICATE_RECURRENCE, INVALID_RECURRENCE,
    INVALID_ZONE, INVALID_DATE, TEMPORAL_INCONSISTENCY, PERIOD_ORDER,
    ASSESSMENT_CONFLICT, ACTIVITY_CONFLICT, OBSERVATION_SUPPORT, ATTRIBUTION_SUPPORT,
    REMINDER_NOT_EXPLICIT,
}

/** Paths contain schema-owned names/array indices only, never quotes, values or unknown keys. */
data class ProposalValidationError(val code: ProposalRejectionCode, val path: String, val characterOffset: Int? = null)

enum class ProposalReviewRequirement {
    HUMAN_MEANING_REVIEW, TEMPORAL_RESOLUTION, INFERRED_CLAIM, UNCERTAIN_CLAIM,
    OBSERVED_PATTERN, PERSONAL_CLAIM, CONTRADICTION, TECHNICAL_SCORE_NOT_TRUSTED,
    LINGUISTIC_SUPPORT_UNDECIDED, RECURRENCE_REVIEW,
}

/** A checked exchange document, still PROPOSED/UNVALIDATED in the protocol, never accepted facts. */
class ValidatedProposalDocument internal constructor(
    val json: String,
    val outcome: ExtractionOutcome,
    val proposalCount: Int,
) {
    val requiresHumanReview: Boolean get() = true
    override fun toString(): String = "ValidatedProposalDocument(outcome=$outcome, proposalCount=$proposalCount, requiresHumanReview=true)"
}

sealed interface ProposalValidationResult {
    data class Valid(val document: ValidatedProposalDocument, val reviewRequirements: Set<ProposalReviewRequirement>) : ProposalValidationResult
    data class Rejected(val error: ProposalValidationError) : ProposalValidationResult
}
