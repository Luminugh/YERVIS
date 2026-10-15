package com.yezvi.assistant.intelligence

import com.yezvi.assistant.data.intelligence.temporal.DeterministicTemporalExpressionResolver
import com.yezvi.assistant.data.intelligence.validation.BoundedJson
import com.yezvi.assistant.data.intelligence.validation.StrictInformationProposalValidator
import com.yezvi.assistant.data.intelligence.validation.a
import com.yezvi.assistant.data.intelligence.validation.obj
import com.yezvi.assistant.data.intelligence.validation.o
import com.yezvi.assistant.data.intelligence.validation.s
import com.yezvi.assistant.domain.intelligence.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ProposalLifecycleTest {
    private val validator = StrictInformationProposalValidator()
    private val resolver = DeterministicTemporalExpressionResolver()

    @Test fun extractorAndValidatorsCanOnlyReachProposedUntilTheUserActs() {
        val completedTask = fixture("06-tarea-completada")
        assertEquals("COMPLETED", proposalData(completedTask).o("state").s("value"))
        val (lifecycle, readiness) = candidate(completedTask)

        assertEquals(ProposalReviewState.CANDIDATE, lifecycle.state)
        val withoutGate = lifecycle.transition(ProposalLifecycleCommand.SubmitForReview("submit-missing", 10L, null))
        assertEquals(ProposalTransitionRejection.VALIDATION_REQUIRED, (withoutGate as ProposalTransitionResult.Rejected).reason)

        val submitted = lifecycle.transition(ProposalLifecycleCommand.SubmitForReview("submit-task", 11L, readiness))
            as ProposalTransitionResult.Applied
        assertEquals(ProposalReviewState.PROPOSED, submitted.lifecycle.state)
        assertEquals(ProposalTransitionActor.HOST, submitted.event.actor)
        val cannotConfirmCandidate = lifecycle.transition(ProposalLifecycleCommand.Confirm(
            "confirm-too-early", 12L, ExplicitUserReviewAction.afterExplicitReview("confirm-too-early"),
        ))
        assertEquals(ProposalTransitionRejection.INVALID_TRANSITION,
            (cannotConfirmCandidate as ProposalTransitionResult.Rejected).reason)

        val missingUserAction = submitted.lifecycle.transition(ProposalLifecycleCommand.Confirm(
            "confirm-without-ui-action", 13L, ExplicitUserReviewAction.afterExplicitReview("different-action"),
        ))
        assertEquals(ProposalTransitionRejection.EXPLICIT_USER_ACTION_REQUIRED,
            (missingUserAction as ProposalTransitionResult.Rejected).reason)
    }

    @Test fun userConfirmationRejectionAndExpirationAreExplicitAndKeepAnAppendOnlyHistory() {
        val (candidate, readiness) = candidate(fixture("01-multiple"))
        val proposed = submit(candidate, readiness)
        val confirmed = proposed.transition(ProposalLifecycleCommand.Confirm(
            "user-confirm-1", 20L, ExplicitUserReviewAction.afterExplicitReview("user-confirm-1"),
        )) as ProposalTransitionResult.Applied
        assertEquals(ProposalReviewState.CONFIRMED, confirmed.lifecycle.state)
        assertEquals(ProposalReviewState.PROPOSED, confirmed.event.from)
        assertEquals(ProposalTransitionActor.USER, confirmed.event.actor)

        val expired = confirmed.lifecycle.transition(ProposalLifecycleCommand.Expire(
            "user-expire-1", 30L, ExplicitUserReviewAction.afterExplicitReview("user-expire-1"),
        )) as ProposalTransitionResult.Applied
        assertEquals(ProposalReviewState.EXPIRED, expired.lifecycle.state)
        assertEquals(listOf(ProposalReviewState.CANDIDATE, ProposalReviewState.PROPOSED,
            ProposalReviewState.CONFIRMED, ProposalReviewState.EXPIRED),
            listOf(expired.lifecycle.history.first().from) + expired.lifecycle.history.map { it.to })

        val (eventCandidate, eventReadiness) = candidate(fixture("04-evento-ambiguo"))
        val rejected = eventCandidate.transition(ProposalLifecycleCommand.Reject(
            "user-reject-1", 40L, ExplicitUserReviewAction.afterExplicitReview("user-reject-1"),
        ))
        assertEquals(ProposalTransitionRejection.INVALID_TRANSITION,
            (rejected as ProposalTransitionResult.Rejected).reason)
        val proposedForReject = submit(eventCandidate, eventReadiness)
        val rejection = proposedForReject.transition(ProposalLifecycleCommand.Reject(
            "user-reject-2", 41L, ExplicitUserReviewAction.afterExplicitReview("user-reject-2"),
        )) as ProposalTransitionResult.Applied
        assertEquals(ProposalReviewState.REJECTED, rejection.lifecycle.state)
        val cannotConfirmRejected = rejection.lifecycle.transition(ProposalLifecycleCommand.Confirm(
                "user-confirm-rejected", 42L,
                ExplicitUserReviewAction.afterExplicitReview("user-confirm-rejected"),
            )) as ProposalTransitionResult.Rejected
        assertEquals(ProposalTransitionRejection.INVALID_TRANSITION, cannotConfirmRejected.reason)
        val cannotReactivateRejected = rejection.lifecycle.transition(ProposalLifecycleCommand.Reactivate(
                "reactivate-rejected", 43L,
                ExplicitUserReviewAction.afterExplicitReview("reactivate-rejected"),
                eventReadiness,
            )) as ProposalTransitionResult.Rejected
        assertEquals(ProposalTransitionRejection.INVALID_TRANSITION, cannotReactivateRejected.reason)
    }

    @Test fun retriesAreIdempotentAndReusingAnActionIdForAnotherDecisionIsRejected() {
        val (candidate, readiness) = candidate(fixture("01-multiple"))
        val command = ProposalLifecycleCommand.SubmitForReview("submit-same", 50L, readiness)
        val first = candidate.transition(command) as ProposalTransitionResult.Applied
        val retry = first.lifecycle.transition(command) as ProposalTransitionResult.AlreadyApplied
        assertEquals(first.lifecycle.state, retry.lifecycle.state)
        assertEquals(1, retry.lifecycle.history.size)

        val conflict = first.lifecycle.transition(ProposalLifecycleCommand.Confirm(
            "submit-same", 50L, ExplicitUserReviewAction.afterExplicitReview("submit-same"),
        )) as ProposalTransitionResult.Rejected
        assertEquals(ProposalTransitionRejection.IDEMPOTENCY_KEY_REUSED, conflict.reason)
        val repeatedConfirm = first.lifecycle.transition(ProposalLifecycleCommand.Confirm(
            "confirm-same", 51L, ExplicitUserReviewAction.afterExplicitReview("confirm-same"),
        )) as ProposalTransitionResult.Applied
        assertTrue(repeatedConfirm.lifecycle.transition(ProposalLifecycleCommand.Confirm(
            "confirm-new-id", 52L, ExplicitUserReviewAction.afterExplicitReview("confirm-new-id"),
        )) is ProposalTransitionResult.NoChange)
        assertEquals(2, repeatedConfirm.lifecycle.history.size)
    }

    @Test fun reviewGateRejectsDifferentF405DocumentAndLifecycleRejectsDifferentProvenance() {
        val task = fixture("06-tarea-completada")
        val (lifecycle, readiness) = candidate(task)
        val other = fixture("02-rutina-semanal")
        val otherRaw = UnvalidatedProposalDocument("1.0.0", other.outcome, other.json)
        val otherTemporal = resolver.resolve(other.request, otherRaw) as TemporalResolutionResult.Completed
        val taskValidation = validator.validate(task.request,
            UnvalidatedProposalDocument("1.0.0", task.outcome, task.json)) as ProposalValidationResult.Valid
        assertNull(ProposalReviewReadiness.afterValidationAndTemporalResolution(
            readiness.identity, readiness.sourceRevisions, taskValidation, otherTemporal,
        ))

        val wrongSources = readiness.sourceRevisions.map { ProposalSourceRevision("unrelated-source", it.revision) }
        val mismatchedGate = requireNotNull(ProposalReviewReadiness.afterValidationAndTemporalResolution(
            readiness.identity, wrongSources, taskValidation,
            resolver.resolve(task.request, UnvalidatedProposalDocument("1.0.0", task.outcome, task.json))
                as TemporalResolutionResult.Completed,
        ))
        val result = lifecycle.transition(ProposalLifecycleCommand.SubmitForReview("submit-wrong-source", 55L, mismatchedGate))
            as ProposalTransitionResult.Rejected
        assertEquals(ProposalTransitionRejection.PROVENANCE_MISMATCH, result.reason)
    }

    @Test fun expiredProposalCanBeReactivatedOnlyByTheUserAfterRevalidation() {
        val (candidate, readiness) = candidate(fixture("02-rutina-semanal"))
        val proposed = submit(candidate, readiness)
        val expired = proposed.transition(ProposalLifecycleCommand.Expire(
            "expire-routine", 60L, ExplicitUserReviewAction.afterExplicitReview("expire-routine"),
        )) as ProposalTransitionResult.Applied
        val reactivated = expired.lifecycle.transition(ProposalLifecycleCommand.Reactivate(
            "reactivate-routine", 70L, ExplicitUserReviewAction.afterExplicitReview("reactivate-routine"), readiness,
        )) as ProposalTransitionResult.Applied
        assertEquals(ProposalReviewState.PROPOSED, reactivated.lifecycle.state)
        assertEquals(listOf(ProposalTransitionAction.SUBMIT_FOR_REVIEW, ProposalTransitionAction.EXPIRE,
            ProposalTransitionAction.REACTIVATE), reactivated.lifecycle.history.map { it.action })
        assertTrue(reactivated.lifecycle.transition(ProposalLifecycleCommand.Reactivate(
                "reactivate-rejected", 71L,
                ExplicitUserReviewAction.afterExplicitReview("reactivate-rejected"), readiness,
            )) is ProposalTransitionResult.NoChange)
    }

    @Test fun observedPatternRemainsAProposalAndRequiresUserConfirmationLikeDeclaredRoutine() {
        val observed = fixture("03-patron-observado")
        val (candidate, readiness) = candidate(observed)
        val submitted = submit(candidate, readiness)
        assertEquals(ProposalReviewState.PROPOSED, submitted.state)
        assertEquals("OBSERVED", proposalData(observed).o("patternKind").s("value"))
        val explicitlyConfirmed = submitted.transition(ProposalLifecycleCommand.Confirm(
            "confirm-observed-explicitly", 80L,
            ExplicitUserReviewAction.afterExplicitReview("confirm-observed-explicitly"),
        )) as ProposalTransitionResult.Applied
        assertEquals(ProposalReviewState.CONFIRMED, explicitlyConfirmed.lifecycle.state)
        // The only confirmation path is an explicit user command; F4.04/F4.05 readiness alone did not do it.
        assertEquals(ProposalReviewState.PROPOSED, submitted.state)

        val declared = fixture("02-rutina-semanal")
        assertEquals("DECLARED", proposalData(declared).o("patternKind").s("value"))
        assertEquals(ProposalReviewState.PROPOSED, submit(candidate(declared).first, candidate(declared).second).state)
    }

    @Test fun occurrenceUnknownIsDifferentFromNoOccurrenceRecordAndAbsenceNeverMeansSkipped() {
        assertEquals(PatternOccurrenceRecord.Reported(PatternOccurrenceState.UNKNOWN),
            PatternOccurrenceRecord.Reported(PatternOccurrenceState.UNKNOWN))
        assertNotEquals(PatternOccurrenceRecord.NoRecordedEvidence,
            PatternOccurrenceRecord.Reported(PatternOccurrenceState.UNKNOWN))
        assertFalse(PatternOccurrenceState.SKIPPED_EXPLICITLY == PatternOccurrenceState.UNKNOWN)
        assertEquals(setOf("COMPLETED", "SKIPPED_EXPLICITLY", "POSTPONED", "CANCELLED", "UNKNOWN"),
            PatternOccurrenceState.values().map { it.name }.toSet())
    }

    @Test fun contradictoryUpdateIsASeparateProposalAndDoesNotRewritePriorHistory() {
        val prior = fixture("13-descripcion-atribuida")
        val (priorCandidate, priorReadiness) = candidate(prior)
        val priorConfirmed = submit(priorCandidate, priorReadiness).transition(ProposalLifecycleCommand.Confirm(
            "confirm-prior", 90L, ExplicitUserReviewAction.afterExplicitReview("confirm-prior"),
        )) as ProposalTransitionResult.Applied

        val update = fixture("08-actualizacion-contradictoria")
        val (newCandidate, newReadiness) = candidate(update)
        assertNotEquals(priorConfirmed.lifecycle.identity, newCandidate.identity)
        val updateProposed = submit(newCandidate, newReadiness)
        assertEquals(ProposalReviewState.CONFIRMED, priorConfirmed.lifecycle.state)
        assertEquals(listOf(ProposalTransitionAction.SUBMIT_FOR_REVIEW, ProposalTransitionAction.CONFIRM),
            priorConfirmed.lifecycle.history.map { it.action })
        assertEquals(ProposalReviewState.PROPOSED, updateProposed.state)
        assertTrue(update.json.contains("CONTRADICTS") || update.json.contains("UPDATES"))
    }

    @Test fun activityStatusesRemainSeparateFromProposalReviewStatusesAndMatchSchemaV1Vocabulary() {
        val schema = BoundedJson(File(F402Fixtures.root, "proposals.schema.json").readText()).parse()
        val schemaEnums = collectEnumArrays(schema).toSet()
        assertTrue(schemaEnums.contains(TaskActivityState.values().map { it.name }.toSet()))
        assertTrue(schemaEnums.contains(EventActivityState.values().map { it.name }.toSet()))
        assertTrue(schemaEnums.contains(ProjectActivityState.values().map { it.name }.toSet()))
        assertTrue(schemaEnums.contains(RoutinePatternKind.values().map { it.name }.toSet()))
        assertTrue(schemaEnums.contains(PatternOccurrenceState.values().map { it.name }.toSet()))
        assertTrue(schemaEnums.contains(RoutineExceptionEffect.values().map { it.name }.toSet()))
        assertTrue(schemaEnums.contains(TrackingIntent.values().map { it.name }.toSet()))
        assertEquals(ProposalReviewState.PROPOSED.name, "PROPOSED")
        assertNotEquals(TaskActivityState.COMPLETED.name, ProposalReviewState.CONFIRMED.name)
    }

    @Test fun domainLifecycleHasNoRoomNetworkAlarmOrAndroidSideEffects() {
        val projectRoot = F402Fixtures.root.parentFile!!.parentFile!!.parentFile!!.parentFile!!
        val path = File(projectRoot, "app/src/main/java/com/yezvi/assistant/domain/intelligence/ProposalLifecycle.kt")
        val source = path.readText()
        assertFalse(Regex("(?m)^import (android\\.|androidx\\.|java\\.net\\.|okhttp|retrofit|com\\.yezvi\\.assistant\\.data\\.(local|repository))")
            .containsMatchIn(source))
        assertFalse(source.contains("AlarmManager"))
    }

    private fun submit(lifecycle: ProposalLifecycle, readiness: ProposalReviewReadiness) =
        (lifecycle.transition(ProposalLifecycleCommand.SubmitForReview(
            "submit-${lifecycle.identity.proposalId}", 100L, readiness,
        )) as ProposalTransitionResult.Applied).lifecycle

    private fun candidate(case: F402Fixtures.Case): Pair<ProposalLifecycle, ProposalReviewReadiness> {
        val idNode = BoundedJson(case.json).parse().obj().a("proposals").first().obj().s("proposalId")
        val identity = ProposalIdentity(case.request.extractionId, idNode)
        val sourceRevisions = case.request.sources.map {
            ProposalSourceRevision(it.snapshot.sourceEntryId, it.snapshot.revision)
        }
        val raw = UnvalidatedProposalDocument("1.0.0", case.outcome, case.json)
        val validation = validator.validate(case.request, raw) as ProposalValidationResult.Valid
        val temporal = resolver.resolve(case.request, raw) as TemporalResolutionResult.Completed
        val readiness = requireNotNull(ProposalReviewReadiness.afterValidationAndTemporalResolution(
            identity, sourceRevisions, validation, temporal,
        ))
        return ProposalLifecycle.candidate(identity, sourceRevisions) to readiness
    }

    private fun fixture(name: String) = F402Fixtures.cases.single { it.name == name }

    private fun proposalData(case: F402Fixtures.Case) =
        BoundedJson(case.json).parse().obj().a("proposals").first().obj().o("data")

    private fun collectEnumArrays(value: com.yezvi.assistant.data.intelligence.validation.JsonValue): List<Set<String>> = when (value) {
        is com.yezvi.assistant.data.intelligence.validation.JsonObject -> value.fields.flatMap { (key, child) ->
            val own = if (key == "enum" && child is com.yezvi.assistant.data.intelligence.validation.JsonArray) {
                listOf(child.values.map { (it as com.yezvi.assistant.data.intelligence.validation.JsonString).value }.toSet())
            } else emptyList()
            own + collectEnumArrays(child)
        }
        is com.yezvi.assistant.data.intelligence.validation.JsonArray -> value.values.flatMap(::collectEnumArrays)
        else -> emptyList()
    }
}
