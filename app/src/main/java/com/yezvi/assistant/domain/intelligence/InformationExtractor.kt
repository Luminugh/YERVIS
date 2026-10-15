package com.yezvi.assistant.domain.intelligence

/** Provider-independent extraction boundary. It neither accepts proposals nor persists them. */
fun interface InformationExtractor {
    /** Cancellation must propagate; returned JSON still requires F4.04/F4.05 validation. */
    suspend fun extract(request: InformationExtractionRequest): InformationExtractionResult
}

object InformationProposalSchema {
    const val VERSION = "1.0.0"
}

/** Protocol outcomes from the existing JSON schema, not information categories. */
enum class ExtractionOutcome { COMPLETE, PARTIAL, NO_PROPOSALS }

/** Host-side provenance; it is not an extra field in the proposal JSON schema. */
enum class ExtractionMode { SYNTHETIC_FIXTURE, MODEL_OUTPUT }

enum class ExtractionError {
    UNSUPPORTED_SCHEMA_VERSION,
    NO_MATCHING_FIXTURE,
    SIMULATED_FAILURE,
}

/** The canonical structured wire document, deliberately opaque until F4.04 parses/validates it. */
@ConsistentCopyVisibility
data class UnvalidatedProposalDocument internal constructor(
    val schemaVersion: String,
    val outcome: ExtractionOutcome,
    val json: String,
) {
    override fun toString(): String = "UnvalidatedProposalDocument(schemaVersion=$schemaVersion, outcome=$outcome)"
}

sealed interface InformationExtractionResult {
    val mode: ExtractionMode

    data class Proposals(
        val document: UnvalidatedProposalDocument,
        override val mode: ExtractionMode,
    ) : InformationExtractionResult {
        init { require(document.outcome != ExtractionOutcome.NO_PROPOSALS) }
    }

    data class NoProposals(
        val document: UnvalidatedProposalDocument,
        override val mode: ExtractionMode,
    ) : InformationExtractionResult {
        init { require(document.outcome == ExtractionOutcome.NO_PROPOSALS) }
    }

    data class Failure(
        val error: ExtractionError,
        override val mode: ExtractionMode,
    ) : InformationExtractionResult
}
