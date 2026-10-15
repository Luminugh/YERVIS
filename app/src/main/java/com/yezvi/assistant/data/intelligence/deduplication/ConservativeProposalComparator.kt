package com.yezvi.assistant.data.intelligence.deduplication

import com.yezvi.assistant.data.intelligence.validation.*
import com.yezvi.assistant.domain.intelligence.*

/** Deterministic candidate generation, not entity fusion or an inference/confirmation engine. */
internal class ConservativeProposalComparator {
    fun compare(
        previous: CanonicalProposal,
        incoming: CanonicalProposal,
        temporalUncertainty: Boolean,
    ): ProposalComparisonKind {
        if (previous.descriptor == incoming.descriptor) return ProposalComparisonKind.TECHNICAL_DUPLICATE
        val old = BoundedJson(previous.descriptor, 100000).parse().obj().o("proposal")
        val new = BoundedJson(incoming.descriptor, 100000).parse().obj().o("proposal")
        if (old.s("category") != new.s("category")) return ProposalComparisonKind.DISTINCT
        val relations = (new["relations"] as? JsonArray)?.values.orEmpty()
        val explicitLink = relations.map { it.obj().o("value") }.firstOrNull {
            canonicalJson(it["targetProposalId"] ?: JsonNull) == canonicalJson(old)
        }
        if (explicitLink != null && explicitLink.s("kind") in setOf("UPDATES", "CONTRADICTS")) {
            if (temporalUncertainty || explicitLink.s("kind") == "CONTRADICTS") {
                return ProposalComparisonKind.CONFLICT_REQUIRES_REVIEW
            }
            val before = old.o("data")
            val after = new.o("data")
            if (before["patternKind"] != after["patternKind"]) return ProposalComparisonKind.CONFLICT_REQUIRES_REVIEW
            return when {
                before["exceptions"] != after["exceptions"] -> ProposalComparisonKind.EXCEPTION_PROPOSED
                before["startsAt"] != after["startsAt"] || before["endsAt"] != after["endsAt"] ->
                    ProposalComparisonKind.RESCHEDULE_PROPOSED
                else -> ProposalComparisonKind.UPDATE_PROPOSED
            }
        }
        // An equal title alone is never a link. Compare every typed value, assessment and context.
        if (temporalUncertainty) return ProposalComparisonKind.DISTINCT
        val before = old.o("data")
        val after = new.o("data")
        val sharedContext = listOf("actor", "subject", "participants").any { field ->
            before[field] != null && canonicalJson(before[field]!!) == canonicalJson(after[field] ?: JsonNull)
        }
        val exactTitleEvidence = before["title"] == after["title"]
        return if ((sharedContext || exactTitleEvidence) && semanticValues(old) == semanticValues(new)) {
            ProposalComparisonKind.POSSIBLE_EQUIVALENT
        } else ProposalComparisonKind.DISTINCT
    }

    private fun semanticValues(value: JsonValue): String {
        fun values(node: JsonValue): JsonValue = when (node) {
            is JsonObject -> JsonObject(node.fields.filterKeys { it != "evidence" }.mapValues { values(it.value) })
            is JsonArray -> JsonArray(node.values.map(::values))
            else -> node
        }
        return canonicalJson(values(value))
    }

    companion object { const val VERSION = 1 }
}
