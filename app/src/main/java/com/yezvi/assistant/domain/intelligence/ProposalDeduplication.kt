package com.yezvi.assistant.domain.intelligence

data class ProposalVersionReference(val identity: ProposalIdentity, val version: Int) {
    init { require(version > 0) }
}

enum class ProposalComparisonKind {
    TECHNICAL_DUPLICATE, POSSIBLE_EQUIVALENT, DISTINCT, UPDATE_PROPOSED,
    EXCEPTION_PROPOSED, RESCHEDULE_PROPOSED, CONFLICT_REQUIRES_REVIEW,
}

enum class ProposalDeduplicationRejection {
    INVALID_DOCUMENT, SYNTHETIC_FIXTURE, SOURCE_CHANGED, HASH_COLLISION,
    IDENTITY_CONFLICT, STALE_VERSION, INVALID_REFERENCE, UNSUPPORTED_CANONICALIZATION,
    TRANSACTION_CONFLICT, STORAGE_FAILURE,
}

/** One receipt per incoming proposal, including duplicate members of a mixed batch. */
data class ProposalDeduplicationReceipt(
    val incoming: ProposalIdentity,
    val stored: ProposalVersionReference,
    val technicalDuplicate: Boolean,
)

sealed interface ProposalDeduplicationResult {
    data class Committed(val receipts: List<ProposalDeduplicationReceipt>) : ProposalDeduplicationResult
    data class Rejected(val reason: ProposalDeduplicationRejection) : ProposalDeduplicationResult
}

data class ProposalBackfillReport(val indexed: Int, val alreadyCovered: Int, val unindexable: Int)

sealed interface ProposalRelationResult {
    data class Evaluated(
        val kind: ProposalComparisonKind,
        val relationId: String?,
        val alreadyRecorded: Boolean,
    ) : ProposalRelationResult
    data class Rejected(val reason: ProposalDeduplicationRejection) : ProposalRelationResult
}
