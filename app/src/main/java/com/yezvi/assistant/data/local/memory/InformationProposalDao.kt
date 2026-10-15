package com.yezvi.assistant.data.local.memory

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Transaction
import com.yezvi.assistant.domain.intelligence.*
import com.yezvi.assistant.data.intelligence.deduplication.relationEntity
import com.yezvi.assistant.data.intelligence.deduplication.ProposalCanonicalizer

@Dao
interface InformationProposalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDedupKey(entity: InformationProposalDedupKeyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRelation(entity: InformationProposalRelationEntity)

    @Query("SELECT * FROM information_proposal_dedup_keys WHERE canonicalizationVersion = :algorithm AND digest = :digest")
    suspend fun findDedupKey(algorithm: Int, digest: String): InformationProposalDedupKeyEntity?

    @Query("SELECT * FROM information_proposal_relations WHERE relationId = :id")
    suspend fun findRelation(id: String): InformationProposalRelationEntity?

    @Query("SELECT * FROM information_proposal_relations ORDER BY relationId")
    suspend fun findRelations(): List<InformationProposalRelationEntity>

    @Query("SELECT * FROM information_proposal_versions ORDER BY createdAtEpochMillis, extractionId, proposalId, version LIMIT :limit OFFSET :offset")
    suspend fun versionPage(limit: Int, offset: Int): List<InformationProposalVersionEntity>

    suspend fun sourcesAreCurrent(rows: List<InformationProposalSourceEntity>): Boolean = rows.all { isCurrent(it) }

    @Transaction
    suspend fun insertHostUpdate(
        batch: InitialProposalBatch,
        keys: List<InformationProposalDedupKeyEntity>,
        previous: InformationProposalVersionEntity,
        previousSources: List<InformationProposalSourceEntity>,
        targetProposalId: String,
        kind: ProposalComparisonKind,
        previousDescriptor: String,
        incomingDescriptor: String,
        at: Long,
    ): ProposalRelationResult.Evaluated {
        if (findVersion(previous.extractionId, previous.proposalId, previous.version) != previous ||
            findSources(previous.extractionId, previous.proposalId, previous.version) != previousSources ||
            findProposal(previous.extractionId, previous.proposalId)?.currentVersion != previous.version
        ) throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.STALE_VERSION)
        val submitted = insertDeduplicatedBatch(batch, keys)
        val target = submitted.receipts.firstOrNull { it.incoming.proposalId == targetProposalId }?.stored
            ?: throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.INVALID_REFERENCE)
        val old = ProposalVersionReference(ProposalIdentity(previous.extractionId, previous.proposalId), previous.version)
        if (target.identity == old.identity) {
            return ProposalRelationResult.Evaluated(ProposalComparisonKind.TECHNICAL_DUPLICATE, null, false)
        }
        val relation = relationEntity(old, target, kind, previousDescriptor, incomingDescriptor, at)
        val parent = findProposal(target.identity.extractionId, target.identity.proposalId)
        val version = findVersion(target.identity.extractionId, target.identity.proposalId, target.version)
        if (findRelation(relation.relationId) == null && (parent?.reviewState != "PROPOSED" ||
                version?.createdBy != "HOST" || target.version != 1)) {
            throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.STALE_VERSION)
        }
        val inserted = recordRelation(relation,
            findSources(relation.fromExtractionId, relation.fromProposalId, relation.fromVersion),
            findSources(relation.toExtractionId, relation.toProposalId, relation.toVersion))
        return ProposalRelationResult.Evaluated(kind, relation.relationId, !inserted)
    }

    /** The writer transaction plus the real PK arbitrate both processes and Room instances. */
    @Transaction
    suspend fun insertDeduplicatedBatch(
        batch: InitialProposalBatch,
        keys: List<InformationProposalDedupKeyEntity>,
    ): ProposalDeduplicationResult.Committed {
        if (keys.size != batch.proposals.size || !sourcesAreCurrent(batch.sources)) {
            throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.SOURCE_CHANGED)
        }
        val receipts = mutableListOf<ProposalDeduplicationReceipt>()
        val novel = mutableListOf<InitialProposalWrite>()
        val claims = linkedMapOf<Pair<Int, String>, InformationProposalDedupKeyEntity>()
        for ((incoming, key) in batch.proposals.zip(keys)) {
            if (key.extractionId != batch.extractionId || key.proposalId != incoming.proposal.proposalId || key.version != 1) {
                throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.IDENTITY_CONFLICT)
            }
            val slot = key.canonicalizationVersion to key.digest
            val existing = claims[slot] ?: findDedupKey(slot.first, slot.second)
            val identity = ProposalIdentity(batch.extractionId, incoming.proposal.proposalId)
            if (existing != null) {
                if (existing.descriptor != key.descriptor) {
                    throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.HASH_COLLISION)
                }
                if (slot !in claims) {
                    val parent = findProposal(existing.extractionId, existing.proposalId)
                    if (parent?.currentVersion != existing.version) {
                        throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.STALE_VERSION)
                    }
                    val owner = findVersion(existing.extractionId, existing.proposalId, existing.version)
                        ?: throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.INVALID_DOCUMENT)
                    if (sha256(owner.payloadJson) != owner.payloadSha256 ||
                        ProposalCanonicalizer().canonicalize(owner.payloadJson, owner.proposalId).descriptor != existing.descriptor
                    ) throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.INVALID_DOCUMENT)
                    val ownerSources = findSources(existing.extractionId, existing.proposalId, existing.version)
                    if (ownerSources.isEmpty() || !sourcesAreCurrent(ownerSources)) {
                        throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.SOURCE_CHANGED)
                    }
                }
                receipts += ProposalDeduplicationReceipt(identity, existing.reference(), true)
            } else {
                if (findProposal(key.extractionId, key.proposalId) != null) {
                    throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.IDENTITY_CONFLICT)
                }
                novel += incoming
                claims[slot] = key
                receipts += ProposalDeduplicationReceipt(identity, key.reference(), false)
            }
        }
        if (novel.isNotEmpty()) {
            if (findProposals(batch.extractionId).isNotEmpty()) {
                throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.IDENTITY_CONFLICT)
            }
            when (insertInitialBatch(batch.copy(proposals = novel))) {
                ProposalDaoWriteResult.APPLIED -> Unit
                ProposalDaoWriteResult.SOURCE_CHANGED -> throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.SOURCE_CHANGED)
                else -> throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.IDENTITY_CONFLICT)
            }
            claims.values.forEach { insertDedupKey(it) }
        }
        return ProposalDeduplicationResult.Committed(receipts)
    }

    @Transaction
    suspend fun backfillKeys(candidates: List<ProposalLegacyKeyCandidate>): ProposalBackfillReport {
        var inserted = 0
        var covered = 0
        for (candidate in candidates) {
            val row = candidate.version
            if (findVersion(row.extractionId, row.proposalId, row.version) != row ||
                findSources(row.extractionId, row.proposalId, row.version) != candidate.sources
            ) throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.TRANSACTION_CONFLICT)
            val existing = findDedupKey(candidate.key.canonicalizationVersion, candidate.key.digest)
            if (existing == null) {
                insertDedupKey(candidate.key)
                inserted++
            } else {
                if (existing.descriptor != candidate.key.descriptor) {
                    throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.HASH_COLLISION)
                }
                covered++
            }
        }
        return ProposalBackfillReport(inserted, covered, 0)
    }

    @Transaction
    suspend fun recordRelation(
        entity: InformationProposalRelationEntity,
        fromSources: List<InformationProposalSourceEntity>,
        toSources: List<InformationProposalSourceEntity>,
    ): Boolean {
        if (entity.fromExtractionId == entity.toExtractionId && entity.fromProposalId == entity.toProposalId) {
            throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.INVALID_REFERENCE)
        }
        if (findProposal(entity.fromExtractionId, entity.fromProposalId)?.currentVersion != entity.fromVersion ||
            findProposal(entity.toExtractionId, entity.toProposalId)?.currentVersion != entity.toVersion ||
            findVersion(entity.fromExtractionId, entity.fromProposalId, entity.fromVersion) == null ||
            findVersion(entity.toExtractionId, entity.toProposalId, entity.toVersion) == null
        ) throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.STALE_VERSION)
        if (findSources(entity.fromExtractionId, entity.fromProposalId, entity.fromVersion) != fromSources ||
            findSources(entity.toExtractionId, entity.toProposalId, entity.toVersion) != toSources ||
            !sourcesAreCurrent(fromSources) || !sourcesAreCurrent(toSources)
        ) throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.SOURCE_CHANGED)
        findRelation(entity.relationId)?.let {
            if (it.copy(createdAtEpochMillis = 0) != entity.copy(createdAtEpochMillis = 0)) {
                throw ProposalDeduplicationAbort(ProposalDeduplicationRejection.HASH_COLLISION)
            }
            return false
        }
        insertRelation(entity)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProposal(entity: InformationProposalEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProposals(entities: List<InformationProposalEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(entity: InformationProposalVersionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersions(entities: List<InformationProposalVersionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSources(entities: List<InformationProposalSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDecision(entity: InformationProposalDecisionEntity)

    @Update
    suspend fun updateProposal(entity: InformationProposalEntity): Int

    @Query("SELECT * FROM information_proposals WHERE extractionId = :extractionId AND proposalId = :proposalId LIMIT 1")
    suspend fun findProposal(extractionId: String, proposalId: String): InformationProposalEntity?

    @Query("SELECT * FROM information_proposals WHERE extractionId = :extractionId ORDER BY proposalId")
    suspend fun findProposals(extractionId: String): List<InformationProposalEntity>

    @Query("SELECT * FROM information_proposal_versions WHERE extractionId = :extractionId AND proposalId = :proposalId ORDER BY version")
    suspend fun findVersions(extractionId: String, proposalId: String): List<InformationProposalVersionEntity>

    @Query("SELECT * FROM information_proposal_versions WHERE extractionId = :extractionId AND proposalId = :proposalId AND version = :version LIMIT 1")
    suspend fun findVersion(extractionId: String, proposalId: String, version: Int): InformationProposalVersionEntity?

    @Query("SELECT * FROM information_proposal_versions WHERE extractionId = :extractionId AND proposalId = :proposalId AND actionId = :actionId LIMIT 1")
    suspend fun findVersionByAction(extractionId: String, proposalId: String, actionId: String): InformationProposalVersionEntity?

    @Query("SELECT * FROM information_proposal_sources WHERE extractionId = :extractionId AND proposalId = :proposalId AND version = :version ORDER BY sourceEntryId, revisionKind, revisionId")
    suspend fun findSources(extractionId: String, proposalId: String, version: Int): List<InformationProposalSourceEntity>

    @Query("SELECT * FROM information_proposal_decisions WHERE extractionId = :extractionId AND proposalId = :proposalId ORDER BY sequence")
    suspend fun findDecisions(extractionId: String, proposalId: String): List<InformationProposalDecisionEntity>

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM information_proposal_decisions WHERE extractionId = :extractionId AND proposalId = :proposalId")
    suspend fun nextDecisionSequence(extractionId: String, proposalId: String): Int

    @Query("SELECT * FROM information_proposal_decisions WHERE extractionId = :extractionId AND proposalId = :proposalId AND actionId = :actionId LIMIT 1")
    suspend fun findDecision(extractionId: String, proposalId: String, actionId: String): InformationProposalDecisionEntity?

    @Query("SELECT * FROM memory_entries WHERE id = :id LIMIT 1")
    suspend fun findMemoryEntry(id: String): MemoryEntryEntity?

    @Query("SELECT * FROM memory_entry_corrections WHERE memoryEntryId = :id ORDER BY correctionId DESC LIMIT 1")
    suspend fun findCurrentCorrection(id: String): MemoryEntryCorrectionEntity?

    @Query("SELECT * FROM memory_entry_corrections WHERE memoryEntryId = :id AND correctionId = :correctionId LIMIT 1")
    suspend fun findCorrection(id: String, correctionId: Long): MemoryEntryCorrectionEntity?

    @Query("UPDATE information_proposals SET currentVersion = :version, reviewState = :state, updatedAtEpochMillis = :updatedAtEpochMillis " +
        "WHERE extractionId = :extractionId AND proposalId = :proposalId AND currentVersion = :expectedVersion AND reviewState = :expectedState")
    suspend fun advanceProposal(
        extractionId: String,
        proposalId: String,
        expectedVersion: Int,
        expectedState: String,
        version: Int,
        state: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("UPDATE information_proposals SET reviewState = :state, updatedAtEpochMillis = :updatedAtEpochMillis " +
        "WHERE extractionId = :extractionId AND proposalId = :proposalId AND currentVersion = :expectedVersion AND reviewState = :expectedState")
    suspend fun advanceState(
        extractionId: String,
        proposalId: String,
        expectedVersion: Int,
        expectedState: String,
        state: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Transaction
    suspend fun insertInitialBatch(batch: InitialProposalBatch): ProposalDaoWriteResult {
        if (batch.proposals.isEmpty() || batch.sources.isEmpty() || batch.sources.any {
                it.extractionId != batch.extractionId || it.version != 1
            }
        ) return ProposalDaoWriteResult.CONFLICT
        if (batch.proposals.any { write ->
                val proposal = write.proposal
                val decision = write.decision
                proposal.extractionId != batch.extractionId || proposal.currentVersion != 1 ||
                    proposal.reviewState != "PROPOSED" || proposal.requestId != write.version.requestId ||
                    write.version.extractionId != proposal.extractionId || write.version.proposalId != proposal.proposalId ||
                    write.version.version != 1 || write.version.createdBy != "HOST" || write.version.actionId != null ||
                    decision.extractionId != proposal.extractionId || decision.proposalId != proposal.proposalId ||
                    decision.version != 1 || decision.sequence != 1 || decision.action != "SUBMIT_FOR_REVIEW" ||
                    decision.actor != "HOST" || decision.fromState != "CANDIDATE" || decision.toState != "PROPOSED" ||
                    canonicalProposalSources(write.sources) != canonicalProposalSources(
                        batch.sources.map { it.copy(proposalId = proposal.proposalId) },
                    )
            }
        ) return ProposalDaoWriteResult.CONFLICT
        if (!batch.sources.all { isCurrent(it) }) return ProposalDaoWriteResult.SOURCE_CHANGED
        val existing = findProposals(batch.extractionId)
        if (existing.isNotEmpty()) {
            if (existing.size == batch.proposals.size && batch.proposals.all { incoming ->
                    val prior = existing.firstOrNull { it.proposalId == incoming.proposal.proposalId }
                    val priorVersions = prior?.let { findVersions(batch.extractionId, it.proposalId) }.orEmpty()
                    val priorSources = prior?.let { findSources(batch.extractionId, it.proposalId, 1) }.orEmpty()
                    val priorDecisions = prior?.let { findDecisions(batch.extractionId, it.proposalId) }.orEmpty()
                    prior != null && prior.currentVersion == 1 && prior.reviewState == "PROPOSED" &&
                        prior.requestId == incoming.proposal.requestId && priorVersions.size == 1 &&
                        priorVersions.single().payloadSha256 == incoming.version.payloadSha256 &&
                        priorVersions.single().requestId == incoming.version.requestId &&
                        priorSources == canonicalProposalSources(incoming.sources) &&
                        priorDecisions == listOf(incoming.decision)
                }
            ) return ProposalDaoWriteResult.ALREADY_APPLIED
            return ProposalDaoWriteResult.CONFLICT
        }
        insertProposals(batch.proposals.map { it.proposal })
        insertVersions(batch.proposals.map { it.version })
        insertSources(batch.proposals.flatMap { it.sources })
        insertDecisionRows(batch.proposals.map { it.decision })
        return ProposalDaoWriteResult.APPLIED
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDecisionRows(entities: List<InformationProposalDecisionEntity>)

    @Transaction
    suspend fun appendProposalVersion(batch: ProposalVersionBatch): ProposalDaoWriteResult {
        if (!batch.sources.all { isCurrent(it) }) return ProposalDaoWriteResult.SOURCE_CHANGED
        val current = findProposal(batch.proposal.extractionId, batch.proposal.proposalId)
            ?: return ProposalDaoWriteResult.NOT_FOUND
        val actionId = batch.version.actionId ?: return ProposalDaoWriteResult.INVALID_STATE
        findVersionByAction(batch.version.extractionId, batch.version.proposalId, actionId)
            ?.let { existing ->
                val existingSources = findSources(batch.version.extractionId, batch.version.proposalId, existing.version)
                return if (existing.version == batch.version.version &&
                    existing.payloadSha256 == batch.version.payloadSha256 && existing.createdBy == "USER" &&
                    existing.requestId == batch.version.requestId && existing.schemaVersion == batch.version.schemaVersion &&
                    existingSources == canonicalProposalSources(batch.sources.map { it.copy(version = existing.version) })
                ) ProposalDaoWriteResult.ALREADY_APPLIED else ProposalDaoWriteResult.ACTION_ID_REUSED
            }
        if (current.reviewState != "PROPOSED") return ProposalDaoWriteResult.INVALID_STATE
        if (batch.version.createdBy != "USER" || batch.version.requestId != current.requestId ||
            batch.sources.any { it.extractionId != current.extractionId || it.proposalId != current.proposalId ||
                it.version != batch.version.version }
        ) return ProposalDaoWriteResult.CONFLICT
        if (current.currentVersion != batch.expectedVersion || batch.version.version != batch.expectedVersion + 1) {
            return ProposalDaoWriteResult.VERSION_CHANGED
        }
        insertVersion(batch.version)
        insertSources(batch.sources)
        if (advanceProposal(
                current.extractionId, current.proposalId, batch.expectedVersion, current.reviewState,
                batch.version.version, current.reviewState, batch.version.createdAtEpochMillis,
            ) != 1
        ) return ProposalDaoWriteResult.VERSION_CHANGED
        return ProposalDaoWriteResult.APPLIED
    }

    @Transaction
    suspend fun persistTransition(batch: ProposalTransitionBatch): ProposalDaoWriteResult {
        val current = findProposal(batch.decision.extractionId, batch.decision.proposalId)
            ?: return ProposalDaoWriteResult.NOT_FOUND
        findDecision(current.extractionId, current.proposalId, batch.decision.actionId)?.let { existing ->
            return if (existing.copy(sequence = 0) == batch.decision.copy(sequence = 0)) ProposalDaoWriteResult.ALREADY_APPLIED
            else ProposalDaoWriteResult.ACTION_ID_REUSED
        }
        if (current.currentVersion != batch.expectedVersion || current.reviewState != batch.decision.fromState) {
            return ProposalDaoWriteResult.INVALID_STATE
        }
        if (batch.decision.version != current.currentVersion || batch.decision.actor != "USER" ||
            batch.decision.action == "SUBMIT_FOR_REVIEW"
        ) return ProposalDaoWriteResult.INVALID_STATE
        if (batch.mustBeCurrent && !batch.sources.all { isCurrent(it) }) return ProposalDaoWriteResult.SOURCE_CHANGED
        insertDecision(batch.decision.copy(sequence = nextDecisionSequence(current.extractionId, current.proposalId)))
        if (advanceState(
                current.extractionId, current.proposalId, current.currentVersion, current.reviewState,
                batch.decision.toState, batch.decision.occurredAtEpochMillis,
            ) != 1
        ) return ProposalDaoWriteResult.INVALID_STATE
        return ProposalDaoWriteResult.APPLIED
    }

    private suspend fun isCurrent(snapshot: InformationProposalSourceEntity): Boolean {
        val entry = findMemoryEntry(snapshot.sourceEntryId) ?: return false
        if (entry.sourceType.name != snapshot.sourceType ||
            entry.capturedAtEpochMillis != snapshot.capturedAtEpochMillis || entry.timezoneId != snapshot.timezoneId
        ) return false
        val latestCorrection = findCurrentCorrection(entry.id)
        val text = when (snapshot.revisionKind) {
            "ORIGINAL" -> {
                if (snapshot.revisionId.isNotEmpty() || latestCorrection != null) return false
                entry.rawText
            }
            "CORRECTION" -> {
                val correctionId = snapshot.revisionId.toLongOrNull() ?: return false
                if (latestCorrection?.correctionId != correctionId) return false
                latestCorrection.correctedText
            }
            else -> return false
        }
        return text.length == snapshot.textLengthUtf16 && sha256(text) == snapshot.textSha256
    }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

data class ProposalLegacyKeyCandidate(
    val version: InformationProposalVersionEntity,
    val sources: List<InformationProposalSourceEntity>,
    val key: InformationProposalDedupKeyEntity,
) {
    override fun toString() = "ProposalLegacyKeyCandidate()"
}

internal class ProposalDeduplicationAbort(val reason: ProposalDeduplicationRejection) : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

private fun InformationProposalDedupKeyEntity.reference() =
    ProposalVersionReference(ProposalIdentity(extractionId, proposalId), version)

data class InitialProposalWrite(
    val proposal: InformationProposalEntity,
    val version: InformationProposalVersionEntity,
    val sources: List<InformationProposalSourceEntity>,
    val decision: InformationProposalDecisionEntity,
)

data class InitialProposalBatch(
    val extractionId: String,
    val sources: List<InformationProposalSourceEntity>,
    val proposals: List<InitialProposalWrite>,
)

data class ProposalVersionBatch(
    val proposal: InformationProposalEntity,
    val expectedVersion: Int,
    val version: InformationProposalVersionEntity,
    val sources: List<InformationProposalSourceEntity>,
)

data class ProposalTransitionBatch(
    val expectedVersion: Int,
    val decision: InformationProposalDecisionEntity,
    val sources: List<InformationProposalSourceEntity>,
    val mustBeCurrent: Boolean,
)

enum class ProposalDaoWriteResult {
    APPLIED, ALREADY_APPLIED, SOURCE_CHANGED, CONFLICT, ACTION_ID_REUSED, NOT_FOUND, INVALID_STATE, VERSION_CHANGED,
}

private fun canonicalProposalSources(
    sources: List<InformationProposalSourceEntity>,
): List<InformationProposalSourceEntity> = sources.sortedWith(
    compareBy<InformationProposalSourceEntity>({ it.sourceEntryId }, { it.revisionKind }, { it.revisionId }),
)
