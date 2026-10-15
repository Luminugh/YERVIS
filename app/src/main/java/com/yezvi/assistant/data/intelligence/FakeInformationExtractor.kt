package com.yezvi.assistant.data.intelligence

import com.yezvi.assistant.domain.intelligence.ExtractionError
import com.yezvi.assistant.domain.intelligence.ExtractionMode
import com.yezvi.assistant.domain.intelligence.ExtractionOutcome
import com.yezvi.assistant.domain.intelligence.InformationExtractionRequest
import com.yezvi.assistant.domain.intelligence.InformationExtractionResult
import com.yezvi.assistant.domain.intelligence.InformationExtractor
import com.yezvi.assistant.domain.intelligence.InformationProposalSchema
import com.yezvi.assistant.domain.intelligence.UnvalidatedProposalDocument
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Trusted, synthetic test configuration; never configure it from user input or provider output. */
class RecordedExtractionScenario(
    val request: InformationExtractionRequest,
    val response: RecordedExtractionResponse,
)

sealed interface RecordedExtractionResponse {
    /** JSON must be a previously schema-checked positive fixture with the exact request context. */
    class Document(val json: String, val outcome: ExtractionOutcome) : RecordedExtractionResponse {
        init { require(json.toByteArray(Charsets.UTF_8).size in 1..262144) }
        override fun toString(): String = "RecordedExtractionResponse.Document(outcome=$outcome)"
    }

    data object SimulatedFailure : RecordedExtractionResponse
}

/**
 * In-memory, exact-match replay. No parser, classifier, clock, random generator, IO or retry state.
 * Positive fixtures are validated by desktop schema checks and tested byte-for-byte, not inferred.
 * Unmatched text, metadata, selection, revision or correlation is a controlled failure.
 */
class FakeInformationExtractor(scenarios: List<RecordedExtractionScenario> = emptyList()) : InformationExtractor {
    private val responses: Map<InformationExtractionRequest, RecordedExtractionResponse> = scenarios.associate { scenario ->
        require(scenario.request.schemaVersion == InformationProposalSchema.VERSION) { "Unsupported recorded schema version" }
        scenario.request to scenario.response
    }.also { require(it.size == scenarios.size) { "Duplicate fixture requests are ambiguous" } }

    override suspend fun extract(request: InformationExtractionRequest): InformationExtractionResult {
        coroutineContext.ensureActive()
        if (request.schemaVersion != InformationProposalSchema.VERSION) return failure(ExtractionError.UNSUPPORTED_SCHEMA_VERSION)
        val response = responses[request] ?: return failure(ExtractionError.NO_MATCHING_FIXTURE)
        return when (response) {
            RecordedExtractionResponse.SimulatedFailure -> failure(ExtractionError.SIMULATED_FAILURE)
            is RecordedExtractionResponse.Document -> {
                val document = UnvalidatedProposalDocument(InformationProposalSchema.VERSION, response.outcome, response.json)
                if (response.outcome == ExtractionOutcome.NO_PROPOSALS) {
                    InformationExtractionResult.NoProposals(document, ExtractionMode.SYNTHETIC_FIXTURE)
                } else {
                    InformationExtractionResult.Proposals(document, ExtractionMode.SYNTHETIC_FIXTURE)
                }
            }
        }
    }

    private fun failure(error: ExtractionError) = InformationExtractionResult.Failure(error, ExtractionMode.SYNTHETIC_FIXTURE)
}
