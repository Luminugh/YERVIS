package com.yezvi.assistant.domain.intelligence

/** Explicit local review action. EXPIRE/REACTIVATE reuse the transitions already defined by F4.06. */
enum class PersistedProposalAction { CONFIRM, REJECT, EXPIRE, REACTIVATE }

enum class ProposalPersistenceRejection {
    SYNTHETIC_FIXTURE,
    INVALID_DOCUMENT,
    PROPOSAL_NOT_FOUND,
    SOURCE_CHANGED,
    INVALID_STATE,
    ACTION_ID_REUSED,
    VERSION_CHANGED,
    DOCUMENT_NOT_EDITABLE,
    NO_CHANGE,
    STORED_DOCUMENT_INVALID,
}

sealed interface ProposalPersistenceResult {
    data class Applied(
        val identity: ProposalIdentity,
        val version: Int,
        val state: ProposalReviewState,
    ) : ProposalPersistenceResult

    data class AlreadyApplied(
        val identity: ProposalIdentity,
        val version: Int,
        val state: ProposalReviewState,
    ) : ProposalPersistenceResult

    data class BatchApplied(val identities: List<ProposalIdentity>) : ProposalPersistenceResult

    data class BatchAlreadyApplied(val identities: List<ProposalIdentity>) : ProposalPersistenceResult

    data class Rejected(val reason: ProposalPersistenceRejection) : ProposalPersistenceResult
}

enum class PersistedProposalVersionAuthor { HOST, USER }

data class PersistedProposalVersionSummary(
    val version: Int,
    val author: PersistedProposalVersionAuthor,
    val actionId: String?,
    val createdAtEpochMillis: Long,
)

data class PersistedProposalDecision(
    val actionId: String,
    val action: ProposalTransitionAction,
    val actor: ProposalTransitionActor,
    val from: ProposalReviewState,
    val to: ProposalReviewState,
    val version: Int,
    val occurredAtEpochMillis: Long,
)

/** Contains source text only through the validated request, never as a second persisted copy. */
class PersistedProposalReview internal constructor(
    val identity: ProposalIdentity,
    val currentVersion: Int,
    val state: ProposalReviewState,
    val request: InformationExtractionRequest,
    val document: ValidatedProposalDocument,
    val temporal: TemporalResolutionResult.Completed,
    val sourcesAreCurrent: Boolean,
    versions: List<PersistedProposalVersionSummary>,
    decisions: List<PersistedProposalDecision>,
) {
    val versions: List<PersistedProposalVersionSummary> = versions.toList()
    val decisions: List<PersistedProposalDecision> = decisions.toList()

    override fun toString(): String =
        "PersistedProposalReview(identity=$identity, currentVersion=$currentVersion, state=$state, " +
            "sourceCount=${request.sources.size}, versions=${versions.size}, decisions=${decisions.size})"
}

sealed interface ProposalReviewLoadResult {
    data class Found(val review: PersistedProposalReview) : ProposalReviewLoadResult
    data object Missing : ProposalReviewLoadResult
    data class Rejected(val reason: ProposalPersistenceRejection) : ProposalReviewLoadResult
}

/** Provider-independent write boundary. Implementations must revalidate before persistence. */
interface InformationProposalRepository {
    suspend fun submitForReview(
        request: InformationExtractionRequest,
        extraction: InformationExtractionResult.Proposals,
        submittedAtEpochMillis: Long,
    ): ProposalPersistenceResult

    /** Appends a user-authored schema 1.0.0 version while the proposal remains PROPOSED. */
    suspend fun appendUserRevision(
        identity: ProposalIdentity,
        request: InformationExtractionRequest,
        editedDocument: UnvalidatedProposalDocument,
        explicitAction: ExplicitUserReviewAction,
        editedAtEpochMillis: Long,
    ): ProposalPersistenceResult

    suspend fun transition(
        identity: ProposalIdentity,
        request: InformationExtractionRequest,
        currentDocument: UnvalidatedProposalDocument,
        action: PersistedProposalAction,
        explicitAction: ExplicitUserReviewAction,
        occurredAtEpochMillis: Long,
    ): ProposalPersistenceResult

    suspend fun find(identity: ProposalIdentity): ProposalReviewLoadResult
}
