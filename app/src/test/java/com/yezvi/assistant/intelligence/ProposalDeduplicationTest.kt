package com.yezvi.assistant.intelligence

import com.yezvi.assistant.data.intelligence.deduplication.*
import com.yezvi.assistant.data.intelligence.validation.*
import com.yezvi.assistant.domain.intelligence.*
import org.junit.Assert.*
import org.junit.Test

class ProposalDeduplicationTest {
    private val canonicalizer = ProposalCanonicalizer()
    private fun fixture(prefix: String) = F402Fixtures.cases.first { it.name.startsWith(prefix) }
    private fun firstId(json: String) = BoundedJson(json).parse().obj().a("proposals").first().obj().s("proposalId")
    private fun canonical(json: String, id: String = firstId(json)) = canonicalizer.canonicalize(json, id)

    @Test fun allFourteenValidatedFixturesHaveReproduciblePerProposalIdentities() {
        val validator = StrictInformationProposalValidator()
        for (case in F402Fixtures.cases) {
            assertTrue(validator.validate(case.request, UnvalidatedProposalDocument("1.0.0", case.outcome, case.json)) is ProposalValidationResult.Valid)
            val ids = BoundedJson(case.json).parse().obj().a("proposals").map { it.obj().s("proposalId") }
            ids.forEach { id ->
                val a = canonical(case.json, id)
                val b = canonical(case.json, id)
                assertEquals(a.descriptor, b.descriptor)
                assertEquals(a.digest, b.digest)
                assertEquals(64, a.digest.length)
            }
        }
    }

    @Test fun correlationAndRenamedTransportReferencesDoNotChangeIdentity() {
        val original = fixture("01").json
        val renamed = original.replace("request-01-multiple", "request-other")
            .replace("extraction-01-multiple", "extraction-other").replace("\"ana\"", "\"person-renamed\"")
        val ids = BoundedJson(original).parse().obj().a("proposals").map { it.obj().s("proposalId") }
        ids.forEach { id -> assertEquals(canonical(original, id).descriptor,
            canonical(renamed, if (id == "ana") "person-renamed" else id).descriptor) }
    }

    @Test fun propertyOrderAndUnorderedArrayOrderDoNotChangeIdentity() {
        val json = fixture("02").json
        val sorted = canonicalJson(BoundedJson(json).parse())
        assertEquals(canonical(json).descriptor, canonical(sorted).descriptor)
    }

    @Test fun sourceIdentityIsNotATransportIdentifier() {
        val json = fixture("02").json
        assertNotEquals(canonical(json).descriptor, canonical(json.replace("s-weekly", "s-another")).descriptor)
    }

    @Test fun sourceRevisionCaptureZoneAndTypedRecurrenceRemainPartOfIdentity() {
        val json = fixture("02").json
        listOf(json.replace("1792083600000", "1792083600001"),
            json.replace("America/Lima", "America/Bogota"),
            json.replace("\"WEEKLY\"", "\"DAILY\""),
            json.replace("\"NEGATED\"", "\"AFFIRMED\""),
            json.replace("\"DECLARED\"", "\"OBSERVED\""))
            .forEach { assertNotEquals(canonical(json).descriptor, canonical(it).descriptor) }
    }

    @Test fun evidenceOffsetsAndExactUnicodeArePreserved() {
        val json = fixture("12").json
        val descriptor = canonical(json).descriptor
        assertTrue(descriptor.contains("UTF16_CODE_UNIT"))
        assertNotEquals(descriptor, canonical(json.replace("\"startUtf16\": 0", "\"startUtf16\": 1")).descriptor)
    }

    @Test fun referenceTargetContentAffectsReferencingProposalIdentity() {
        val json = fixture("01").json
        val changed = json.replace("\"value\": \"Ana\"", "\"value\": \"Elena\"")
        val ids = BoundedJson(json).parse().obj().a("proposals").map { it.obj().s("proposalId") }
        assertTrue(ids.any { it != "ana" && canonical(json, it).descriptor != canonical(changed, it).descriptor })
    }

    @Test fun exceptionTransportIdDoesNotChangeMeaningButExceptionMultiplicityDoes() {
        val json = fixture("02").json
        assertEquals(canonical(json).descriptor, canonical(json.replace("ex1", "ex9")).descriptor)
        val root = BoundedJson(json).parse().obj()
        val node = root.a("proposals").first().obj()
        val data = node.o("data")
        val exceptions = data.a("exceptions")
        val changed = JsonObject(root.fields + ("proposals" to JsonArray(listOf(JsonObject(node.fields +
            ("data" to JsonObject(data.fields + ("exceptions" to JsonArray(exceptions + exceptions)))))))))
        assertNotEquals(canonical(json).descriptor, canonical(canonicalJson(changed)).descriptor)
    }

    @Test fun equalHashDoesNotMakeDescriptorsEqual() {
        val injected = ProposalCanonicalizer { "a".repeat(64) }
        val a = fixture("02").json
        val b = a.replace("WEEKLY", "DAILY")
        val left = injected.canonicalize(a, firstId(a))
        val right = injected.canonicalize(b, firstId(b))
        assertEquals(left.digest, right.digest)
        assertNotEquals(left.descriptor, right.descriptor)
    }

    @Test fun descriptorDiagnosticsDoNotContainEvidenceOrHash() {
        val value = canonical(fixture("02").json)
        assertFalse(value.toString().contains(value.digest))
        assertFalse(value.toString().contains("estudio"))
    }

    @Test fun malformedOversizedAndUnknownSchemaAreControlled() {
        assertThrows(RuntimeException::class.java) { canonical("{") }
        assertThrows(RuntimeException::class.java) { canonical(" ".repeat(300000)) }
        assertThrows(IllegalArgumentException::class.java) { canonical(fixture("02").json.replace("1.0.0", "2.0.0")) }
    }

    private fun descriptor(category: String = "EVENT", data: String, relation: String = ""): CanonicalProposal {
        val raw = """{"proposal":{"category":"$category","data":$data$relation}}"""
        return CanonicalProposal(raw, proposalDigest(raw))
    }

    @Test fun sameTitleDifferentDateIsDistinctWithoutExplicitUpdateEvidence() {
        val a = descriptor(data = """{"title":{"value":"Reunión"},"startsAt":{"date":"2026-10-15"}}""")
        val b = descriptor(data = """{"title":{"value":"Reunión"},"startsAt":{"date":"2026-10-16"}}""")
        assertEquals(ProposalComparisonKind.DISTINCT, ConservativeProposalComparator().compare(a, b, false))
    }

    @Test fun differentParticipantsRemainDistinct() {
        val a = descriptor(data = """{"title":{"value":"Reunión"},"participants":["Ana"]}""")
        val b = descriptor(data = """{"title":{"value":"Reunión"},"participants":["Elena"]}""")
        assertEquals(ProposalComparisonKind.DISTINCT, ConservativeProposalComparator().compare(a, b, false))
    }

    @Test fun routineHabitAndObservedPatternAreNotInterchangeable() {
        val a = canonical(fixture("02").json)
        val b = canonical(fixture("03").json)
        assertEquals(ProposalComparisonKind.DISTINCT, ConservativeProposalComparator().compare(a, b, true))
        val habit = descriptor("HABIT", """{"title":{"value":"Estudio"}}""")
        assertEquals(ProposalComparisonKind.DISTINCT, ConservativeProposalComparator().compare(a, habit, false))
    }

    @Test fun explicitUpdateProducesDirectedCandidateNotConfirmation() {
        val a = descriptor("ROUTINE", """{"title":{"value":"Estudiar"},"recurrence":{"days":["MO"]}}""")
        val node = BoundedJson(a.descriptor).parse().obj().o("proposal")
        val relation = ",\"relations\":[{\"value\":{\"kind\":\"UPDATES\",\"targetProposalId\":${canonicalJson(node)}}}]"
        val b = descriptor("ROUTINE", """{"title":{"value":"Estudiar"},"recurrence":{"days":["WE"]}}""", relation)
        assertEquals(ProposalComparisonKind.UPDATE_PROPOSED, ConservativeProposalComparator().compare(a, b, false))
        assertEquals(ProposalComparisonKind.CONFLICT_REQUIRES_REVIEW, ConservativeProposalComparator().compare(a, b, true))
    }

    @Test fun exceptionAndScheduleChangeHaveSeparateCandidateKinds() {
        val a = descriptor(data = """{"title":{"value":"Visita"}}""")
        val node = BoundedJson(a.descriptor).parse().obj().o("proposal")
        val link = ",\"relations\":[{\"value\":{\"kind\":\"UPDATES\",\"targetProposalId\":${canonicalJson(node)}}}]"
        assertEquals(ProposalComparisonKind.EXCEPTION_PROPOSED, ConservativeProposalComparator().compare(a,
            descriptor(data = """{"title":{"value":"Visita"},"exceptions":["synthetic"]}""", relation = link), false))
        assertEquals(ProposalComparisonKind.RESCHEDULE_PROPOSED, ConservativeProposalComparator().compare(a,
            descriptor(data = """{"title":{"value":"Visita"},"startsAt":{"date":"2026-10-16"}}""", relation = link), false))
    }

    @Test fun contradictoryEvidenceIsAlwaysReviewableConflict() {
        val a = descriptor(data = """{"title":{"value":"Visita"}}""")
        val node = BoundedJson(a.descriptor).parse().obj().o("proposal")
        val link = ",\"relations\":[{\"value\":{\"kind\":\"CONTRADICTS\",\"targetProposalId\":${canonicalJson(node)}}}]"
        val b = descriptor(data = """{"title":{"value":"Visita cancelada"}}""", relation = link)
        assertEquals(ProposalComparisonKind.CONFLICT_REQUIRES_REVIEW, ConservativeProposalComparator().compare(a, b, false))
    }

    @Test fun dstOrAmbiguousTimeDoesNotPermitEquivalence() {
        val a = descriptor(data = """{"title":{"value":"Visita"}}""")
        val b = CanonicalProposal(a.descriptor.replace("{\"proposal\"", "{\"context\":1,\"proposal\""), "0".repeat(64))
        assertEquals(ProposalComparisonKind.DISTINCT, ConservativeProposalComparator().compare(a, b, true))
    }

    @Test fun symmetricRelationHasOneIdentityInEitherOrderAndIgnoresAttemptTime() {
        val a = ProposalVersionReference(ProposalIdentity("extraction-a", "person"), 1)
        val b = ProposalVersionReference(ProposalIdentity("extraction-b", "person"), 2)
        val first = relationEntity(a, b, ProposalComparisonKind.POSSIBLE_EQUIVALENT, "a", "b", 1)
        val reverse = relationEntity(b, a, ProposalComparisonKind.POSSIBLE_EQUIVALENT, "b", "a", 2)
        assertEquals(first.relationId, reverse.relationId)
        assertEquals(first.evidenceDigest, reverse.evidenceDigest)
    }

    @Test fun directedRelationAndVersionChangesHaveDifferentIdentities() {
        val a = ProposalVersionReference(ProposalIdentity("extraction-a", "routine"), 1)
        val b = ProposalVersionReference(ProposalIdentity("extraction-b", "routine"), 1)
        assertNotEquals(relationEntity(a, b, ProposalComparisonKind.UPDATE_PROPOSED, "a", "b", 1).relationId,
            relationEntity(b, a, ProposalComparisonKind.UPDATE_PROPOSED, "b", "a", 1).relationId)
        assertNotEquals(relationEntity(a, b, ProposalComparisonKind.UPDATE_PROPOSED, "a", "b", 1).relationId,
            relationEntity(a, b.copy(version = 2), ProposalComparisonKind.UPDATE_PROPOSED, "a", "b", 1).relationId)
    }

    @Test fun relationEndpointRolesCannotCollideWhenIdentifierStringsAreSwapped() {
        val a = ProposalVersionReference(ProposalIdentity("entry-a", "entry-b"), 1)
        val swapped = ProposalVersionReference(ProposalIdentity("entry-b", "entry-a"), 1)
        val b = ProposalVersionReference(ProposalIdentity("entry-c", "entry-d"), 1)
        assertNotEquals(relationEntity(a, b, ProposalComparisonKind.POSSIBLE_EQUIVALENT, "a", "b", 1).relationId,
            relationEntity(swapped, b, ProposalComparisonKind.POSSIBLE_EQUIVALENT, "a", "b", 1).relationId)
    }
}
