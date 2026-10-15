package com.yezvi.assistant.intelligence

import com.yezvi.assistant.data.intelligence.deduplication.canonicalJson
import com.yezvi.assistant.data.intelligence.validation.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Test-only metric primitives. Alignment IDs must be assigned/reviewed by the evaluator,
 * never guessed from equal titles. A provider must pass F4.04/F4.05 before this adapter.
 * These counts evaluate a supplied prediction; no fake/provider is invoked here. */
internal object AgendaEvaluation {
    data class Counts(val tp: Int, val fp: Int, val fn: Int) {
        val precision: Double? get() = if (tp + fp == 0) null else tp.toDouble() / (tp + fp)
        val recall: Double? get() = if (tp + fn == 0) null else tp.toDouble() / (tp + fn)
    }
    fun counts(gold: Set<String>, prediction: Set<String>) = Counts(
        gold.intersect(prediction).size, (prediction - gold).size, (gold - prediction).size)

    data class Labels(val categories: Map<String, String>, val states: Set<String>,
        val evidence: Set<String>, val sourceLinks: Set<String>, val relations: Set<String>)

    fun labels(document: JsonObject, alignment: Map<String, String>): Labels {
        val categories = linkedMapOf<String, String>()
        val states = linkedSetOf<String>()
        val evidence = linkedSetOf<String>()
        val links = linkedSetOf<String>()
        val relations = linkedSetOf<String>()
        document.a("proposals").forEach { value ->
            val p = value.obj()
            val key = alignment.getValue(p.s("proposalId"))
            require(key !in categories) { "Non-bijective alignment" }
            categories[key] = p.s("category")
            fun walk(node: JsonValue, path: String) {
                when (node) {
                    is JsonObject -> {
                        if (node["evidence"] is JsonArray) node.a("evidence").forEach { item ->
                            val e = item.obj()
                            evidence += "$key|$path|${canonicalJson(e)}"
                            links += "$key|$path|${e.s("sourceEntryId")}|${canonicalJson(e.o("revision"))}"
                        }
                        if (node["state"] is JsonObject) states += "$key|$path/state|${node.o("state").s("value")}"
                        node.fields.forEach { (name, child) -> walk(child, "$path/$name") }
                    }
                    is JsonArray -> node.values.forEachIndexed { i, child -> walk(child, "$path/$i") }
                    else -> Unit
                }
            }
            walk(p.o("data"), "/data")
            (p["relations"] as? JsonArray)?.values.orEmpty().forEach { relation ->
                val r = relation.obj().o("value")
                relations += "$key|${r.s("kind")}|${alignment.getValue(r.s("targetProposalId"))}"
                walk(relation, "/relations/${r.s("kind")}/${alignment.getValue(r.s("targetProposalId"))}")
            }
        }
        return Labels(categories, states, evidence, links, relations)
    }

    fun concept(gold: Labels, prediction: Labels, concept: String) = counts(
        gold.categories.filterValues { it == concept }.keys, prediction.categories.filterValues { it == concept }.keys)

    data class Report(val categoryCorrect: Int, val categoryDenominator: Int,
        val concepts: Map<String, Counts>, val states: Counts, val evidence: Counts,
        val sourceLinks: Counts, val contradictions: Counts, val unjustifiedUpdates: Int,
        val falseRoutines: Int, val falseReminders: Int)

    fun evaluate(gold: Labels, prediction: Labels): Report {
        val concepts = (gold.categories.values + prediction.categories.values).toSortedSet()
            .associateWith { concept(gold, prediction, it) }
        return Report(gold.categories.count { (id, category) -> prediction.categories[id] == category },
            gold.categories.size, concepts, counts(gold.states, prediction.states),
            counts(gold.evidence, prediction.evidence), counts(gold.sourceLinks, prediction.sourceLinks),
            counts(gold.relations.filter { "|CONTRADICTS|" in it }.toSet(), prediction.relations.filter { "|CONTRADICTS|" in it }.toSet()),
            (prediction.relations - gold.relations).count { "|UPDATES|" in it },
            concepts["ROUTINE"]?.fp ?: 0, concepts["REMINDER"]?.fp ?: 0)
    }

    /** Repeated descriptors emitted as separate entities, beyond the first owner. Pure audit:
     * it does not test durable UNIQUE arbitration; that remains F4.09's isolated DB coverage. */
    fun unavoidedTechnicalDuplicates(canonicalDescriptors: List<String>): Int =
        canonicalDescriptors.size - canonicalDescriptors.toSet().size
}

class AgendaEvaluationTest {
    @Test fun categoryPrecisionRecallExposeFalseRoutinesAndMissingFacts() {
        val gold = AgendaEvaluation.Labels(mapOf("a" to "FACT", "b" to "TASK"), emptySet(), emptySet(), emptySet(), emptySet())
        val predicted = gold.copy(categories = mapOf("a" to "ROUTINE", "b" to "TASK", "alarm" to "REMINDER"))
        assertEquals(AgendaEvaluation.Counts(0, 1, 0), AgendaEvaluation.concept(gold, predicted, "ROUTINE"))
        assertEquals(AgendaEvaluation.Counts(0, 0, 1), AgendaEvaluation.concept(gold, predicted, "FACT"))
        assertEquals(AgendaEvaluation.Counts(1, 0, 0), AgendaEvaluation.concept(gold, predicted, "TASK"))
        assertEquals(1, AgendaEvaluation.concept(gold, predicted, "REMINDER").fp)
    }

    @Test fun missingDenominatorsAreUnavailableNotPerfectScores() {
        val empty = AgendaEvaluation.counts(emptySet(), emptySet())
        assertNull(empty.precision)
        assertNull(empty.recall)
        assertEquals(0.5, AgendaEvaluation.counts(setOf("a", "b"), setOf("a", "c")).precision!!, 0.0)
        assertEquals(0.5, AgendaEvaluation.counts(setOf("a", "b"), setOf("a", "c")).recall!!, 0.0)
    }

    @Test fun wrongStatesEvidenceAndLinksCannotCountAsCorrect() {
        for (name in listOf("state", "quote", "revision", "source", "contradiction", "update")) {
            assertEquals(AgendaEvaluation.Counts(0, 1, 1), AgendaEvaluation.counts(setOf("$name:gold"), setOf("$name:wrong")))
        }
    }

    @Test fun exactEvidenceAdapterIncludesRevisionAndUtf16Range() {
        val scenario = BoundedJson(File(AgendaCorpus.root, "agenda-30.json").readText()).parse().obj()
        val document = scenario.a("goldenDocuments").single().obj()
        val labels = AgendaEvaluation.labels(document, mapOf("p" to "occurrence"))
        assertEquals("FACT", labels.categories.getValue("occurrence"))
        assertTrue(labels.evidence.any { it.contains("CORRECTION") && it.contains("startUtf16") })
        assertTrue(labels.sourceLinks.all { it.contains("17") })
    }

    @Test fun reportExposesContradictionsUnjustifiedUpdatesAndDuplicateEntities() {
        val gold = AgendaEvaluation.Labels(mapOf("old" to "FACT", "new" to "FACT"), setOf("state:gold"),
            setOf("quote:gold"), setOf("link:gold"), setOf("new|CONTRADICTS|old"))
        val prediction = gold.copy(categories = mapOf("old" to "FACT", "new" to "ROUTINE", "r" to "REMINDER"),
            states = emptySet(), evidence = emptySet(), sourceLinks = setOf("wrong"), relations = setOf("new|UPDATES|old"))
        val r = AgendaEvaluation.evaluate(gold, prediction)
        assertEquals(1, r.categoryCorrect)
        assertEquals(2, r.categoryDenominator)
        assertEquals(1, r.falseRoutines)
        assertEquals(1, r.falseReminders)
        assertEquals(1, r.contradictions.fn)
        assertEquals(1, r.unjustifiedUpdates)
        assertEquals(1, AgendaEvaluation.unavoidedTechnicalDuplicates(listOf("descriptor-a", "descriptor-a", "descriptor-b")))
    }

    @Test fun corpusInventoryAndReproducibleComponentReportHaveNoProviderAccuracy() {
        val scenarios = AgendaCorpus.index.a("scenarios").map { item ->
            BoundedJson(File(AgendaCorpus.root, item.obj().s("file")).readText(Charsets.UTF_8)).parse().obj()
        }
        assertEquals(48, scenarios.size)
        assertEquals(setOf("A", "B", "C"), scenarios.map { it.s("lane") }.toSet())
        val docs = scenarios.flatMap { it.a("goldenDocuments") }.map { it.obj() }
        val concepts = docs.flatMap { it.a("proposals") }.map { it.obj().s("category") }.toSet()
        assertEquals(setOf("TASK", "EVENT", "ROUTINE", "HABIT", "REMINDER", "IDEA", "FACT", "DECISION", "GOAL", "PERSON", "PROJECT"), concepts)
        assertEquals(setOf("America/Lima", "America/New_York", "Europe/Madrid"),
            scenarios.flatMap { it.a("sources") }.map { it.obj().s("timezoneId") }.toSet())
        var evidence = 0
        docs.forEach { doc -> AgendaCorpus.walk(doc) { if (it["quote"] != null && it["range"] != null) evidence++ } }
        val counts = listOf(
            "corpusVersion=1.0.0", "scenarios=${scenarios.size}",
            *listOf("A", "B", "C").map { lane -> "lane$lane=${scenarios.count { it.s("lane") == lane }}" }.toTypedArray(),
            "documents=${docs.size}", "proposals=${docs.sumOf { it.a("proposals").size }}",
            "evidenceReferences=$evidence", "temporalGoldChecks=${scenarios.sumOf { it.a("expectedTemporal").size }}",
            "comparisons=${scenarios.sumOf { it.a("comparisons").size }}",
            "relations=${scenarios.sumOf { it.a("expectedRelations").size }}",
            "knownTemporalLimitations=${scenarios.sumOf { s -> s.a("expectedTemporal").count { it.obj()["knownLimitation"] != null } }}",
            "knownComparatorLimitations=${scenarios.sumOf { s -> s.a("comparisons").count { it.obj()["knownLimitation"] != null } }}",
            "providerEvaluations=0", "providerPrecision=NOT_MEASURED", "providerRecall=NOT_MEASURED",
            "dbWrites=NOT_APPLICABLE_NO_ROOM_IN_HARNESS", "externalActions=NOT_APPLICABLE_NO_EXECUTOR"
        )
        val report = File("build/reports/agenda-corpus/component-inventory.txt")
        requireNotNull(report.parentFile).mkdirs()
        report.writeText(counts.joinToString("\n", postfix = "\n"))
    }
}
