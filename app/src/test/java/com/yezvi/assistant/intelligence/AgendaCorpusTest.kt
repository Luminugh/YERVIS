package com.yezvi.assistant.intelligence

import com.yezvi.assistant.data.intelligence.deduplication.*
import com.yezvi.assistant.data.intelligence.temporal.DeterministicTemporalExpressionResolver
import com.yezvi.assistant.data.intelligence.validation.*
import com.yezvi.assistant.domain.intelligence.*
import com.yezvi.assistant.domain.memory.*
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Authored answers are inputs to component checks, NEVER evidence of AI quality. */
@RunWith(Parameterized::class)
class AgendaCorpusTest(private val scenarioId: String, private val filename: String, private val digest: String) {
    @Test fun authoredGoldenIsConsistentWithExistingComponents() {
        val bytes = File(AgendaCorpus.root, filename).readBytes()
        assertEquals(digest, AgendaCorpus.sha256(bytes))
        val scenario = BoundedJson(bytes.toString(Charsets.UTF_8)).parse().obj()
        assertEquals(scenarioId, scenario.s("id"))
        assertEquals("NOT_RUN", scenario.o("evaluation").s("providerEvaluation"))
        assertTrue(scenario.s("rationale").isNotBlank())
        assertEquals(6, scenario.a("forbiddenActions").size)
        val sources = scenario.a("sources").map { it.obj() }
        val docs = scenario.a("goldenDocuments").map { it.obj() }
        val allResolutions = mutableListOf<TemporalResolution>()
        docs.forEach { root ->
            val selected = root.a("sourceSnapshots").map { snap ->
                val node = snap.obj()
                val source = sources.single { it.s("sourceEntryId") == node.s("sourceEntryId") && it["revision"] == node["revision"] }
                AgendaCorpus.source(source)
            }
            val request = InformationExtractionRequest(root.s("requestId"), root.s("extractionId"), selected)
            val json = canonicalJson(root)
            val document = UnvalidatedProposalDocument(root.s("schemaVersion"), ExtractionOutcome.valueOf(root.s("outcome")), json)
            val result = StrictInformationProposalValidator().validate(request, document)
            assertTrue("$scenarioId: $result", result is ProposalValidationResult.Valid)
            result as ProposalValidationResult.Valid
            assertTrue(result.document.requiresHumanReview)
            val temporal = DeterministicTemporalExpressionResolver().resolve(request, document)
            assertTrue("$scenarioId temporal: $temporal", temporal is TemporalResolutionResult.Completed)
            temporal as TemporalResolutionResult.Completed
            allResolutions += temporal.resolutions
            root.a("proposals").forEach { value ->
                val p = value.obj()
                assertEquals("PROPOSED", p.s("proposalStatus"))
                val identity = ProposalIdentity(root.s("extractionId"), p.s("proposalId"))
                val revisions = selected.map { ProposalSourceRevision(it.snapshot.sourceEntryId, it.snapshot.revision) }
                val ready = ProposalReviewReadiness.afterValidationAndTemporalResolution(identity, revisions, result, temporal)
                val transition = ProposalLifecycle.candidate(identity, revisions).transition(
                    ProposalLifecycleCommand.SubmitForReview("review", 1792083600000L, ready))
                assertTrue(transition is ProposalTransitionResult.Applied)
                assertEquals(ProposalReviewState.PROPOSED, (transition as ProposalTransitionResult.Applied).lifecycle.state)
                assertEquals(json, temporal.validatedDocument.json)
            }
            // Independent mechanical evidence check including UTF-16 supplementary characters.
            AgendaCorpus.walk(root) { node ->
                if (node["quote"] != null && node["range"] != null) {
                    val source = sources.single { it.s("sourceEntryId") == node.s("sourceEntryId") && it["revision"] == node["revision"] }
                    val range = node.o("range")
                    assertEquals(node.s("quote"), source.s("text").substring(range["startUtf16"]!!.integer(), range["endUtf16"]!!.integer()))
                }
            }
        }
        scenario.a("expectedTemporal").forEach { value ->
            val expected = value.obj()
            val actual = allResolutions.filter { it.originalExpression == expected.s("expression") }
            assertTrue("Missing temporal ${expected.s("expression")}", actual.isNotEmpty())
            actual.forEach { r ->
                assertEquals(expected.s("status"), r.status.name)
                val fields = mapOf("localDate" to r.localDate, "reason" to r.reason.name,
                    "localTime" to r.localTime, "monthDayWithoutYear" to r.monthDayWithoutYear, "dayPart" to r.dayPart)
                fields.forEach { (key, output) ->
                    val limitation = expected["knownLimitation"] as? JsonObject
                    val answer = if (key == "reason" && limitation != null) limitation["observedReason"] else expected[key]
                    if (expected.fields.containsKey(key)) assertEquals("$scenarioId $key", (answer as? JsonString)?.value, output)
                }
                if (r.status != TemporalResolutionStatus.RESOLVED) assertNull(r.instantEpochMillis)
            }
        }
        scenario.a("comparisons").forEach { value ->
            val c = value.obj()
            val canonicalizer = ProposalCanonicalizer()
            val left = canonicalizer.canonicalize(canonicalJson(docs[c["leftDocument"]!!.integer()]), c.s("left"))
            val right = canonicalizer.canonicalize(canonicalJson(docs[c["rightDocument"]!!.integer()]), c.s("right"))
            // The desired answer stays in the golden. A known limitation is an explicit
            // regression observation, NOT a semantic pass or an altered gold answer.
            val observed = (c["knownLimitation"] as? JsonObject)?.s("observedKind") ?: c.s("expected")
            assertEquals(observed, ConservativeProposalComparator().compare(left, right,
                (c["temporalUncertainty"] as JsonBoolean).value).name)
        }
        val proposals = docs.flatMap { it.a("proposals") }.map { it.obj() }
        scenario.a("expectedRelations").forEach { value ->
            val relation = value.obj()
            val p = proposals.single { it.s("proposalId") == relation.s("source") }
            assertTrue(p.a("relations").any { it.obj().o("value").s("kind") == relation.s("kind") &&
                it.obj().o("value").s("targetProposalId") == relation.s("target") })
        }
        scenario.a("unknownDates").forEach { value ->
            val date = value.string()
            val resolutions = allResolutions.filter { it.localDate == date }
            assertTrue("Absence cannot generate an occurrence", resolutions.isEmpty())
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<String>> =
            AgendaCorpus.index.a("scenarios").map { it.obj().let { s -> arrayOf(s.s("id"), s.s("file"), s.s("sha256")) } }
    }
}

internal object AgendaCorpus {
    val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .map { File(it, "app/src/test/resources/agenda/v1") }.first { File(it, "index.json").isFile }
    val index = BoundedJson(File(root, "index.json").readText(Charsets.UTF_8)).parse().obj()
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun source(s: JsonObject): InformationExtractionSource {
        val captured = s.s("capturedAtEpochMillis").toLong()
        val id = s.s("sourceEntryId")
        val revision = s.o("revision")
        val correction = if (revision.s("kind") == "CORRECTION") revision.s("correctionId").toLong() else null
        val entry = MemoryEntry(id, if (correction == null) s.s("text") else s.s("originalText"),
            CaptureSourceType.valueOf(s.s("sourceType")), captured, s.s("timezoneId"), CaptureStatus.SAVED)
        return InformationExtractionSource.from(entry, correction?.let { MemoryTextVersion(id, s.s("text"), it, captured + 1000) })
    }
    fun walk(value: JsonValue, visit: (JsonObject) -> Unit) {
        when (value) {
            is JsonObject -> { visit(value); value.fields.values.forEach { walk(it, visit) } }
            is JsonArray -> value.values.forEach { walk(it, visit) }
            else -> Unit
        }
    }
}
