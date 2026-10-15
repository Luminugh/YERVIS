package com.yezvi.assistant.data.intelligence.validation

import com.yezvi.assistant.domain.intelligence.*
import com.yezvi.assistant.domain.intelligence.ProposalRejectionCode.*
import com.yezvi.assistant.domain.intelligence.ProposalReviewRequirement.*
import java.util.Locale

/** Structural validation must finish first. Each instance is confined to one validation call. */
internal class ProposalSemanticChecks(private val request: InformationExtractionRequest) {
    private val review = linkedSetOf(HUMAN_MEANING_REVIEW, LINGUISTIC_SUPPORT_UNDECIDED)
    private var evidenceCount = 0
    private lateinit var proposals: Map<String, JsonObject>

    fun check(root: JsonObject): Set<ProposalReviewRequirement> {
        if (root.s("requestId") != request.requestId || root.s("extractionId") != request.extractionId) abort(CORRELATION, "")
        val snapshots = root.a("sourceSnapshots")
        if (snapshots.size != request.sources.size) abort(SNAPSHOT_MISMATCH, "/sourceSnapshots")
        val seen = mutableSetOf<Pair<String, SourceTextRevision>>()
        snapshots.forEachIndexed { i, node ->
            val snapshot = node.obj()
            val path = "/sourceSnapshots/$i"
            val source = source(snapshot, path)
            if (!seen.add(source.snapshot.sourceEntryId to source.snapshot.revision)) abort(SNAPSHOT_MISMATCH, path)
            val expected = source.snapshot
            val ranges = snapshot.a("authorizedRanges").map { it.obj().get("startUtf16")!!.integer() to it.obj().get("endUtf16")!!.integer() }
            if (snapshot.s("sourceType") != expected.sourceType.name ||
                long(snapshot.s("capturedAtEpochMillis"), "$path/capturedAtEpochMillis") != expected.capturedAtEpochMillis ||
                snapshot.s("timezoneId") != expected.timezoneId || snapshot.s("textSha256") != expected.textSha256 ||
                snapshot["textLengthUtf16"]!!.integer() != expected.textLengthUtf16 ||
                ranges != source.fragments.map { it.range.startUtf16 to it.range.endUtf16 }) abort(SNAPSHOT_MISMATCH, path)
        }
        val list = root.a("proposals").map { it.obj() }
        proposals = list.associateBy { it.s("proposalId") }
        if (list.size != proposals.size) abort(DUPLICATE_ID, "/proposals")
        walk(root, "")
        list.forEachIndexed { index, proposal ->
            val path = "/proposals/$index"
            references(proposal, proposal.s("proposalId"), path)
            semantics(proposal, path)
        }
        root.a("issues").forEachIndexed { index, issue ->
            issue.obj().a("affectedProposalIds").forEachIndexed { refIndex, id ->
                if (id.string() !in proposals) abort(INVALID_REFERENCE, "/issues/$index/affectedProposalIds/$refIndex")
            }
            if (issue.obj().s("code") == "CONTRADICTION") review += CONTRADICTION
        }
        cycles("dependsOn")
        cycles("UPDATES")
        return review
    }

    private fun source(ref: JsonObject, path: String): InformationExtractionSource {
        val id = ref.s("sourceEntryId")
        val revisionObject = ref.o("revision")
        val revision = if (revisionObject.s("kind") == "ORIGINAL") SourceTextRevision.Original else {
            val n = long(revisionObject.s("correctionId"), "$path/revision/correctionId")
            if (n <= 0) abort(INT64_RANGE, "$path/revision/correctionId")
            SourceTextRevision.Correction(n)
        }
        val sameEntry = request.sources.filter { it.snapshot.sourceEntryId == id }
        if (sameEntry.isEmpty()) abort(SOURCE_NOT_AUTHORIZED, path)
        return sameEntry.find { it.snapshot.revision == revision } ?: abort(REVISION_MISMATCH, "$path/revision")
    }

    private fun evidence(value: JsonObject, path: String) {
        if (++evidenceCount > 1024) abort(EVIDENCE_LIMIT, path)
        val host = source(value, path)
        val range = value.o("range")
        val start = range["startUtf16"]!!.integer()
        val end = range["endUtf16"]!!.integer()
        if (start >= end || end > host.snapshot.textLengthUtf16) abort(EVIDENCE_RANGE, "$path/range")
        val fragment = host.fragments.find { start >= it.range.startUtf16 && end <= it.range.endUtf16 }
            ?: abort(EVIDENCE_RANGE, "$path/range")
        val localStart = start - fragment.range.startUtf16
        val localEnd = end - fragment.range.startUtf16
        fun split(index: Int) = index > 0 && index < fragment.text.length && fragment.text[index - 1].isHighSurrogate() && fragment.text[index].isLowSurrogate()
        if (split(localStart) || split(localEnd)) abort(EVIDENCE_RANGE, "$path/range")
        if (fragment.text.substring(localStart, localEnd) != value.s("quote")) abort(EVIDENCE_QUOTE, path)
    }

    private fun walk(value: JsonValue, path: String, key: String = "") {
        when (value) {
            is JsonArray -> value.values.forEachIndexed { i, item -> walk(item, "$path/$i", key) }
            is JsonObject -> {
                if (value["sourceEntryId"] != null && value["revision"] != null) {
                    if (value["quote"] != null) evidence(value, path) else source(value, path)
                }
                if (key == "timezoneId") zone(value.s("value"), path)
                if (key == "instantEpochMillis") long(value.s("value"), path)
                if (key == "localDate" && !CalendarChecks.validDate(value.s("value"))) abort(INVALID_DATE, path)
                if (value["assessment"] != null) {
                    val assessment = value.o("assessment")
                    if (assessment.s("basis") == "INFERRED") review += INFERRED_CLAIM
                    if (assessment.s("modality") != "ASSERTED" || assessment.s("polarity") == "NEGATED") review += UNCERTAIN_CLAIM
                    if (assessment["technicalScore"] != null) review += TECHNICAL_SCORE_NOT_TRUSTED
                }
                if (value["expression"] != null && value["resolved"] != null) temporal(value, path)
                if (value["frequency"] != null) recurrence(value, path)
                if ((key in setOf("observedPeriod", "validity", "period")) && (value["from"] != null || value["to"] != null)) period(value, path)
                value.fields.forEach { (name, child) ->
                    if (name == "timezoneId" && child is JsonString) zone(child.value, "$path/$name")
                    walk(child, "$path/$name", name)
                }
            }
            is JsonString -> if (key in setOf("requestId", "extractionId", "proposalId", "targetProposalId", "occurrenceId", "exceptionId", "affectedProposalIds") &&
                !value.value.matches(Regex("[a-z][a-z0-9_-]{0,63}"))) abort(STRUCTURE, path)
            else -> Unit
        }
    }

    private fun references(value: JsonValue, owner: String, path: String, key: String = "") {
        when (value) {
            is JsonArray -> value.values.forEachIndexed { i, child -> references(child, owner, "$path/$i", key) }
            is JsonObject -> {
                val ref = (value["value"] as? JsonObject)?.get("proposalId") as? JsonString
                if (ref != null) {
                    val destination = target(ref.value, owner, path)
                    val allowed = when (key) {
                        "actor", "person", "participants", "speaker" -> setOf("PERSON")
                        "project" -> setOf("PROJECT")
                        "goal" -> setOf("GOAL")
                        "dependsOn" -> setOf("TASK")
                        "progress" -> setOf("TASK", "FACT")
                        "subject" -> setOf("PERSON", "PROJECT")
                        "linkedProposal" -> setOf("TASK", "EVENT", "FACT")
                        "target" -> setOf("TASK", "EVENT", "ROUTINE", "HABIT", "FACT", "GOAL")
                        else -> emptySet()
                    }
                    if (destination.s("category") !in allowed) abort(REFERENCE_TYPE, path)
                }
                if (key == "relations") {
                    val relation = value.o("value")
                    val destination = target(relation.s("targetProposalId"), owner, path)
                    if (relation.s("kind") in setOf("UPDATES", "CONTRADICTS") && destination.s("category") != proposals.getValue(owner).s("category")) abort(REFERENCE_TYPE, path)
                    if (relation.s("kind") == "CONTRADICTS") review += CONTRADICTION
                }
                value.fields.forEach { (name, child) -> references(child, owner, "$path/$name", name) }
            }
            else -> Unit
        }
    }

    private fun target(id: String, owner: String, path: String): JsonObject {
        if (id == owner) abort(INVALID_REFERENCE, path)
        return proposals[id] ?: abort(INVALID_REFERENCE, path)
    }

    private fun cycles(kind: String) {
        val active = mutableSetOf<String>()
        val complete = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in complete) return
            if (!active.add(id)) abort(REFERENCE_CYCLE, "/proposals")
            val p = proposals.getValue(id)
            val refs = if (kind == "dependsOn") (p.o("data")["dependsOn"] as? JsonArray)?.values.orEmpty().map { it.obj().o("value").s("proposalId") }
            else (p["relations"] as? JsonArray)?.values.orEmpty().map { it.obj().o("value") }.filter { it.s("kind") == kind }.map { it.s("targetProposalId") }
            refs.forEach { visit(it) }
            active.remove(id); complete += id
        }
        proposals.keys.forEach { visit(it) }
    }

    private fun temporal(time: JsonObject, path: String) {
        val anchor = time.o("anchor")
        source(anchor, "$path/anchor")
        if (time.o("expression").a("evidence").none {
                it.obj().s("sourceEntryId") == anchor.s("sourceEntryId") && it.obj()["revision"] == anchor["revision"]
            }) abort(TEMPORAL_INCONSISTENCY, "$path/anchor")
        val resolved = time["resolved"] as? JsonObject
        if (resolved == null) { review += TEMPORAL_RESOLUTION; return }
        if (time.s("uncertainty") == "UNRESOLVED") abort(TEMPORAL_INCONSISTENCY, "$path/resolved")
        val date = resolved.text("localDate")
        val clock = resolved.text("localTime")
        val timezone = resolved.text("timezoneId")
        date?.let { if (!CalendarChecks.validDate(it)) abort(INVALID_DATE, "$path/resolved/localDate") }
        clock?.let { if (!CalendarChecks.validTime(it)) abort(TEMPORAL_INCONSISTENCY, "$path/resolved/localTime") }
        timezone?.let { zone(it, "$path/resolved/timezoneId") }
        listOf("localDate", "localTime", "timezoneId").forEach { field ->
            val wrapper = resolved[field] as? JsonObject ?: return@forEach
            val value = wrapper.s("value")
            val quotes = wrapper.a("evidence").map { it.obj().s("quote") }
            // Literal typed values are objectively comparable; language-based resolutions stay deferred.
            val literals = when (field) {
                "localDate" -> quotes.flatMap { Regex("(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}(?![0-9])").findAll(it).map { m -> m.value }.toList() }
                "localTime" -> quotes.flatMap { Regex("(?<![0-9])(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?(?![0-9])").findAll(it).map { m -> m.value }.toList() }
                else -> quotes.filter { CalendarChecks.validZone(it) }
            }.distinct()
            if (literals.size == 1 && literals.single() != value &&
                !(field == "localTime" && literals.single() + ":00" == value)) abort(TEMPORAL_INCONSISTENCY, "$path/resolved/$field")
            if (literals.isEmpty()) review += TEMPORAL_RESOLUTION
        }
        val precision = time.s("precision")
        if (clock != null && (precision !in setOf("MINUTE", "SECOND") || (precision == "MINUTE" && clock.length == 8 && clock.takeLast(2) != "00"))) abort(TEMPORAL_INCONSISTENCY, path)
        if (clock != null && precision == "SECOND" && clock.length != 8) abort(TEMPORAL_INCONSISTENCY, path)
        if (date == null || (precision in setOf("MINUTE", "SECOND") && clock == null)) review += TEMPORAL_RESOLUTION
        if (resolved["instantEpochMillis"] != null) {
            val instant = long(resolved.text("instantEpochMillis")!!, "$path/resolved/instantEpochMillis")
            val candidates = CalendarChecks.possibleInstants(date!!, clock!!, timezone!!)
            if (instant !in candidates) abort(TEMPORAL_INCONSISTENCY, "$path/resolved/instantEpochMillis")
            if (candidates.size > 1) review += TEMPORAL_RESOLUTION
        } else if (date != null && clock != null && timezone != null) {
            val candidates = CalendarChecks.possibleInstants(date, clock, timezone)
            if (candidates.isEmpty()) abort(TEMPORAL_INCONSISTENCY, "$path/resolved")
            if (candidates.size > 1) review += TEMPORAL_RESOLUTION
        }
        // A self-consistent conversion can still use a wrong occurrence date. Never infer from capture.
        if (time.s("uncertainty") != "NONE_REPORTED") review += TEMPORAL_RESOLUTION
    }

    private fun compareTimes(first: JsonObject, second: JsonObject): Int? {
        val a = first["resolved"] as? JsonObject ?: return null
        val b = second["resolved"] as? JsonObject ?: return null
        val ai = a.text("instantEpochMillis")?.toLongOrNull()
        val bi = b.text("instantEpochMillis")?.toLongOrNull()
        if (ai != null && bi != null) return ai.compareTo(bi)
        // Partial day/minute precision overlaps; only compare fully comparable components.
        val ad = a.text("localDate") ?: return null
        val bd = b.text("localDate") ?: return null
        if (a.text("timezoneId") != b.text("timezoneId")) return null
        if (ad != bd) return ad.compareTo(bd)
        val at = a.text("localTime") ?: return 0
        val bt = b.text("localTime") ?: return 0
        return (if (at.length == 5) "$at:00" else at).compareTo(if (bt.length == 5) "$bt:00" else bt)
    }

    private fun period(value: JsonObject, path: String) {
        val from = value["from"] as? JsonObject
        val to = value["to"] as? JsonObject
        if (from != null && to != null && compareTimes(from, to)?.let { it > 0 } == true) abort(PERIOD_ORDER, path)
    }

    private fun recurrence(value: JsonObject, path: String) {
        review += RECURRENCE_REVIEW
        listOf("weekdays", "monthDays", "months", "times").forEach { key ->
            val fields = (value[key] as? JsonArray)?.values.orEmpty().map {
                val field = it.obj()["value"]
                if (key == "times" && field is JsonString && field.value.length == 5) JsonString(field.value + ":00") else field
            }
            if (fields.distinct().size != fields.size) abort(DUPLICATE_RECURRENCE, "$path/$key")
        }
        val months = (value["months"] as? JsonArray)?.values.orEmpty().map { it.obj()["value"]!!.integer() }
        val days = (value["monthDays"] as? JsonArray)?.values.orEmpty().map { it.obj()["value"]!!.integer() }
        if (months.isNotEmpty() && days.any { day -> months.any { month -> day > intArrayOf(31,29,31,30,31,30,31,31,30,31,30,31)[month - 1] } }) abort(INVALID_RECURRENCE, path)
        // Months with 29/30/31 days may need an explicit skip policy; never generate a calendar here.
        if (days.any { it > 28 }) review += TEMPORAL_RESOLUTION
    }

    private fun semantics(proposal: JsonObject, path: String) {
        val category = proposal.s("category")
        val data = proposal.o("data")
        val title = data.o("title")
        val assessment = title.o("assessment")
        val state = data.text("state")
        val activeState = state in setOf("PENDING", "IN_PROGRESS", "SCHEDULED", "ACTIVE", "COMPLETED", "HELD")
        if (activeState && assessment.s("polarity") == "NEGATED") abort(ACTIVITY_CONFLICT, "$path/data/state")
        if (state in setOf("SCHEDULED", "HELD", "COMPLETED", "ACTIVE", "IN_PROGRESS") &&
            assessment.s("modality") in setOf("POSSIBLE", "CONDITIONAL", "UNCERTAIN", "QUESTION", "DESIRED") &&
            data.o("state").o("assessment").s("modality") == "ASSERTED") abort(ACTIVITY_CONFLICT, "$path/data/state")
        checkSimpleClause(title, state, "$path/data/title")
        (data["state"] as? JsonObject)?.let { stateField ->
            checkSimpleClause(stateField, state, "$path/data/state")
            val a = stateField.o("assessment")
            if (activeState && a.s("polarity") == "NEGATED") abort(ACTIVITY_CONFLICT, "$path/data/state")
        }
        if (category == "EVENT" && data["startsAt"] != null && data["endsAt"] != null && compareTimes(data.o("startsAt"), data.o("endsAt"))?.let { it > 0 } == true) abort(PERIOD_ORDER, "$path/data/endsAt")
        listOf("occurrences" to "occurrenceId", "exceptions" to "exceptionId").forEach { (collection, idKey) ->
            val elements = (data[collection] as? JsonArray)?.values.orEmpty().map { it.obj() }
            if (elements.map { it.s(idKey) }.distinct().size != elements.size) abort(DUPLICATE_ID, "$path/data/$collection")
        }
        if (category == "ROUTINE" && data.text("patternKind") == "OBSERVED") {
            review += OBSERVED_PATTERN
            val pattern = data.o("patternKind")
            if (independentSources(pattern) < 2) abort(OBSERVATION_SUPPORT, "$path/data/patternKind")
            val occurrences = data.a("occurrences").map { it.obj() }
            val anchors = occurrences.map { it.o("at").o("anchor").s("sourceEntryId") }
            if (anchors.distinct().size < 2) abort(OBSERVATION_SUPPORT, "$path/data/occurrences")
        }
        val validity = (data["recurrence"] as? JsonObject)?.get("validity") as? JsonObject
        val observed = data["observedPeriod"] as? JsonObject
        (data["occurrences"] as? JsonArray)?.values.orEmpty().forEachIndexed { index, occurrence ->
            val item = occurrence.obj()
            val at = item.o("at")
            listOfNotNull(validity, observed).forEach { bounds ->
                if ((bounds["from"] as? JsonObject)?.let { compareTimes(at, it) }?.let { it < 0 } == true ||
                    (bounds["to"] as? JsonObject)?.let { compareTimes(at, it) }?.let { it > 0 } == true) abort(PERIOD_ORDER, "$path/data/occurrences/$index/at")
            }
            if (item.text("state") == "SKIPPED_EXPLICITLY" && item.o("state").o("assessment").s("basis") != "EXPLICIT") abort(OBSERVATION_SUPPORT, "$path/data/occurrences/$index/state")
            val date = (at["resolved"] as? JsonObject)?.text("localDate")
            val weekdays = ((data["recurrence"] as? JsonObject)?.get("weekdays") as? JsonArray)?.values.orEmpty().map { it.obj().s("value") }
            if (category == "ROUTINE" && date != null && weekdays.isNotEmpty() && data["exceptions"] == null &&
                CalendarChecks.weekday(date) !in weekdays) abort(INVALID_RECURRENCE, "$path/data/occurrences/$index/at")
        }
        if (validity != null) (data["exceptions"] as? JsonArray)?.values.orEmpty().forEachIndexed { index, exception ->
            val bounds = exception.obj().o("period")
            val vf = validity["from"] as? JsonObject
            val vt = validity["to"] as? JsonObject
            val ef = bounds["from"] as? JsonObject
            val et = bounds["to"] as? JsonObject
            if ((vf != null && et != null && compareTimes(et, vf)?.let { it < 0 } == true) ||
                (vt != null && ef != null && compareTimes(ef, vt)?.let { it > 0 } == true)) abort(PERIOD_ORDER, "$path/data/exceptions/$index/period")
        }
        if (category == "FACT") {
            val kind = data.text("factKind")
            if (kind in setOf("PERSONALITY_DESCRIPTION", "PREFERENCE", "BOUNDED_CONCLUSION", "BEHAVIOR_OBSERVATION")) review += PERSONAL_CLAIM
            if (kind == "BOUNDED_CONCLUSION" && independentSources(title) < 2) abort(ATTRIBUTION_SUPPORT, "$path/data/title")
            if (kind == "PERSONALITY_DESCRIPTION" && (assessment.s("basis") == "INFERRED" || data.o("attribution").s("kind") == "MODEL_CONCLUSION")) abort(ATTRIBUTION_SUPPORT, "$path/data/attribution")
        }
        if (category == "REMINDER") {
            val explicit = data.o("request")
            val a = explicit.o("assessment")
            if (a.s("basis") != "EXPLICIT" || a.s("modality") != "ASSERTED" || a.s("polarity") != "AFFIRMED" ||
                assessment.s("polarity") != "AFFIRMED" || assessment.s("modality") in setOf("POSSIBLE", "CONDITIONAL", "UNCERTAIN", "QUESTION")) abort(REMINDER_NOT_EXPLICIT, "$path/data/request")
            // A date alone objectively does not request a reminder. Unknown phrasings need human review.
            if (explicit.a("evidence").all { e -> Regex("[0-9 :/.-]+|(?i:mañana|hoy|lunes|martes|miércoles|jueves|viernes|sábado|domingo)").matches(e.obj().s("quote")) }) abort(REMINDER_NOT_EXPLICIT, "$path/data/request")
            val trigger = data.o("trigger")
            if (trigger.s("kind") == "BEFORE") {
                val targetCategory = proposals.getValue(data.o("target").o("value").s("proposalId")).s("category")
                val role = trigger.text("relativeTo")!!
                if ((role.startsWith("EVENT_") && targetCategory != "EVENT") || (role.startsWith("TASK_") && targetCategory != "TASK") || (role == "ROUTINE_OCCURRENCE" && targetCategory != "ROUTINE")) abort(REFERENCE_TYPE, "$path/data/trigger/relativeTo")
            }
        }
    }

    /** Deliberately narrow single-clause contradictions, not a general Spanish entailment model. */
    private fun checkSimpleClause(title: JsonObject, state: String?, path: String) {
        if (title.a("evidence").size != 1) return
        val clause = title.a("evidence").single().obj().s("quote").trim().trimEnd('.', '!').lowercase(Locale.ROOT)
        val a = title.o("assessment")
        // Do not extrapolate to "terminé la primera parte" or "cancelé la cancelación".
        if (clause == "ya terminé el informe" && state in setOf("PENDING", "IN_PROGRESS")) abort(ACTIVITY_CONFLICT, path)
        if (clause in setOf("cancelé la reunión", "cancelé la cita", "cancelé la tarea") && state in setOf("SCHEDULED", "PENDING", "HELD")) abort(ACTIVITY_CONFLICT, path)
        if (listOf(" pero ", " aunque ", " y ", " o ").any { it in clause }) return
        if (Regex("no tengo (una |un |la |el )?reunión([^.!?;]*)").matches(clause) && a.s("polarity") != "NEGATED") abort(ASSESSMENT_CONFLICT, path)
        if (Regex("(quizá|tal vez|puede que) [^.!?;]+").matches(clause) && a.s("modality") == "ASSERTED") abort(ASSESSMENT_CONFLICT, path)
    }

    private fun independentSources(field: JsonObject) = field.a("evidence").map { it.obj().s("sourceEntryId") }.distinct().size
    private fun long(value: String, path: String) = value.toLongOrNull() ?: abort(INT64_RANGE, path)
    private fun zone(value: String, path: String) { if (!CalendarChecks.validZone(value)) abort(INVALID_ZONE, path) }
    private fun abort(code: ProposalRejectionCode, path: String): Nothing = throw ValidationAbort(code, path)
}
