package com.yezvi.assistant.data.repository

import com.yezvi.assistant.data.intelligence.deduplication.*
import com.yezvi.assistant.data.intelligence.validation.*
import com.yezvi.assistant.data.local.memory.*
import com.yezvi.assistant.domain.intelligence.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** F4.09 entry point. No IO except Room; all outputs remain pending, never merged. */
class RoomProposalDeduplication internal constructor(
    private val dao: InformationProposalDao,
    private val repository: RoomInformationProposalRepository = RoomInformationProposalRepository(dao),
    private val canonicalizer: ProposalCanonicalizer = ProposalCanonicalizer(),
) {
    /** A HOST update is a NEW pending extraction and an immutable link, committed together. */
    suspend fun proposeHostUpdate(
        previous: ProposalVersionReference,
        request: InformationExtractionRequest,
        extraction: InformationExtractionResult.Proposals,
        targetProposalId: String,
        at: Long,
    ): ProposalRelationResult = withContext(Dispatchers.Default) {
        try {
            if (request.extractionId == previous.identity.extractionId) {
                return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.IDENTITY_CONFLICT)
            }
            val old = (repository.find(previous.identity) as? ProposalReviewLoadResult.Found)?.review
                ?: return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.INVALID_REFERENCE)
            if (old.currentVersion != previous.version || !old.sourcesAreCurrent) {
                return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.STALE_VERSION)
            }
            val prepared = repository.prepareSubmission(request, extraction, at)
            if (prepared !is PreparedProposalSubmission.Ready) {
                return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.INVALID_DOCUMENT)
            }
            val oldDescriptor = canonicalizer.canonicalize(old.document.json, previous.identity.proposalId)
            val incoming = canonicalizer.canonicalize(extraction.document.json, targetProposalId)
            val temporal = com.yezvi.assistant.data.intelligence.temporal.DeterministicTemporalExpressionResolver()
                .resolve(request, extraction.document) as? TemporalResolutionResult.Completed
                ?: return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.INVALID_DOCUMENT)
            val uncertain = (old.temporal.resolutions.filter { it.proposalId == previous.identity.proposalId } +
                temporal.resolutions.filter { it.proposalId == targetProposalId }).any {
                    it.status != TemporalResolutionStatus.RESOLVED || it.proposedResolutionConflict
                }
            val kind = ConservativeProposalComparator().compare(oldDescriptor, incoming, uncertain)
            if (kind !in directedKinds && kind != ProposalComparisonKind.CONFLICT_REQUIRES_REVIEW) {
                return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.IDENTITY_CONFLICT)
            }
            val previousRow = dao.findVersion(previous.identity.extractionId, previous.identity.proposalId, previous.version)
                ?: return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.INVALID_REFERENCE)
            val sources = dao.findSources(previousRow.extractionId, previousRow.proposalId, previousRow.version)
            backfill()
            dao.insertHostUpdate(prepared.batch, prepared.batch.proposals.map { keyFor(it.version) },
                previousRow, sources, targetProposalId, kind, oldDescriptor.descriptor, incoming.descriptor, at)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (abort: ProposalDeduplicationAbort) { ProposalRelationResult.Rejected(abort.reason) }
        catch (_: IllegalArgumentException) { ProposalRelationResult.Rejected(ProposalDeduplicationRejection.UNSUPPORTED_CANONICALIZATION) }
        catch (_: Exception) { ProposalRelationResult.Rejected(ProposalDeduplicationRejection.STORAGE_FAILURE) }
    }

    suspend fun submit(
        request: InformationExtractionRequest,
        extraction: InformationExtractionResult.Proposals,
        submittedAtEpochMillis: Long,
    ): ProposalDeduplicationResult = withContext(Dispatchers.Default) {
        try {
            val prepared = repository.prepareSubmission(request, extraction, submittedAtEpochMillis)
            if (prepared is PreparedProposalSubmission.Rejected) return@withContext rejected(when (prepared.reason) {
                ProposalPersistenceRejection.SYNTHETIC_FIXTURE -> ProposalDeduplicationRejection.SYNTHETIC_FIXTURE
                else -> ProposalDeduplicationRejection.INVALID_DOCUMENT
            })
            val batch = (prepared as PreparedProposalSubmission.Ready).batch
            val keys = try { batch.proposals.map { keyFor(it.version) } }
            catch (_: IllegalArgumentException) { return@withContext rejected(ProposalDeduplicationRejection.UNSUPPORTED_CANONICALIZATION) }
            backfill()
            dao.insertDeduplicatedBatch(batch, keys)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (abort: ProposalDeduplicationAbort) { rejected(abort.reason) }
        catch (_: Exception) { rejected(ProposalDeduplicationRejection.STORAGE_FAILURE) }
    }

    /** Each page commits atomically. Interrupted work resumes safely, without rewriting history. */
    suspend fun backfill(): ProposalBackfillReport = withContext(Dispatchers.Default) {
        var offset = 0
        var indexed = 0
        var covered = 0
        var unindexable = 0
        while (true) {
            val page = dao.versionPage(64, offset)
            if (page.isEmpty()) break
            val candidates = mutableListOf<ProposalLegacyKeyCandidate>()
            for (version in page) {
                val sources = dao.findSources(version.extractionId, version.proposalId, version.version)
                val key = try {
                    if (!repository.validatedLegacyVersion(version, sources)) null else keyFor(version)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IllegalArgumentException) { null }
                if (key == null) unindexable++ else candidates += ProposalLegacyKeyCandidate(version, sources, key)
            }
            val result = dao.backfillKeys(candidates)
            indexed += result.indexed
            covered += result.alreadyCovered
            offset += page.size
        }
        ProposalBackfillReport(indexed, covered, unindexable)
    }

    /** Exact versions and current sources are rechecked inside the relation write transaction. */
    suspend fun compareAndRelate(
        previous: ProposalVersionReference,
        incoming: ProposalVersionReference,
        evaluatedAtEpochMillis: Long,
    ): ProposalRelationResult = withContext(Dispatchers.Default) {
        try {
            if (previous.identity == incoming.identity) {
                return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.INVALID_REFERENCE)
            }
            val old = (repository.find(previous.identity) as? ProposalReviewLoadResult.Found)?.review
                ?: return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.INVALID_REFERENCE)
            val new = (repository.find(incoming.identity) as? ProposalReviewLoadResult.Found)?.review
                ?: return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.INVALID_REFERENCE)
            if (old.currentVersion != previous.version || new.currentVersion != incoming.version) {
                return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.STALE_VERSION)
            }
            if (!old.sourcesAreCurrent || !new.sourcesAreCurrent) {
                return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.SOURCE_CHANGED)
            }
            val oldDescriptor = canonicalizer.canonicalize(old.document.json, previous.identity.proposalId)
            val newDescriptor = canonicalizer.canonicalize(new.document.json, incoming.identity.proposalId)
            val uncertain = (old.temporal.resolutions.filter { it.proposalId == previous.identity.proposalId } +
                new.temporal.resolutions.filter { it.proposalId == incoming.identity.proposalId }).any {
                it.status != TemporalResolutionStatus.RESOLVED || it.proposedResolutionConflict
            }
            val kind = ConservativeProposalComparator().compare(oldDescriptor, newDescriptor, uncertain)
            if (kind in setOf(ProposalComparisonKind.DISTINCT, ProposalComparisonKind.TECHNICAL_DUPLICATE)) {
                return@withContext ProposalRelationResult.Evaluated(kind, null, false)
            }
            val directed = kind in directedKinds
            if (directed && (new.currentVersion != 1 || new.state != ProposalReviewState.PROPOSED ||
                    new.versions.singleOrNull()?.author != PersistedProposalVersionAuthor.HOST)) {
                return@withContext ProposalRelationResult.Rejected(ProposalDeduplicationRejection.STALE_VERSION)
            }
            val entity = relationEntity(previous, incoming, kind, oldDescriptor.descriptor,
                newDescriptor.descriptor, evaluatedAtEpochMillis)
            val from = dao.findSources(entity.fromExtractionId, entity.fromProposalId, entity.fromVersion)
            val to = dao.findSources(entity.toExtractionId, entity.toProposalId, entity.toVersion)
            val inserted = dao.recordRelation(entity, from, to)
            ProposalRelationResult.Evaluated(kind, entity.relationId, !inserted)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (abort: ProposalDeduplicationAbort) { ProposalRelationResult.Rejected(abort.reason) }
        catch (_: Exception) { ProposalRelationResult.Rejected(ProposalDeduplicationRejection.STORAGE_FAILURE) }
    }

    private fun keyFor(version: InformationProposalVersionEntity): InformationProposalDedupKeyEntity {
        val canonical = canonicalizer.canonicalize(version.payloadJson, version.proposalId)
        return InformationProposalDedupKeyEntity(ProposalCanonicalizer.VERSION, canonical.digest, canonical.descriptor,
            version.extractionId, version.proposalId, version.version)
    }

    private fun rejected(reason: ProposalDeduplicationRejection) = ProposalDeduplicationResult.Rejected(reason)
}

