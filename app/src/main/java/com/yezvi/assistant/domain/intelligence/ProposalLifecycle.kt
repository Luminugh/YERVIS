package com.yezvi.assistant.domain.intelligence

import java.util.Collections

/** Host-side review lifecycle. The v1 exchange JSON itself still only says PROPOSED. */
enum class ProposalReviewState { CANDIDATE, PROPOSED, CONFIRMED, REJECTED, EXPIRED }

/** Exact activity values carried by Schema 1.0.0; these are not review decisions. */
enum class TaskActivityState { PENDING, IN_PROGRESS, COMPLETED, CANCELLED, BLOCKED, POSTPONED, UNKNOWN }
enum class EventActivityState { TENTATIVE, SCHEDULED, HELD, CANCELLED, POSTPONED, UNKNOWN }
enum class ProjectActivityState { PROPOSED, ACTIVE, PAUSED, COMPLETED, CANCELLED, UNKNOWN }
enum class RoutinePatternKind { DECLARED, OBSERVED }
enum class PatternOccurrenceState { COMPLETED, SKIPPED_EXPLICITLY, POSTPONED, CANCELLED, UNKNOWN }
enum class RoutineExceptionEffect { PAUSED, CANCELLED_OCCURRENCE, RESCHEDULED, FREQUENCY_CHANGE }
enum class TrackingIntent { REQUESTED, DECLINED }

/** Identity is local to one extraction; it is not a Room ID or a durable person identity. */
data class ProposalIdentity(val extractionId: String, val proposalId: String) {
    init {
        require(extractionId.matches(ID_PATTERN) && proposalId.matches(ID_PATTERN))
    }

    private companion object { val ID_PATTERN = Regex("[a-z][a-z0-9_-]{0,63}") }
}

/** Provenance pointer only. Field-level quotes/ranges stay in the immutable validated document. */
data class ProposalSourceRevision(val sourceEntryId: String, val revision: SourceTextRevision) {
    init { require(sourceEntryId.isNotBlank() && sourceEntryId.codePointCount(0, sourceEntryId.length) <= 128) }
}

/** Marker supplied only by an explicit user review action; it carries no content or private text. */
class ExplicitUserReviewAction private constructor(val actionId: String) {
    companion object {
        fun afterExplicitReview(actionId: String): ExplicitUserReviewAction {
            require(actionId.matches(ACTION_ID_PATTERN))
            return ExplicitUserReviewAction(actionId)
        }

        private val ACTION_ID_PATTERN = Regex("[a-z][a-z0-9_-]{0,63}")
    }
}

/**
 * Proof that the exact same document passed F4.04 and F4.05. This only permits presentation
 * as a proposal; it never confirms the proposal. The caller must derive identity/source pointers
 * from the validated document and the authorized request.
 */
class ProposalReviewReadiness private constructor(
    val identity: ProposalIdentity,
    sourceRevisions: Collection<ProposalSourceRevision>,
) {
    val sourceRevisions: Set<ProposalSourceRevision> =
        Collections.unmodifiableSet(LinkedHashSet(sourceRevisions))

    companion object {
        fun afterValidationAndTemporalResolution(
            identity: ProposalIdentity,
            sourceRevisions: Collection<ProposalSourceRevision>,
            validation: ProposalValidationResult.Valid,
            temporal: TemporalResolutionResult.Completed,
        ): ProposalReviewReadiness? {
            if (!validation.document.requiresHumanReview || validation.document.proposalCount < 1) return null
            if (validation.document.json != temporal.validatedDocument.json ||
                validation.document.outcome != temporal.validatedDocument.outcome
            ) return null
            if (sourceRevisions.isEmpty() || sourceRevisions.toSet().size != sourceRevisions.size) return null
            return ProposalReviewReadiness(identity, sourceRevisions)
        }
    }
}

enum class ProposalTransitionAction { SUBMIT_FOR_REVIEW, CONFIRM, REJECT, EXPIRE, REACTIVATE }
enum class ProposalTransitionActor { HOST, USER }

data class ProposalLifecycleEvent(
    val actionId: String,
    val action: ProposalTransitionAction,
    val actor: ProposalTransitionActor,
    val from: ProposalReviewState,
    val to: ProposalReviewState,
    val occurredAtEpochMillis: Long,
)

sealed interface ProposalLifecycleCommand {
    val actionId: String
    val occurredAtEpochMillis: Long

    data class SubmitForReview(
        override val actionId: String,
        override val occurredAtEpochMillis: Long,
        val readiness: ProposalReviewReadiness?,
    ) : ProposalLifecycleCommand

    data class Confirm(
        override val actionId: String,
        override val occurredAtEpochMillis: Long,
        val explicitAction: ExplicitUserReviewAction,
    ) : ProposalLifecycleCommand

    data class Reject(
        override val actionId: String,
        override val occurredAtEpochMillis: Long,
        val explicitAction: ExplicitUserReviewAction,
    ) : ProposalLifecycleCommand

    data class Expire(
        override val actionId: String,
        override val occurredAtEpochMillis: Long,
        val explicitAction: ExplicitUserReviewAction,
    ) : ProposalLifecycleCommand

    data class Reactivate(
        override val actionId: String,
        override val occurredAtEpochMillis: Long,
        val explicitAction: ExplicitUserReviewAction,
        val readiness: ProposalReviewReadiness?,
    ) : ProposalLifecycleCommand
}

enum class ProposalTransitionRejection {
    INVALID_TRANSITION,
    VALIDATION_REQUIRED,
    PROVENANCE_MISMATCH,
    EXPLICIT_USER_ACTION_REQUIRED,
    IDEMPOTENCY_KEY_REUSED,
}

sealed interface ProposalTransitionResult {
    data class Applied(val lifecycle: ProposalLifecycle, val event: ProposalLifecycleEvent) : ProposalTransitionResult
    data class AlreadyApplied(val lifecycle: ProposalLifecycle, val event: ProposalLifecycleEvent) : ProposalTransitionResult
    data class NoChange(val lifecycle: ProposalLifecycle) : ProposalTransitionResult
    data class Rejected(val lifecycle: ProposalLifecycle, val reason: ProposalTransitionRejection) : ProposalTransitionResult
}

/** Immutable in-memory lifecycle. F4.06 does not persist or mutate proposal payloads. */
class ProposalLifecycle private constructor(
    val identity: ProposalIdentity,
    val state: ProposalReviewState,
    sourceRevisions: Collection<ProposalSourceRevision>,
    history: List<ProposalLifecycleEvent>,
) {
    val sourceRevisions: Set<ProposalSourceRevision> =
        Collections.unmodifiableSet(LinkedHashSet(sourceRevisions))
    val history: List<ProposalLifecycleEvent> = Collections.unmodifiableList(ArrayList(history))

    fun transition(command: ProposalLifecycleCommand): ProposalTransitionResult {
        val action = command.action()
        val actor = command.actor()
        if (!command.actionId.matches(ACTION_ID_PATTERN)) return rejected(ProposalTransitionRejection.INVALID_TRANSITION)

        history.firstOrNull { it.actionId == command.actionId }?.let { previous ->
            return if (previous.action == action && previous.actor == actor &&
                previous.occurredAtEpochMillis == command.occurredAtEpochMillis
            ) ProposalTransitionResult.AlreadyApplied(this, previous)
            else rejected(ProposalTransitionRejection.IDEMPOTENCY_KEY_REUSED)
        }

        val explicitAction = command.explicitActionOrNull()
        if (actor == ProposalTransitionActor.USER && explicitAction?.actionId != command.actionId) {
            return rejected(ProposalTransitionRejection.EXPLICIT_USER_ACTION_REQUIRED)
        }

        if (command is ProposalLifecycleCommand.SubmitForReview || command is ProposalLifecycleCommand.Reactivate) {
            val readiness = command.readinessOrNull()
                ?: return rejected(ProposalTransitionRejection.VALIDATION_REQUIRED)
            if (readiness.identity != identity || readiness.sourceRevisions != sourceRevisions) {
                return rejected(ProposalTransitionRejection.PROVENANCE_MISMATCH)
            }
        }

        val target = when (command) {
            is ProposalLifecycleCommand.SubmitForReview -> when (state) {
                ProposalReviewState.CANDIDATE -> ProposalReviewState.PROPOSED
                ProposalReviewState.PROPOSED -> return ProposalTransitionResult.NoChange(this)
                else -> return rejected(ProposalTransitionRejection.INVALID_TRANSITION)
            }
            is ProposalLifecycleCommand.Confirm -> when (state) {
                ProposalReviewState.PROPOSED -> ProposalReviewState.CONFIRMED
                ProposalReviewState.CONFIRMED -> return ProposalTransitionResult.NoChange(this)
                else -> return rejected(ProposalTransitionRejection.INVALID_TRANSITION)
            }
            is ProposalLifecycleCommand.Reject -> when (state) {
                ProposalReviewState.PROPOSED -> ProposalReviewState.REJECTED
                ProposalReviewState.REJECTED -> return ProposalTransitionResult.NoChange(this)
                else -> return rejected(ProposalTransitionRejection.INVALID_TRANSITION)
            }
            is ProposalLifecycleCommand.Expire -> when (state) {
                ProposalReviewState.PROPOSED, ProposalReviewState.CONFIRMED -> ProposalReviewState.EXPIRED
                ProposalReviewState.EXPIRED -> return ProposalTransitionResult.NoChange(this)
                else -> return rejected(ProposalTransitionRejection.INVALID_TRANSITION)
            }
            is ProposalLifecycleCommand.Reactivate -> when (state) {
                ProposalReviewState.EXPIRED -> ProposalReviewState.PROPOSED
                ProposalReviewState.PROPOSED -> return ProposalTransitionResult.NoChange(this)
                else -> return rejected(ProposalTransitionRejection.INVALID_TRANSITION)
            }
        }

        val event = ProposalLifecycleEvent(
            actionId = command.actionId,
            action = action,
            actor = actor,
            from = state,
            to = target,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
        )
        val updated = ProposalLifecycle(identity, target, sourceRevisions, history + event)
        return ProposalTransitionResult.Applied(updated, event)
    }

    override fun toString(): String = "ProposalLifecycle(identity=$identity, state=$state, historySize=${history.size})"

    private fun rejected(reason: ProposalTransitionRejection) = ProposalTransitionResult.Rejected(this, reason)

    companion object {
        private val ACTION_ID_PATTERN = Regex("[a-z][a-z0-9_-]{0,63}")

        fun candidate(
            identity: ProposalIdentity,
            sourceRevisions: Collection<ProposalSourceRevision>,
        ): ProposalLifecycle {
            require(sourceRevisions.isNotEmpty() && sourceRevisions.toSet().size == sourceRevisions.size)
            return ProposalLifecycle(identity, ProposalReviewState.CANDIDATE, sourceRevisions, emptyList())
        }

        /** Restores only the persisted projection; subsequent actions still pass through transition(). */
        internal fun restoreProjection(
            identity: ProposalIdentity,
            sourceRevisions: Collection<ProposalSourceRevision>,
            state: ProposalReviewState,
        ): ProposalLifecycle {
            require(sourceRevisions.isNotEmpty() && sourceRevisions.toSet().size == sourceRevisions.size)
            require(state != ProposalReviewState.CANDIDATE) { "Unsubmitted candidates are not persisted" }
            return ProposalLifecycle(identity, state, sourceRevisions, emptyList())
        }
    }
}

private fun ProposalLifecycleCommand.action(): ProposalTransitionAction = when (this) {
    is ProposalLifecycleCommand.SubmitForReview -> ProposalTransitionAction.SUBMIT_FOR_REVIEW
    is ProposalLifecycleCommand.Confirm -> ProposalTransitionAction.CONFIRM
    is ProposalLifecycleCommand.Reject -> ProposalTransitionAction.REJECT
    is ProposalLifecycleCommand.Expire -> ProposalTransitionAction.EXPIRE
    is ProposalLifecycleCommand.Reactivate -> ProposalTransitionAction.REACTIVATE
}

private fun ProposalLifecycleCommand.actor(): ProposalTransitionActor = when (this) {
    is ProposalLifecycleCommand.SubmitForReview -> ProposalTransitionActor.HOST
    else -> ProposalTransitionActor.USER
}

private fun ProposalLifecycleCommand.explicitActionOrNull(): ExplicitUserReviewAction? = when (this) {
    is ProposalLifecycleCommand.Confirm -> explicitAction
    is ProposalLifecycleCommand.Reject -> explicitAction
    is ProposalLifecycleCommand.Expire -> explicitAction
    is ProposalLifecycleCommand.Reactivate -> explicitAction
    is ProposalLifecycleCommand.SubmitForReview -> null
}

private fun ProposalLifecycleCommand.readinessOrNull(): ProposalReviewReadiness? = when (this) {
    is ProposalLifecycleCommand.SubmitForReview -> readiness
    is ProposalLifecycleCommand.Reactivate -> readiness
    else -> null
}

/** Distinguishes a cited UNKNOWN occurrence from no occurrence evidence at all. */
sealed interface PatternOccurrenceRecord {
    data class Reported(val state: PatternOccurrenceState) : PatternOccurrenceRecord
    data object NoRecordedEvidence : PatternOccurrenceRecord
}
