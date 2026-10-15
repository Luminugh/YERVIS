package com.yezvi.assistant.data.local.memory

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/** Current review-state projection. The append-only decision rows remain the audit source. */
@Entity(
    tableName = "information_proposals",
    primaryKeys = ["extractionId", "proposalId"],
    indices = [Index(value = ["reviewState", "updatedAtEpochMillis"])],
)
data class InformationProposalEntity(
    val extractionId: String,
    val proposalId: String,
    val requestId: String,
    val currentVersion: Int,
    val reviewState: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** A complete, previously validated schema document is retained for every proposal revision. */
@Entity(
    tableName = "information_proposal_versions",
    primaryKeys = ["extractionId", "proposalId", "version"],
    foreignKeys = [
        ForeignKey(
            entity = InformationProposalEntity::class,
            parentColumns = ["extractionId", "proposalId"],
            childColumns = ["extractionId", "proposalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["extractionId", "proposalId", "actionId"], unique = true),
    ],
)
data class InformationProposalVersionEntity(
    val extractionId: String,
    val proposalId: String,
    val version: Int,
    val requestId: String,
    val schemaVersion: String,
    val outcome: String,
    val payloadJson: String,
    val payloadSha256: String,
    /** HOST for a checked extraction version; USER for a version created by explicit editing. */
    val createdBy: String,
    /** Null only for initial HOST versions. USER edits use this as an idempotency key. */
    val actionId: String?,
    val createdAtEpochMillis: Long,
)

/** Source snapshot and authorized UTF-16 windows for one immutable proposal version. */
@Entity(
    tableName = "information_proposal_sources",
    primaryKeys = [
        "extractionId", "proposalId", "version", "sourceEntryId", "revisionKind", "revisionId",
    ],
    foreignKeys = [
        ForeignKey(
            entity = InformationProposalVersionEntity::class,
            parentColumns = ["extractionId", "proposalId", "version"],
            childColumns = ["extractionId", "proposalId", "version"],
            onDelete = ForeignKey.CASCADE,
        ),
        // RESTRICT makes bypassing the repository fail closed instead of leaving a proposal without sources.
        ForeignKey(
            entity = MemoryEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceEntryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["extractionId", "proposalId", "version"]),
        Index(value = ["sourceEntryId"]),
    ],
)
data class InformationProposalSourceEntity(
    val extractionId: String,
    val proposalId: String,
    val version: Int,
    val sourceEntryId: String,
    val revisionKind: String,
    /** Empty for ORIGINAL; decimal correctionId for CORRECTION, never nullable in the primary key. */
    val revisionId: String,
    val sourceType: String,
    val capturedAtEpochMillis: Long,
    val timezoneId: String,
    val textSha256: String,
    val textLengthUtf16: Int,
    /** Compact JSON of [start,end) pairs; no source text is copied into this table. */
    val authorizedRangesJson: String,
)

/** Append-only F4.06 transitions, each bound to the exact proposal version it reviewed. */
@Entity(
    tableName = "information_proposal_decisions",
    primaryKeys = ["extractionId", "proposalId", "actionId"],
    foreignKeys = [
        ForeignKey(
            entity = InformationProposalEntity::class,
            parentColumns = ["extractionId", "proposalId"],
            childColumns = ["extractionId", "proposalId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = InformationProposalVersionEntity::class,
            parentColumns = ["extractionId", "proposalId", "version"],
            childColumns = ["extractionId", "proposalId", "version"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["extractionId", "proposalId", "version"]),
        Index(value = ["extractionId", "proposalId", "occurredAtEpochMillis"]),
        Index(value = ["extractionId", "proposalId", "sequence"], unique = true),
    ],
)
data class InformationProposalDecisionEntity(
    val extractionId: String,
    val proposalId: String,
    val actionId: String,
    val sequence: Int,
    val version: Int,
    val action: String,
    val actor: String,
    val fromState: String,
    val toState: String,
    val occurredAtEpochMillis: Long,
)
