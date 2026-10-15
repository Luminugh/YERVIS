package com.yezvi.assistant.presentation.intelligence

import com.yezvi.assistant.data.intelligence.FakeInformationExtractor
import com.yezvi.assistant.data.intelligence.RecordedExtractionResponse
import com.yezvi.assistant.data.intelligence.RecordedExtractionScenario
import com.yezvi.assistant.data.intelligence.temporal.DeterministicTemporalExpressionResolver
import com.yezvi.assistant.data.intelligence.validation.BoundedJson
import com.yezvi.assistant.data.intelligence.validation.JsonArray
import com.yezvi.assistant.data.intelligence.validation.JsonBoolean
import com.yezvi.assistant.data.intelligence.validation.JsonNull
import com.yezvi.assistant.data.intelligence.validation.JsonNumber
import com.yezvi.assistant.data.intelligence.validation.JsonObject
import com.yezvi.assistant.data.intelligence.validation.JsonString
import com.yezvi.assistant.data.intelligence.validation.JsonValue
import com.yezvi.assistant.data.intelligence.validation.StrictInformationProposalValidator
import com.yezvi.assistant.domain.intelligence.*
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryTextVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads only the pinned, synthetic F4.02 fixtures copied into the debug APK. */
internal class ProposalReviewDemoLoader(private val readAsset: (String) -> String) {
    private val fixtureNames = listOf(
        "01-multiple", "02-rutina-semanal", "03-patron-observado", "04-evento-ambiguo",
        "05-negacion", "06-tarea-completada", "07-recordatorio-explicito",
        "08-actualizacion-contradictoria", "09-evidencia-insuficiente",
        "10-idea-decision-objetivo-habito", "11-conclusion-personal-acotada",
        "12-revision-utf16", "13-descripcion-atribuida", "14-tiempo-resuelto",
    )

    suspend fun load(): ProposalReviewDemoSession {
        val inputs = withContext(Dispatchers.IO) {
            val sourcesJson = readAsset("proposal-review/sources.json")
            fixtureNames.map { name -> name to readAsset("proposal-review/fixtures/$name.json") }
                .let { FixtureInputs(sourcesJson, it) }
        }
        return withContext(Dispatchers.Default) { buildSession(inputs) }
    }

    private suspend fun buildSession(inputs: FixtureInputs): ProposalReviewDemoSession {
        val sources = parseSources(inputs.sourcesJson)
        val seeds = inputs.fixtures.map { (name, json) -> parseScenario(name, json, sources) }
        val extractor = FakeInformationExtractor(seeds.map { it.scenario })
        val validator = StrictInformationProposalValidator()
        val resolver = DeterministicTemporalExpressionResolver()
        val items = mutableListOf<ActiveProposalReview>()
        var noProposals = 0

        seeds.forEachIndexed { fixtureIndex, seed ->
            val extraction = extractor.extract(seed.request)
            val unvalidated = when (extraction) {
                is InformationExtractionResult.Proposals -> extraction.document
                is InformationExtractionResult.NoProposals -> {
                    noProposals++
                    extraction.document
                }
                is InformationExtractionResult.Failure -> error("fixture-extractor-${extraction.error.name.lowercase()}")
            }
            val validation = validator.validate(seed.request, unvalidated)
            if (validation !is ProposalValidationResult.Valid) {
                val code = (validation as ProposalValidationResult.Rejected).error.code.name
                error("fixture-validation-$code")
            }
            val temporal = resolver.resolve(seed.request, unvalidated)
            if (temporal !is TemporalResolutionResult.Completed) {
                val code = (temporal as TemporalResolutionResult.Rejected).error.code.name
                error("fixture-temporal-$code")
            }
            if (extraction is InformationExtractionResult.NoProposals) return@forEachIndexed

            val documentRoot = parseJson(validation.document.json).objectValue("validated-root")
            val proposalNodes = documentRoot.array("proposals")
            val proposalSources = seed.request.sources.map { source ->
                val prepared = seed.sourcesById.getValue(source.snapshot.sourceEntryId)
                ProposalSourceRevision(source.snapshot.sourceEntryId, source.snapshot.revision).also {
                    check(prepared.extractionSource.snapshot == source.snapshot)
                }
            }
            val displaySources = seed.request.sources.map { source ->
                seed.sourcesById.getValue(source.snapshot.sourceEntryId).display
            }
            proposalNodes.values.forEachIndexed { proposalIndex, rawProposal ->
                val proposal = rawProposal.objectValue("proposal")
                val proposalId = proposal.string("proposalId")
                val category = proposal.string("category")
                val identity = ProposalIdentity(seed.request.extractionId, proposalId)
                val readiness = ProposalReviewReadiness.afterValidationAndTemporalResolution(
                    identity = identity,
                    sourceRevisions = proposalSources,
                    validation = validation,
                    temporal = temporal,
                ) ?: error("fixture-review-readiness")
                val candidate = ProposalLifecycle.candidate(identity, proposalSources)
                val submitted = candidate.transition(
                    ProposalLifecycleCommand.SubmitForReview(
                        actionId = "demo-submit-${fixtureIndex}-${proposalIndex}",
                        occurredAtEpochMillis = 0L,
                        readiness = readiness,
                    ),
                )
                val lifecycle = (submitted as? ProposalTransitionResult.Applied)?.lifecycle
                    ?: error("fixture-review-transition")
                val fields = collectFields(
                    data = proposal.jsonValue("data"),
                    basePointer = "/proposals/$proposalIndex/data",
                    proposalId = proposalId,
                    resolutions = temporal.resolutions,
                )
                val title = fields.firstOrNull { it.key == "title" }?.value
                    ?.takeIf(String::isNotBlank) ?: category
                val card = ProposalReviewCardUi(
                    extractionId = seed.request.extractionId,
                    proposalId = proposalId,
                    category = category,
                    title = title,
                    fields = fields,
                    sources = displaySources,
                    reviewState = lifecycle.state,
                )
                items += ActiveProposalReview(card, lifecycle, readiness)
            }
        }
        return ProposalReviewDemoSession(
            proposals = items.sortedBy { CATEGORY_REVIEW_ORDER[it.card.category] ?: Int.MAX_VALUE },
            noProposalScenarios = noProposals,
        )
    }

    private fun parseSources(json: String): Map<String, PreparedDemoSource> {
        val sourceArray = parseJson(json).objectValue("sources-root").array("sources")
        return sourceArray.values.associate { raw ->
            val source = raw.objectValue("source")
            val snapshot = source.objectValue("snapshot")
            val id = snapshot.string("sourceEntryId")
            val text = source.string("text")
            val revisionNode = snapshot.objectValue("revision")
            val correctionId = if (revisionNode.string("kind") == "CORRECTION") {
                revisionNode.string("correctionId").toLongOrNull() ?: error("fixture-revision")
            } else null
            val sourceType = CaptureSourceType.valueOf(snapshot.string("sourceType"))
            val capturedAt = snapshot.string("capturedAtEpochMillis").toLongOrNull() ?: error("fixture-capture-time")
            val timezoneId = snapshot.string("timezoneId")
            val originalText = if (correctionId == null) text else "Original sintético anterior."
            val entry = MemoryEntry(
                id = id,
                rawText = originalText,
                sourceType = sourceType,
                capturedAtEpochMillis = capturedAt,
                timezoneId = timezoneId,
                status = CaptureStatus.SAVED,
            )
            val version = correctionId?.let { MemoryTextVersion(id, text, it, capturedAt + 1_000L) }
            val extractionSource = InformationExtractionSource.from(entry, version)
            val revisionLabel = when (val revision = extractionSource.snapshot.revision) {
                SourceTextRevision.Original -> "Original"
                is SourceTextRevision.Correction -> "Corrección ${revision.correctionId}"
            }
            id to PreparedDemoSource(
                extractionSource = extractionSource,
                display = ProposalReviewSourceUi(
                    sourceEntryId = id,
                    revisionLabel = revisionLabel,
                    originalText = originalText,
                    reviewedText = text,
                    capturedAtEpochMillis = capturedAt,
                    timezoneId = timezoneId,
                ),
            )
        }
    }

    private fun parseScenario(
        name: String,
        json: String,
        sources: Map<String, PreparedDemoSource>,
    ): ScenarioSeed {
        val root = parseJson(json).objectValue("fixture-root")
        val sourceIds = root.array("sourceSnapshots").values.map { it.objectValue("snapshot").string("sourceEntryId") }
        val fixtureSources = sourceIds.map { sources[it] ?: error("fixture-source-missing") }
        val request = InformationExtractionRequest(
            requestId = root.string("requestId"),
            extractionId = root.string("extractionId"),
            sources = fixtureSources.map { it.extractionSource },
            schemaVersion = root.string("schemaVersion"),
        )
        val outcome = ExtractionOutcome.valueOf(root.string("outcome"))
        val scenario = RecordedExtractionScenario(
            request = request,
            response = RecordedExtractionResponse.Document(json, outcome),
        )
        return ScenarioSeed(name, request, scenario, fixtureSources.associateBy { it.display.sourceEntryId })
    }

    private fun collectFields(
        data: JsonValue,
        basePointer: String,
        proposalId: String,
        resolutions: List<TemporalResolution>,
    ): List<ProposalReviewFieldUi> {
        val output = mutableListOf<ProposalReviewFieldUi>()
        fun walk(value: JsonValue, pointer: String) {
            when (value) {
                is JsonArray -> value.values.forEachIndexed { index, child -> walk(child, "$pointer/$index") }
                is JsonObject -> {
                    val expression = value["expression"] as? JsonObject
                    if (expression != null && value["anchor"] is JsonObject) {
                        val expressionValue = expression["value"] as? JsonString
                        if (expressionValue != null) {
                            output += ProposalReviewFieldUi(
                                pointer = pointer,
                                key = pointer.substringAfterLast('/'),
                                value = expressionValue.value,
                                assessment = parseAssessment(expression["assessment"]),
                                evidence = parseEvidence(expression["evidence"]),
                                temporal = resolutions.firstOrNull {
                                    it.proposalId == proposalId && it.jsonPointer == pointer
                                }?.let(::toTemporalUi),
                            )
                        }
                        return
                    }
                    if (value["value"] != null && value["evidence"] is JsonArray) {
                        val rawValue = value["value"]!!
                        val relatedId = (rawValue as? JsonObject)?.stringOrNull("proposalId")
                        output += ProposalReviewFieldUi(
                            pointer = pointer,
                            key = pointer.substringAfterLast('/'),
                            value = if (relatedId == null) rawValue.displayValue() else "",
                            relatedProposalId = relatedId,
                            assessment = parseAssessment(value["assessment"]),
                            evidence = parseEvidence(value["evidence"]),
                        )
                        return
                    }
                    value.fields.forEach { (key, child) -> walk(child, "$pointer/${escapePointer(key)}") }
                }
                else -> Unit
            }
        }
        walk(data, basePointer)
        return output
    }

    private fun toTemporalUi(value: TemporalResolution) = ProposalReviewTemporalUi(
        expression = value.originalExpression,
        status = value.status,
        reason = value.reason,
        localDate = value.localDate,
        endLocalDate = value.endLocalDate,
        localTime = value.localTime,
        endLocalTime = value.endLocalTime,
        dayPart = value.dayPart,
        timezoneId = value.resolvedTimezoneId ?: value.captureTimezoneId,
    )

    private fun parseEvidence(value: JsonValue?): List<ProposalReviewEvidenceUi> =
        (value as? JsonArray)?.values.orEmpty().map { raw ->
            val item = raw.objectValue("evidence")
            val revision = item.objectValue("revision")
            val range = item.objectValue("range")
            ProposalReviewEvidenceUi(
                sourceEntryId = item.string("sourceEntryId"),
                revisionLabel = revisionLabel(revision),
                quote = item.string("quote"),
                startUtf16 = range.int("startUtf16"),
                endUtf16 = range.int("endUtf16"),
            )
        }

    private fun parseAssessment(value: JsonValue?): ProposalReviewAssessmentUi? {
        val assessment = value as? JsonObject ?: return null
        return ProposalReviewAssessmentUi(
            basis = assessment.stringOrNull("basis"),
            modality = assessment.stringOrNull("modality"),
            polarity = assessment.stringOrNull("polarity"),
        )
    }

    private fun revisionLabel(value: JsonObject): String = when (value.string("kind")) {
        "CORRECTION" -> "Corrección ${value.string("correctionId")}"
        else -> "Original"
    }

    private fun parseJson(text: String): JsonValue = BoundedJson(text).parse()

    private data class FixtureInputs(val sourcesJson: String, val fixtures: List<Pair<String, String>>)
    private data class PreparedDemoSource(
        val extractionSource: InformationExtractionSource,
        val display: ProposalReviewSourceUi,
    )
    private data class ScenarioSeed(
        val name: String,
        val request: InformationExtractionRequest,
        val scenario: RecordedExtractionScenario,
        val sourcesById: Map<String, PreparedDemoSource>,
    )

    private companion object {
        fun escapePointer(value: String) = value.replace("~", "~0").replace("/", "~1")
    }
}

private val CATEGORY_REVIEW_ORDER = mapOf(
    "EVENT" to 0,
    "TASK" to 1,
    "FACT" to 2,
    "ROUTINE" to 3,
    "HABIT" to 4,
    "REMINDER" to 5,
    "PERSON" to 6,
    "PROJECT" to 7,
    "DECISION" to 8,
    "GOAL" to 9,
    "IDEA" to 10,
)

private fun JsonValue.objectValue(label: String): JsonObject = this as? JsonObject ?: error("fixture-$label")
private fun JsonValue.arrayValue(label: String): JsonArray = this as? JsonArray ?: error("fixture-$label")
private fun JsonObject.jsonValue(key: String): JsonValue = this[key] ?: error("fixture-$key")
private fun JsonObject.objectValue(key: String): JsonObject = jsonValue(key).objectValue(key)
private fun JsonObject.string(key: String): String = (this[key] as? JsonString)?.value ?: error("fixture-$key")
private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonString)?.value
private fun JsonObject.int(key: String): Int = ((this[key] as? JsonNumber)?.value?.toInt()) ?: error("fixture-$key")
private fun JsonObject.array(key: String): JsonArray = jsonValue(key).arrayValue(key)
private fun JsonValue.displayValue(): String = when (this) {
    is JsonString -> value
    is JsonNumber -> value.toPlainString()
    is JsonBoolean -> value.toString()
    JsonNull -> "Sin dato" 
    is JsonArray -> values.joinToString(", ") { it.displayValue() }
    is JsonObject -> fields.entries.joinToString(" · ") { (key, value) -> "$key: ${value.displayValue()}" }
}
