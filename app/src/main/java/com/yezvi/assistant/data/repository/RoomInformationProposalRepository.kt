package com.yezvi.assistant.data.repository

import com.yezvi.assistant.data.intelligence.temporal.DeterministicTemporalExpressionResolver
import com.yezvi.assistant.data.intelligence.validation.BoundedJson
import com.yezvi.assistant.data.intelligence.validation.JsonArray
import com.yezvi.assistant.data.intelligence.validation.JsonNumber
import com.yezvi.assistant.data.intelligence.validation.JsonObject
import com.yezvi.assistant.data.intelligence.validation.JsonString
import com.yezvi.assistant.data.intelligence.validation.JsonValue
import com.yezvi.assistant.data.intelligence.validation.StrictInformationProposalValidator
import com.yezvi.assistant.data.local.memory.InformationProposalDao
import com.yezvi.assistant.data.local.memory.InformationProposalDecisionEntity
import com.yezvi.assistant.data.local.memory.InformationProposalEntity
import com.yezvi.assistant.data.local.memory.InformationProposalSourceEntity
import com.yezvi.assistant.data.local.memory.InformationProposalVersionEntity
import com.yezvi.assistant.data.local.memory.InitialProposalBatch
import com.yezvi.assistant.data.local.memory.InitialProposalWrite
import com.yezvi.assistant.data.local.memory.ProposalDaoWriteResult
import com.yezvi.assistant.data.local.memory.ProposalTransitionBatch
import com.yezvi.assistant.data.local.memory.ProposalVersionBatch
import com.yezvi.assistant.domain.intelligence.*
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryTextVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Room persistence for checked proposal versions and explicit F4.06 review transitions. */
class RoomInformationProposalRepository internal constructor(
    private val dao: InformationProposalDao,
    /** Only instrumentation setup may opt in, and it must use a uniquely named temporary database. */
    private val allowSyntheticFixturesForTests: Boolean = false,
) : InformationProposalRepository {
    private val validator = StrictInformationProposalValidator()
    private val temporalResolver = DeterministicTemporalExpressionResolver()

    override suspend fun submitForReview(
        request: InformationExtractionRequest,
        extraction: InformationExtractionResult.Proposals,
        submittedAtEpochMillis: Long,
    ): ProposalPersistenceResult {
        return when (val result = RoomProposalDeduplication(dao, this).submit(request, extraction, submittedAtEpochMillis)) {
            is ProposalDeduplicationResult.Committed -> {
                val ids = result.receipts.map { it.stored.identity }
                if (result.receipts.all { it.technicalDuplicate }) ProposalPersistenceResult.BatchAlreadyApplied(ids)
                else ProposalPersistenceResult.BatchApplied(ids)
            }
            is ProposalDeduplicationResult.Rejected -> when (result.reason) {
                ProposalDeduplicationRejection.SOURCE_CHANGED -> rejected(ProposalPersistenceRejection.SOURCE_CHANGED)
                ProposalDeduplicationRejection.SYNTHETIC_FIXTURE -> rejected(ProposalPersistenceRejection.SYNTHETIC_FIXTURE)
                ProposalDeduplicationRejection.INVALID_DOCUMENT -> rejected(ProposalPersistenceRejection.INVALID_DOCUMENT)
                ProposalDeduplicationRejection.STORAGE_FAILURE -> error("Proposal transaction failed")
                else -> rejected(ProposalPersistenceRejection.INVALID_STATE)
            }
        }
    }

    internal suspend fun prepareSubmission(
        request: InformationExtractionRequest,
        extraction: InformationExtractionResult.Proposals,
        submittedAtEpochMillis: Long,
    ): PreparedProposalSubmission {
        if (extraction.mode != ExtractionMode.MODEL_OUTPUT && !allowSyntheticFixturesForTests) {
            return PreparedProposalSubmission.Rejected(ProposalPersistenceRejection.SYNTHETIC_FIXTURE)
        }
        val checked = validate(request, extraction.document) ?:
            return PreparedProposalSubmission.Rejected(ProposalPersistenceRejection.INVALID_DOCUMENT)
        val root = parseObject(checked.validation.document.json)
            ?: return PreparedProposalSubmission.Rejected(ProposalPersistenceRejection.INVALID_DOCUMENT)
        val proposals = root["proposals"] as? JsonArray
            ?: return PreparedProposalSubmission.Rejected(ProposalPersistenceRejection.INVALID_DOCUMENT)
        if (proposals.values.isEmpty()) return PreparedProposalSubmission.Rejected(ProposalPersistenceRejection.INVALID_DOCUMENT)

        val sourceRevisions = request.sources.map { source ->
            ProposalSourceRevision(source.snapshot.sourceEntryId, source.snapshot.revision)
        }
        val sourceRowsByProposal = proposals.values.map { raw ->
            val proposalNode = raw as? JsonObject ?: return PreparedProposalSubmission.Rejected(ProposalPersistenceRejection.INVALID_DOCUMENT)
            val proposalId = (proposalNode["proposalId"] as? JsonString)?.value
                ?: return PreparedProposalSubmission.Rejected(ProposalPersistenceRejection.INVALID_DOCUMENT)
            val identity = try { ProposalIdentity(request.extractionId, proposalId) }
            catch (_: IllegalArgumentException) { return PreparedProposalSubmission.Rejected(ProposalPersistenceRejection.INVALID_DOCUMENT) }
            val readiness = ProposalReviewReadiness.afterValidationAndTemporalResolution(
                identity, sourceRevisions, checked.validation, checked.temporal,
            ) ?: return PreparedProposalSubmission.Rejected(ProposalPersistenceRejection.INVALID_DOCUMENT)
            val lifecycle = ProposalLifecycle.candidate(identity, sourceRevisions)
            val submitId = hostSubmitActionId(identity)
            val transitioned = lifecycle.transition(
                ProposalLifecycleCommand.SubmitForReview(submitId, submittedAtEpochMillis, readiness),
            ) as? ProposalTransitionResult.Applied
                ?: return PreparedProposalSubmission.Rejected(ProposalPersistenceRejection.INVALID_DOCUMENT)
            val event = transitioned.event
            val parent = InformationProposalEntity(
                extractionId = identity.extractionId,
                proposalId = identity.proposalId,
                requestId = request.requestId,
                currentVersion = 1,
                reviewState = transitioned.lifecycle.state.name,
                createdAtEpochMillis = submittedAtEpochMillis,
                updatedAtEpochMillis = submittedAtEpochMillis,
            )
            val version = InformationProposalVersionEntity(
                extractionId = identity.extractionId,
                proposalId = identity.proposalId,
                version = 1,
                requestId = request.requestId,
                schemaVersion = request.schemaVersion,
                outcome = checked.validation.document.outcome.name,
                payloadJson = checked.validation.document.json,
                payloadSha256 = sha256(checked.validation.document.json),
                createdBy = PersistedProposalVersionAuthor.HOST.name,
                actionId = null,
                createdAtEpochMillis = submittedAtEpochMillis,
            )
            val sources = request.sources.map { it.toEntity(identity, 1) }
            val decision = event.toEntity(identity, version = 1, sequence = 1)
            InitialProposalWrite(parent, version, sources, decision)
        }

        return PreparedProposalSubmission.Ready(InitialProposalBatch(
            extractionId = request.extractionId,
            sources = sourceRowsByProposal.first().sources,
            proposals = sourceRowsByProposal,
        ))
    }

    override suspend fun appendUserRevision(
        identity: ProposalIdentity,
        request: InformationExtractionRequest,
        editedDocument: UnvalidatedProposalDocument,
        explicitAction: ExplicitUserReviewAction,
        editedAtEpochMillis: Long,
    ): ProposalPersistenceResult {
        if (request.extractionId != identity.extractionId) return rejected(ProposalPersistenceRejection.DOCUMENT_NOT_EDITABLE)
        val checked = validate(request, editedDocument)
            ?: return rejected(ProposalPersistenceRejection.INVALID_DOCUMENT)
        val digest = sha256(checked.validation.document.json)
        dao.findVersionByAction(identity.extractionId, identity.proposalId, explicitAction.actionId)?.let { prior ->
            val storedSources = dao.findSources(identity.extractionId, identity.proposalId, prior.version)
            val requestedSources = request.sources.map { it.toEntity(identity, prior.version) }
            return if (prior.createdBy == PersistedProposalVersionAuthor.USER.name &&
                prior.requestId == request.requestId && prior.schemaVersion == request.schemaVersion &&
                prior.payloadSha256 == digest && sameSourceRows(storedSources, requestedSources)
            ) {
                val parent = dao.findProposal(identity.extractionId, identity.proposalId)
                    ?: return rejected(ProposalPersistenceRejection.PROPOSAL_NOT_FOUND)
                ProposalPersistenceResult.AlreadyApplied(identity, prior.version, parent.reviewState.toReviewState())
            } else rejected(ProposalPersistenceRejection.ACTION_ID_REUSED)
        }

        val current = find(identity)
        val review = (current as? ProposalReviewLoadResult.Found)?.review
            ?: return when (current) {
                ProposalReviewLoadResult.Missing -> rejected(ProposalPersistenceRejection.PROPOSAL_NOT_FOUND)
                is ProposalReviewLoadResult.Rejected -> rejected(current.reason)
                else -> rejected(ProposalPersistenceRejection.STORED_DOCUMENT_INVALID)
            }
        if (review.state != ProposalReviewState.PROPOSED) return rejected(ProposalPersistenceRejection.INVALID_STATE)
        if (!review.sourcesAreCurrent) return rejected(ProposalPersistenceRejection.SOURCE_CHANGED)
        if (request != review.request) return rejected(ProposalPersistenceRejection.SOURCE_CHANGED)
        if (!isSingleProposalEdit(review.document.json, checked.validation.document.json, identity.proposalId)) {
            return rejected(ProposalPersistenceRejection.DOCUMENT_NOT_EDITABLE)
        }
        if (review.document.json == checked.validation.document.json) return rejected(ProposalPersistenceRejection.NO_CHANGE)

        val expected = review.currentVersion
        val version = InformationProposalVersionEntity(
            extractionId = identity.extractionId,
            proposalId = identity.proposalId,
            version = expected + 1,
            requestId = request.requestId,
            schemaVersion = request.schemaVersion,
            outcome = checked.validation.document.outcome.name,
            payloadJson = checked.validation.document.json,
            payloadSha256 = digest,
            createdBy = PersistedProposalVersionAuthor.USER.name,
            actionId = explicitAction.actionId,
            createdAtEpochMillis = editedAtEpochMillis,
        )
        val sources = request.sources.map { it.toEntity(identity, version.version) }
        return when (dao.appendProposalVersion(ProposalVersionBatch(
            proposal = InformationProposalEntity(
                identity.extractionId, identity.proposalId, request.requestId,
                expected, review.state.name, 0L, editedAtEpochMillis,
            ),
            expectedVersion = expected,
            version = version,
            sources = sources,
        ))) {
            ProposalDaoWriteResult.APPLIED -> ProposalPersistenceResult.Applied(identity, version.version, review.state)
            ProposalDaoWriteResult.ALREADY_APPLIED -> ProposalPersistenceResult.AlreadyApplied(identity, version.version, review.state)
            ProposalDaoWriteResult.SOURCE_CHANGED -> rejected(ProposalPersistenceRejection.SOURCE_CHANGED)
            ProposalDaoWriteResult.ACTION_ID_REUSED -> rejected(ProposalPersistenceRejection.ACTION_ID_REUSED)
            ProposalDaoWriteResult.VERSION_CHANGED -> rejected(ProposalPersistenceRejection.VERSION_CHANGED)
            ProposalDaoWriteResult.NOT_FOUND -> rejected(ProposalPersistenceRejection.PROPOSAL_NOT_FOUND)
            else -> rejected(ProposalPersistenceRejection.INVALID_STATE)
        }
    }

    override suspend fun transition(
        identity: ProposalIdentity,
        request: InformationExtractionRequest,
        currentDocument: UnvalidatedProposalDocument,
        action: PersistedProposalAction,
        explicitAction: ExplicitUserReviewAction,
        occurredAtEpochMillis: Long,
    ): ProposalPersistenceResult {
        if (request.extractionId != identity.extractionId) return rejected(ProposalPersistenceRejection.INVALID_DOCUMENT)
        val storedAction = dao.findDecision(identity.extractionId, identity.proposalId, explicitAction.actionId)
        if (storedAction != null) {
            val priorVersion = dao.findVersion(identity.extractionId, identity.proposalId, storedAction.version)
            val priorSources = dao.findSources(identity.extractionId, identity.proposalId, storedAction.version)
            val requestedSources = request.sources.map { it.toEntity(identity, storedAction.version) }
            val checkedRetry = validate(request, currentDocument)
            val priorJson = priorVersion?.let { parseObject(it.payloadJson) }
            val retryJson = checkedRetry?.let { parseObject(it.validation.document.json) }
            val sameAction = priorVersion != null && checkedRetry != null &&
                storedAction.action == action.transitionAction().name &&
                storedAction.actor == ProposalTransitionActor.USER.name &&
                storedAction.occurredAtEpochMillis == occurredAtEpochMillis &&
                priorVersion.requestId == request.requestId && priorVersion.schemaVersion == request.schemaVersion &&
                priorJson != null && priorJson == retryJson &&
                sameSourceRows(priorSources, requestedSources)
            return if (sameAction) {
                ProposalPersistenceResult.AlreadyApplied(identity, storedAction.version, storedAction.toState.toReviewState())
            } else rejected(ProposalPersistenceRejection.ACTION_ID_REUSED)
        }

        val checked = validate(request, currentDocument)
            ?: return rejected(ProposalPersistenceRejection.INVALID_DOCUMENT)
        val loaded = find(identity)
        val review = (loaded as? ProposalReviewLoadResult.Found)?.review
            ?: return when (loaded) {
                ProposalReviewLoadResult.Missing -> rejected(ProposalPersistenceRejection.PROPOSAL_NOT_FOUND)
                is ProposalReviewLoadResult.Rejected -> rejected(loaded.reason)
                else -> rejected(ProposalPersistenceRejection.STORED_DOCUMENT_INVALID)
            }
        if (request != review.request || checked.validation.document.json != review.document.json) {
            return rejected(ProposalPersistenceRejection.SOURCE_CHANGED)
        }
        if (action in setOf(PersistedProposalAction.CONFIRM, PersistedProposalAction.REACTIVATE) && !review.sourcesAreCurrent) {
            return rejected(ProposalPersistenceRejection.SOURCE_CHANGED)
        }

        val revisions = request.sources.map { ProposalSourceRevision(it.snapshot.sourceEntryId, it.snapshot.revision) }
        val lifecycle = ProposalLifecycle.restoreProjection(identity, revisions, review.state)
        val readiness = ProposalReviewReadiness.afterValidationAndTemporalResolution(
            identity, revisions, checked.validation, checked.temporal,
        )
        val command = when (action) {
            PersistedProposalAction.CONFIRM -> ProposalLifecycleCommand.Confirm(
                explicitAction.actionId, occurredAtEpochMillis, explicitAction,
            )
            PersistedProposalAction.REJECT -> ProposalLifecycleCommand.Reject(
                explicitAction.actionId, occurredAtEpochMillis, explicitAction,
            )
            PersistedProposalAction.EXPIRE -> ProposalLifecycleCommand.Expire(
                explicitAction.actionId, occurredAtEpochMillis, explicitAction,
            )
            PersistedProposalAction.REACTIVATE -> ProposalLifecycleCommand.Reactivate(
                explicitAction.actionId, occurredAtEpochMillis, explicitAction, readiness,
            )
        }
        val transition = lifecycle.transition(command)
        val event = when (transition) {
            is ProposalTransitionResult.Applied -> transition.event
            is ProposalTransitionResult.AlreadyApplied -> transition.event
            is ProposalTransitionResult.NoChange -> return rejected(ProposalPersistenceRejection.NO_CHANGE)
            is ProposalTransitionResult.Rejected -> return rejected(ProposalPersistenceRejection.INVALID_STATE)
        }
        val decision = event.toEntity(identity, review.currentVersion, sequence = 0)
        val sourceRows = dao.findSources(identity.extractionId, identity.proposalId, review.currentVersion)
        val daoResult = dao.persistTransition(
            ProposalTransitionBatch(
                expectedVersion = review.currentVersion,
                decision = decision,
                sources = sourceRows,
                mustBeCurrent = action == PersistedProposalAction.CONFIRM || action == PersistedProposalAction.REACTIVATE,
            ),
        )
        return when (daoResult) {
            ProposalDaoWriteResult.APPLIED -> ProposalPersistenceResult.Applied(identity, review.currentVersion, event.to)
            ProposalDaoWriteResult.ALREADY_APPLIED -> ProposalPersistenceResult.AlreadyApplied(identity, review.currentVersion, event.to)
            ProposalDaoWriteResult.SOURCE_CHANGED -> rejected(ProposalPersistenceRejection.SOURCE_CHANGED)
            ProposalDaoWriteResult.NOT_FOUND -> rejected(ProposalPersistenceRejection.PROPOSAL_NOT_FOUND)
            ProposalDaoWriteResult.ACTION_ID_REUSED -> rejected(ProposalPersistenceRejection.ACTION_ID_REUSED)
            else -> rejected(ProposalPersistenceRejection.INVALID_STATE)
        }
    }

    override suspend fun find(identity: ProposalIdentity): ProposalReviewLoadResult = withContext(Dispatchers.Default) {
        val parent = dao.findProposal(identity.extractionId, identity.proposalId) ?: return@withContext ProposalReviewLoadResult.Missing
        val current = try { parent.reviewState.toReviewState() }
        catch (_: IllegalArgumentException) { return@withContext loadRejected() }
        val historyEntities = dao.findDecisions(identity.extractionId, identity.proposalId)
        if (historyEntities.isEmpty() || historyEntities.map { it.sequence } != (1..historyEntities.size).toList() ||
            historyEntities.last().toState != current.name
        ) return@withContext loadRejected()
        val versionEntities = dao.findVersions(identity.extractionId, identity.proposalId)
        if (versionEntities.isEmpty() || versionEntities.map { it.version } != (1..versionEntities.size).toList() ||
            parent.currentVersion != versionEntities.size || versionEntities.any { version ->
                version.requestId != parent.requestId ||
                    if (version.version == 1) {
                        version.createdBy != PersistedProposalVersionAuthor.HOST.name || version.actionId != null
                    } else {
                        version.createdBy != PersistedProposalVersionAuthor.USER.name || version.actionId.isNullOrBlank()
                    }
            }
        ) return@withContext loadRejected()
        if (versionEntities.any { sha256(it.payloadJson) != it.payloadSha256 }) return@withContext loadRejected()

        val readinessByVersion = linkedMapOf<Int, ProposalReviewReadiness>()
        for (version in versionEntities) {
            val historicalSources = dao.findSources(identity.extractionId, identity.proposalId, version.version)
            val historicalRequest = reconstructRequest(parent, version, version.payloadJson, historicalSources)
                ?: return@withContext loadRejected()
            val historicalDocument = UnvalidatedProposalDocument(
                version.schemaVersion, version.outcome.toExtractionOutcome(), version.payloadJson,
            )
            val historicalChecked = validate(historicalRequest, historicalDocument)
                ?: return@withContext loadRejected()
            if (identity.proposalId !in (parseProposalIdentities(version.payloadJson) ?: return@withContext loadRejected())) {
                return@withContext loadRejected()
            }
            val historicalRevisions = historicalRequest.sources.map {
                ProposalSourceRevision(it.snapshot.sourceEntryId, it.snapshot.revision)
            }
            readinessByVersion[version.version] = ProposalReviewReadiness.afterValidationAndTemporalResolution(
                identity, historicalRevisions, historicalChecked.validation, historicalChecked.temporal,
            ) ?: return@withContext loadRejected()
        }

        val composedJson = composeCurrentDocument(identity.extractionId)
            ?: return@withContext loadRejected()
        val currentVersion = versionEntities.firstOrNull { it.version == parent.currentVersion }
            ?: return@withContext loadRejected()
        val sourceRows = dao.findSources(identity.extractionId, identity.proposalId, parent.currentVersion)
        val request = reconstructRequest(parent, currentVersion, composedJson, sourceRows)
            ?: return@withContext loadRejected()
        val document = UnvalidatedProposalDocument(
            currentVersion.schemaVersion,
            currentVersion.outcome.toExtractionOutcome(),
            composedJson,
        )
        val checked = validate(request, document) ?: return@withContext loadRejected()
        val identities = parseProposalIdentities(composedJson) ?: return@withContext loadRejected()
        if (identity.proposalId !in identities || parent.currentVersion <= 0) return@withContext loadRejected()
        if (!historyMatches(identity, historyEntities, readinessByVersion, current, parent.currentVersion)) {
            return@withContext loadRejected()
        }
        val sourceCurrent = sourceRows.all { isCurrent(it) }
        val versions = versionEntities.map { entity ->
            PersistedProposalVersionSummary(
                entity.version,
                try { PersistedProposalVersionAuthor.valueOf(entity.createdBy) }
                catch (_: IllegalArgumentException) { return@withContext loadRejected() },
                entity.actionId,
                entity.createdAtEpochMillis,
            )
        }
        val decisions = historyEntities.map { entity ->
            try {
                PersistedProposalDecision(
                    entity.actionId,
                    ProposalTransitionAction.valueOf(entity.action),
                    ProposalTransitionActor.valueOf(entity.actor),
                    entity.fromState.toReviewState(),
                    entity.toState.toReviewState(),
                    entity.version,
                    entity.occurredAtEpochMillis,
                )
            } catch (_: IllegalArgumentException) { return@withContext loadRejected() }
        }
        ProposalReviewLoadResult.Found(
            PersistedProposalReview(identity, parent.currentVersion, current, request,
                checked.validation.document, checked.temporal, sourceCurrent, versions, decisions),
        )
    }

    internal suspend fun validatedLegacyVersion(
        version: InformationProposalVersionEntity,
        sourceRows: List<InformationProposalSourceEntity>,
    ): Boolean {
        if (sha256(version.payloadJson) != version.payloadSha256) return false
        val parent = dao.findProposal(version.extractionId, version.proposalId) ?: return false
        val request = reconstructRequest(parent, version, version.payloadJson, sourceRows) ?: return false
        return validate(request, UnvalidatedProposalDocument(
            version.schemaVersion, version.outcome.toExtractionOutcome(), version.payloadJson,
        )) != null
    }

    private suspend fun validate(
        request: InformationExtractionRequest,
        document: UnvalidatedProposalDocument,
    ): CheckedDocument? = withContext(Dispatchers.Default) {
        val validation = validator.validate(request, document) as? ProposalValidationResult.Valid ?: return@withContext null
        val temporal = temporalResolver.resolve(request, document) as? TemporalResolutionResult.Completed
            ?: return@withContext null
        if (validation.document.json != temporal.validatedDocument.json) return@withContext null
        CheckedDocument(validation, temporal)
    }

    private suspend fun composeCurrentDocument(extractionId: String): String? {
        val proposals = dao.findProposals(extractionId)
        if (proposals.isEmpty()) return null
        val proposalById = linkedMapOf<String, JsonObject>()
        var baseRoot: JsonObject? = null
        var originalOrder: List<String>? = null
        for (proposal in proposals) {
            val version = dao.findVersion(extractionId, proposal.proposalId, proposal.currentVersion) ?: return null
            if (sha256(version.payloadJson) != version.payloadSha256) return null
            val root = parseObject(version.payloadJson) ?: return null
            val ids = proposalIds(root) ?: return null
            val node = (root["proposals"] as JsonArray).values.mapNotNull { it as? JsonObject }
                .firstOrNull { (it["proposalId"] as? JsonString)?.value == proposal.proposalId } ?: return null
            if (baseRoot == null) {
                baseRoot = root
                originalOrder = ids
            } else if (root.withoutProposals() != baseRoot!!.withoutProposals()) {
                return null
            }
            if (proposalById.put(proposal.proposalId, node) != null) return null
        }
        val contextualNodes = proposalMap(checkNotNull(baseRoot)) ?: return null
        if (!contextualNodes.keys.containsAll(proposalById.keys)) return null
        val composed = checkNotNull(baseRoot).withProposals(
            checkNotNull(originalOrder).map { proposalById[it] ?: contextualNodes[it] ?: return null },
        )
        return serialize(composed)
    }

    private suspend fun reconstructRequest(
        parent: InformationProposalEntity,
        version: InformationProposalVersionEntity,
        documentJson: String,
        sourceRows: List<InformationProposalSourceEntity>,
    ): InformationExtractionRequest? {
        val root = parseObject(documentJson) ?: return null
        if ((root["requestId"] as? JsonString)?.value != parent.requestId ||
            (root["extractionId"] as? JsonString)?.value != parent.extractionId
        ) return null
        val snapshots = root["sourceSnapshots"] as? JsonArray ?: return null
        val byKey = sourceRows.associateBy { Triple(it.sourceEntryId, it.revisionKind, it.revisionId) }.toMutableMap()
        if (byKey.size != sourceRows.size || byKey.size != snapshots.values.size) return null
        val sources = mutableListOf<InformationExtractionSource>()
        for (raw in snapshots.values) {
            val snap = (raw as? JsonObject) ?: return null
            val sourceId = (snap["sourceEntryId"] as? JsonString)?.value ?: return null
            val revisionNode = snap["revision"] as? JsonObject ?: return null
            val kind = (revisionNode["kind"] as? JsonString)?.value ?: return null
            val revisionKind: String
            val revisionId: String
            val correctionId: Long?
            when (kind) {
                "ORIGINAL" -> { revisionKind = "ORIGINAL"; revisionId = ""; correctionId = null }
                "CORRECTION" -> {
                    revisionKind = "CORRECTION"
                    correctionId = (revisionNode["correctionId"] as? JsonString)?.value?.toLongOrNull() ?: return null
                    revisionId = correctionId.toString()
                }
                else -> return null
            }
            val stored = byKey.remove(Triple(sourceId, revisionKind, revisionId)) ?: return null
            val entry = dao.findMemoryEntry(sourceId) ?: return null
            val textVersion = if (correctionId == null) null else {
                val correction = dao.findCorrection(sourceId, correctionId) ?: return null
                MemoryTextVersion(sourceId, correction.correctedText, correction.correctionId, correction.modifiedAtEpochMillis)
            }
            val ranges = parseRanges(stored.authorizedRangesJson) ?: return null
            val memory = MemoryEntry(
                id = entry.id,
                rawText = entry.rawText,
                sourceType = entry.sourceType,
                capturedAtEpochMillis = entry.capturedAtEpochMillis,
                timezoneId = entry.timezoneId,
                status = CaptureStatus.SAVED,
            )
            val source = try { InformationExtractionSource.from(memory, textVersion, ranges) }
            catch (_: IllegalArgumentException) { return null }
            if (!source.matches(stored) || !snapshotMatches(snap, source.snapshot, stored.authorizedRangesJson)) return null
            sources += source
        }
        if (byKey.isNotEmpty()) return null
        return try {
            InformationExtractionRequest(parent.requestId, parent.extractionId, sources, version.schemaVersion)
        } catch (_: IllegalArgumentException) { null }
    }

    private suspend fun isCurrent(source: InformationProposalSourceEntity): Boolean {
        val entry = dao.findMemoryEntry(source.sourceEntryId) ?: return false
        if (entry.sourceType.name != source.sourceType || entry.capturedAtEpochMillis != source.capturedAtEpochMillis ||
            entry.timezoneId != source.timezoneId
        ) return false
        val latest = dao.findCurrentCorrection(entry.id)
        val text = when (source.revisionKind) {
            "ORIGINAL" -> if (source.revisionId.isEmpty() && latest == null) entry.rawText else return false
            "CORRECTION" -> {
                val id = source.revisionId.toLongOrNull() ?: return false
                if (latest?.correctionId != id) return false
                latest.correctedText
            }
            else -> return false
        }
        return text.length == source.textLengthUtf16 && sha256(text) == source.textSha256
    }

    private fun isSingleProposalEdit(currentJson: String, editedJson: String, targetId: String): Boolean {
        val before = parseObject(currentJson) ?: return false
        val after = parseObject(editedJson) ?: return false
        if (before.withoutProposals() != after.withoutProposals()) return false
        val beforeItems = proposalMap(before) ?: return false
        val afterItems = proposalMap(after) ?: return false
        if (beforeItems.keys != afterItems.keys || targetId !in beforeItems.keys) return false
        if (beforeItems.any { (id, value) -> id != targetId && afterItems[id] != value }) return false
        val oldTarget = beforeItems.getValue(targetId)
        val newTarget = afterItems.getValue(targetId)
        if ((oldTarget["category"] as? JsonString)?.value != (newTarget["category"] as? JsonString)?.value) return false
        return oldTarget != newTarget
    }

    private fun parseProposalIdentities(json: String): Set<String>? = parseObject(json)?.let(::proposalIds)?.toSet()

    private fun proposalIds(root: JsonObject): List<String>? {
        val list = root["proposals"] as? JsonArray ?: return null
        val ids = list.values.map { raw ->
            ((raw as? JsonObject)?.get("proposalId") as? JsonString)?.value ?: return null
        }
        return ids.takeIf { it.toSet().size == it.size }
    }

    private fun proposalMap(root: JsonObject): Map<String, JsonObject>? {
        val list = root["proposals"] as? JsonArray ?: return null
        val result = linkedMapOf<String, JsonObject>()
        for (raw in list.values) {
            val item = raw as? JsonObject ?: return null
            val id = (item["proposalId"] as? JsonString)?.value ?: return null
            if (result.put(id, item) != null) return null
        }
        return result
    }

    private fun parseObject(json: String): JsonObject? = try { BoundedJson(json).parse() as? JsonObject }
    catch (_: RuntimeException) { null }

    private fun JsonObject.withoutProposals() = JsonObject(fields.filterKeys { it != "proposals" })
    private fun JsonObject.withProposals(nodes: List<JsonObject>): JsonObject =
        JsonObject(LinkedHashMap(fields).apply { put("proposals", JsonArray(nodes)) })

    private fun serialize(value: JsonValue): String = when (value) {
        is JsonObject -> value.fields.entries.joinToString(prefix = "{", postfix = "}") {
            "${serialize(JsonString(it.key))}:${serialize(it.value)}"
        }
        is JsonArray -> value.values.joinToString(prefix = "[", postfix = "]", transform = ::serialize)
        is JsonString -> buildString {
            append('"')
            value.value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
        is JsonNumber -> value.value.toPlainString()
        is com.yezvi.assistant.data.intelligence.validation.JsonBoolean -> value.value.toString()
        com.yezvi.assistant.data.intelligence.validation.JsonNull -> "null"
    }

    private fun parseRanges(json: String): List<Utf16TextRange>? {
        val values = try { BoundedJson(json).parse() as? JsonArray } catch (_: RuntimeException) { null } ?: return null
        val ranges = values.values.map { raw ->
            val pair = raw as? JsonArray ?: return null
            if (pair.values.size != 2) return null
            val start = (pair.values[0] as? JsonNumber)?.value?.toInt() ?: return null
            val end = (pair.values[1] as? JsonNumber)?.value?.toInt() ?: return null
            try { Utf16TextRange(start, end) } catch (_: IllegalArgumentException) { return null }
        }
        return ranges.takeIf { it.isNotEmpty() }
    }

    private fun InformationExtractionSource.toEntity(
        identity: ProposalIdentity,
        version: Int,
    ): InformationProposalSourceEntity {
        val revision = when (val value = snapshot.revision) {
            SourceTextRevision.Original -> "ORIGINAL" to ""
            is SourceTextRevision.Correction -> "CORRECTION" to value.correctionId.toString()
        }
        val ranges = fragments.joinToString(prefix = "[", postfix = "]") {
            "[${it.range.startUtf16},${it.range.endUtf16}]"
        }
        return InformationProposalSourceEntity(
            identity.extractionId, identity.proposalId, version, snapshot.sourceEntryId,
            revision.first, revision.second, snapshot.sourceType.name, snapshot.capturedAtEpochMillis,
            snapshot.timezoneId, snapshot.textSha256, snapshot.textLengthUtf16, ranges,
        )
    }

    private fun InformationExtractionSource.matches(row: InformationProposalSourceEntity): Boolean =
        snapshot.sourceEntryId == row.sourceEntryId && snapshot.sourceType.name == row.sourceType &&
            snapshot.capturedAtEpochMillis == row.capturedAtEpochMillis && snapshot.timezoneId == row.timezoneId &&
            snapshot.textSha256 == row.textSha256 && snapshot.textLengthUtf16 == row.textLengthUtf16 &&
            when (val revision = snapshot.revision) {
                SourceTextRevision.Original -> row.revisionKind == "ORIGINAL" && row.revisionId.isEmpty()
                is SourceTextRevision.Correction -> row.revisionKind == "CORRECTION" && row.revisionId == revision.correctionId.toString()
            } && fragments.joinToString(prefix = "[", postfix = "]") {
                "[${it.range.startUtf16},${it.range.endUtf16}]"
            } == row.authorizedRangesJson

    private fun sameSourceRows(
        left: List<InformationProposalSourceEntity>,
        right: List<InformationProposalSourceEntity>,
    ): Boolean {
        fun List<InformationProposalSourceEntity>.canonical() = sortedWith(
            compareBy<InformationProposalSourceEntity>({ it.sourceEntryId }, { it.revisionKind }, { it.revisionId }),
        )
        return left.canonical() == right.canonical()
    }

    /** Replays every durable decision through F4.06 instead of trusting the state projection. */
    private fun historyMatches(
        identity: ProposalIdentity,
        rows: List<InformationProposalDecisionEntity>,
        readinessByVersion: Map<Int, ProposalReviewReadiness>,
        projectedState: ProposalReviewState,
        currentVersion: Int,
    ): Boolean {
        val initialReadiness = readinessByVersion[1] ?: return false
        var lifecycle = try { ProposalLifecycle.candidate(identity, initialReadiness.sourceRevisions) }
        catch (_: IllegalArgumentException) { return false }
        for (row in rows) {
            if (row.version !in 1..currentVersion) return false
            val readiness = readinessByVersion[row.version] ?: return false
            val command = try {
                when (ProposalTransitionAction.valueOf(row.action)) {
                    ProposalTransitionAction.SUBMIT_FOR_REVIEW -> {
                        if (row.version != 1) return false
                        ProposalLifecycleCommand.SubmitForReview(row.actionId, row.occurredAtEpochMillis, readiness)
                    }
                    ProposalTransitionAction.CONFIRM -> ProposalLifecycleCommand.Confirm(
                        row.actionId, row.occurredAtEpochMillis, ExplicitUserReviewAction.afterExplicitReview(row.actionId),
                    )
                    ProposalTransitionAction.REJECT -> ProposalLifecycleCommand.Reject(
                        row.actionId, row.occurredAtEpochMillis, ExplicitUserReviewAction.afterExplicitReview(row.actionId),
                    )
                    ProposalTransitionAction.EXPIRE -> ProposalLifecycleCommand.Expire(
                        row.actionId, row.occurredAtEpochMillis, ExplicitUserReviewAction.afterExplicitReview(row.actionId),
                    )
                    ProposalTransitionAction.REACTIVATE -> ProposalLifecycleCommand.Reactivate(
                        row.actionId, row.occurredAtEpochMillis, ExplicitUserReviewAction.afterExplicitReview(row.actionId), readiness,
                    )
                }
            } catch (_: IllegalArgumentException) { return false }
            val applied = lifecycle.transition(command) as? ProposalTransitionResult.Applied ?: return false
            val event = applied.event
            if (event.actionId != row.actionId || event.action.name != row.action || event.actor.name != row.actor ||
                event.from.name != row.fromState || event.to.name != row.toState ||
                event.occurredAtEpochMillis != row.occurredAtEpochMillis || row.version !in 1..currentVersion
            ) return false
            lifecycle = applied.lifecycle
        }
        return rows.isNotEmpty() && lifecycle.state == projectedState
    }

    private fun snapshotMatches(node: JsonObject, snapshot: ExtractionSourceSnapshot, storedRanges: String): Boolean {
        val revision = node["revision"] as? JsonObject ?: return false
        val ranges = (node["authorizedRanges"] as? JsonArray)?.values ?: return false
        val rangeParts = ArrayList<String>(ranges.size)
        for (raw in ranges) {
            val range = raw as? JsonObject ?: return false
            val start = try { (range["startUtf16"] as? JsonNumber)?.value?.intValueExact() ?: return false }
            catch (_: ArithmeticException) { return false }
            val end = try { (range["endUtf16"] as? JsonNumber)?.value?.intValueExact() ?: return false }
            catch (_: ArithmeticException) { return false }
            rangeParts += "[$start,$end]"
        }
        val rangeText = rangeParts.joinToString(prefix = "[", postfix = "]")
        val snapshotSource = snapshot
        val revisionMatches = when (val value = snapshotSource.revision) {
            SourceTextRevision.Original -> (revision["kind"] as? JsonString)?.value == "ORIGINAL"
            is SourceTextRevision.Correction -> (revision["kind"] as? JsonString)?.value == "CORRECTION" &&
                (revision["correctionId"] as? JsonString)?.value == value.correctionId.toString()
        }
        return revisionMatches &&
            (node["sourceEntryId"] as? JsonString)?.value == snapshotSource.sourceEntryId &&
            (node["sourceType"] as? JsonString)?.value == snapshotSource.sourceType.name &&
            (node["capturedAtEpochMillis"] as? JsonString)?.value == snapshotSource.capturedAtEpochMillis.toString() &&
            (node["timezoneId"] as? JsonString)?.value == snapshotSource.timezoneId &&
            (node["textSha256"] as? JsonString)?.value == snapshotSource.textSha256 &&
            (node["textLengthUtf16"] as? JsonNumber)?.value?.toInt() == snapshotSource.textLengthUtf16 &&
            rangeText == storedRanges
    }

    private fun ProposalLifecycleEvent.toEntity(
        identity: ProposalIdentity,
        version: Int,
        sequence: Int,
    ) = InformationProposalDecisionEntity(
        identity.extractionId, identity.proposalId, actionId, sequence, version, action.name, actor.name,
        from.name, to.name, occurredAtEpochMillis,
    )

    private fun hostSubmitActionId(identity: ProposalIdentity): String =
        "submit-${sha256("${identity.extractionId}/${identity.proposalId}").take(48)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun String.toReviewState() = ProposalReviewState.valueOf(this)
    private fun String.toExtractionOutcome() = ExtractionOutcome.valueOf(this)

    private fun rejected(reason: ProposalPersistenceRejection) = ProposalPersistenceResult.Rejected(reason)
    private fun loadRejected() = ProposalReviewLoadResult.Rejected(ProposalPersistenceRejection.STORED_DOCUMENT_INVALID)

    private data class CheckedDocument(
        val validation: ProposalValidationResult.Valid,
        val temporal: TemporalResolutionResult.Completed,
    )
}

private fun PersistedProposalAction.transitionAction(): ProposalTransitionAction = when (this) {
    PersistedProposalAction.CONFIRM -> ProposalTransitionAction.CONFIRM
    PersistedProposalAction.REJECT -> ProposalTransitionAction.REJECT
    PersistedProposalAction.EXPIRE -> ProposalTransitionAction.EXPIRE
    PersistedProposalAction.REACTIVATE -> ProposalTransitionAction.REACTIVATE
}

internal sealed interface PreparedProposalSubmission {
    data class Ready(val batch: InitialProposalBatch) : PreparedProposalSubmission
    data class Rejected(val reason: ProposalPersistenceRejection) : PreparedProposalSubmission
}
