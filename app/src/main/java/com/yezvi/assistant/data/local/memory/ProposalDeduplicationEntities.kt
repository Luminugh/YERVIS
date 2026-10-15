package com.yezvi.assistant.data.local.memory

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "information_proposal_dedup_keys",
    primaryKeys = ["canonicalizationVersion", "digest"],
    foreignKeys = [ForeignKey(
        entity = InformationProposalVersionEntity::class,
        parentColumns = ["extractionId", "proposalId", "version"],
        childColumns = ["extractionId", "proposalId", "version"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["extractionId", "proposalId", "version", "canonicalizationVersion"], unique = true)],
)
data class InformationProposalDedupKeyEntity(
    val canonicalizationVersion: Int,
    val digest: String,
    val descriptor: String,
    val extractionId: String,
    val proposalId: String,
    val version: Int,
) {
    override fun toString() = "InformationProposalDedupKeyEntity(canonicalizationVersion=$canonicalizationVersion)"
}

@Entity(
    tableName = "information_proposal_relations",
    primaryKeys = ["relationId"],
    foreignKeys = [
        ForeignKey(
            entity = InformationProposalVersionEntity::class,
            parentColumns = ["extractionId", "proposalId", "version"],
            childColumns = ["fromExtractionId", "fromProposalId", "fromVersion"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = InformationProposalVersionEntity::class,
            parentColumns = ["extractionId", "proposalId", "version"],
            childColumns = ["toExtractionId", "toProposalId", "toVersion"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["fromExtractionId", "fromProposalId", "fromVersion"]),
        Index(value = ["toExtractionId", "toProposalId", "toVersion"]),
        Index(value = ["comparatorVersion", "kind", "fromExtractionId", "fromProposalId", "fromVersion",
            "toExtractionId", "toProposalId", "toVersion"], unique = true),
    ],
)
data class InformationProposalRelationEntity(
    val relationId: String,
    val fromExtractionId: String,
    val fromProposalId: String,
    val fromVersion: Int,
    val toExtractionId: String,
    val toProposalId: String,
    val toVersion: Int,
    val kind: String,
    val comparatorVersion: Int,
    val evidenceDigest: String,
    val createdAtEpochMillis: Long,
) {
    override fun toString() = "InformationProposalRelationEntity(kind=$kind, comparatorVersion=$comparatorVersion)"
}
