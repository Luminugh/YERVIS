package com.yezvi.assistant.data.intelligence.temporal

import com.yezvi.assistant.data.intelligence.validation.BoundedJson
import com.yezvi.assistant.data.intelligence.validation.CalendarChecks
import com.yezvi.assistant.data.intelligence.validation.JsonArray
import com.yezvi.assistant.data.intelligence.validation.JsonObject
import com.yezvi.assistant.data.intelligence.validation.JsonString
import com.yezvi.assistant.data.intelligence.validation.JsonValue
import com.yezvi.assistant.data.intelligence.validation.StrictInformationProposalValidator
import com.yezvi.assistant.domain.intelligence.*
import java.util.Calendar
import java.util.Collections
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Conservative deterministic calendar resolver. It first validates the unchanged exchange document,
 * then derives read-only values exclusively from literal evidence and the original capture snapshot.
 */
class DeterministicTemporalExpressionResolver : TemporalExpressionResolver {
    private val validator = StrictInformationProposalValidator()

    override fun resolve(
        request: InformationExtractionRequest,
        document: UnvalidatedProposalDocument,
    ): TemporalResolutionResult {
        val validation = validator.validate(request, document)
        if (validation is ProposalValidationResult.Rejected) return TemporalResolutionResult.Rejected(validation.error)
        val validated = (validation as ProposalValidationResult.Valid).document
        val root = try { BoundedJson(validated.json).parse() as? JsonObject } catch (_: RuntimeException) { null }
            ?: return TemporalResolutionResult.Rejected(
                ProposalValidationError(ProposalRejectionCode.MALFORMED_JSON, ""),
            )
        val results = mutableListOf<TemporalResolution>()
        val proposals = root["proposals"] as? JsonArray ?: return TemporalResolutionResult.Rejected(
            ProposalValidationError(ProposalRejectionCode.STRUCTURE, "/proposals"),
        )
        proposals.values.forEachIndexed { index, value ->
            val proposal = value as? JsonObject ?: return@forEachIndexed
            val proposalId = proposal.stringValue("proposalId") ?: return@forEachIndexed
            val category = proposal.stringValue("category") ?: return@forEachIndexed
            val data = proposal["data"] as? JsonObject ?: return@forEachIndexed
            collectTemporal(data, "/proposals/$index/data", proposalId, category, request, results)
        }
        return TemporalResolutionResult.Completed(validated, Collections.unmodifiableList(results))
    }

    private fun collectTemporal(
        value: JsonValue,
        pointer: String,
        proposalId: String,
        category: String,
        request: InformationExtractionRequest,
        output: MutableList<TemporalResolution>,
    ) {
        when (value) {
            is JsonArray -> value.values.forEachIndexed { index, child ->
                collectTemporal(child, "$pointer/$index", proposalId, category, request, output)
            }
            is JsonObject -> {
                if (value["expression"] is JsonObject && value["anchor"] is JsonObject && value["resolved"] != null) {
                    output += resolveOne(value, pointer, proposalId, category, request).copy(
                        reportedPrecision = value.stringValue("precision") ?: "UNSPECIFIED",
                        reportedUncertainty = value.stringValue("uncertainty") ?: "UNRESOLVED",
                    )
                } else {
                    value.fields.forEach { (key, child) ->
                        collectTemporal(child, "$pointer/${escapePointer(key)}", proposalId, category, request, output)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun resolveOne(
        temporal: JsonObject,
        pointer: String,
        proposalId: String,
        category: String,
        request: InformationExtractionRequest,
    ): TemporalResolution {
        val expressionField = temporal["expression"] as JsonObject
        val expression = expressionField.stringValue("value").orEmpty()
        val anchor = temporal["anchor"] as JsonObject
        val sourceId = anchor.stringValue("sourceEntryId").orEmpty()
        val revisionJson = anchor["revision"] as? JsonObject ?: JsonObject(emptyMap())
        val revision = revisionJson.toRevision() ?: SourceTextRevision.Original
        val source = request.sources.firstOrNull {
            it.snapshot.sourceEntryId == sourceId && it.snapshot.revision == revision
        }
        val evidence = ((expressionField["evidence"] as? JsonArray)?.values.orEmpty()).mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val evidenceRevision = (item["revision"] as? JsonObject)?.toRevision() ?: return@mapNotNull null
            val rangeJson = item["range"] as? JsonObject ?: return@mapNotNull null
            val start = rangeJson.intValue("startUtf16") ?: return@mapNotNull null
            val end = rangeJson.intValue("endUtf16") ?: return@mapNotNull null
            val quote = item.stringValue("quote") ?: return@mapNotNull null
            TemporalEvidenceReference(item.stringValue("sourceEntryId").orEmpty(), evidenceRevision, quote,
                Utf16TextRange(start, end))
        }
        if (source == null) {
            return invalidResult(proposalId, category, pointer, expression, evidence, sourceId, revision,
                0L, "", TemporalResolutionReason.EXPRESSION_NOT_LOCATED)
        }
        val snapshot = source.snapshot
        if (!CalendarChecks.validZone(snapshot.timezoneId)) {
            return invalidResult(proposalId, category, pointer, expression, evidence, sourceId, revision,
                snapshot.capturedAtEpochMillis, snapshot.timezoneId, TemporalResolutionReason.INVALID_SOURCE_ZONE)
        }
        val anchorEvidence = evidence.filter { it.sourceEntryId == sourceId && it.revision == revision }
        val literalEvidence = anchorEvidence.filter { it.quote.contains(expression, ignoreCase = true) }
        if (expression.isBlank() || literalEvidence.isEmpty() || literalEvidence.size != anchorEvidence.size) {
            return invalidResult(proposalId, category, pointer, expression, evidence, sourceId, revision,
                snapshot.capturedAtEpochMillis, snapshot.timezoneId,
                if (anchorEvidence.isEmpty()) TemporalResolutionReason.EXPRESSION_NOT_LOCATED
                else TemporalResolutionReason.EXPRESSION_NOT_LOCATED)
        }

        val candidates = literalEvidence.map {
            parseExpression(contextualExpression(expression, it.quote), snapshot.capturedAtEpochMillis, snapshot.timezoneId)
        }
        val unique = candidates.distinctBy { it.signature() }
        if (unique.size != 1) {
            return invalidResult(proposalId, category, pointer, expression, evidence, sourceId, revision,
                snapshot.capturedAtEpochMillis, snapshot.timezoneId, TemporalResolutionReason.CONFLICTING_EVIDENCE,
                status = TemporalResolutionStatus.AMBIGUOUS)
        }
        val parsed = unique.single()
        val derived = materialize(parsed, proposalId, category, pointer, expression, evidence, sourceId, revision,
            snapshot.capturedAtEpochMillis, snapshot.timezoneId)
        return compareProposedResolution(derived, temporal["resolved"] as? JsonObject)
    }

    private fun materialize(
        parsed: Parsed,
        proposalId: String,
        category: String,
        pointer: String,
        expression: String,
        evidence: List<TemporalEvidenceReference>,
        sourceId: String,
        revision: SourceTextRevision,
        capturedAt: Long,
        captureZone: String,
    ): TemporalResolution {
        if (parsed.status == TemporalResolutionStatus.INVALID) {
            return invalidResult(proposalId, category, pointer, expression, evidence, sourceId, revision,
                capturedAt, captureZone, parsed.reason)
        }
        val zone = parsed.zoneId ?: captureZone
        var status = parsed.status
        var reason = parsed.reason
        var instant: Long? = null
        if (parsed.localDate != null && parsed.localTime != null && !parsed.ambiguousClock) {
            val candidates = CalendarChecks.possibleInstants(parsed.localDate, parsed.localTime, zone)
            when (candidates.size) {
                0 -> { status = TemporalResolutionStatus.INVALID; reason = TemporalResolutionReason.DST_GAP }
                1 -> instant = candidates.single()
                else -> { status = TemporalResolutionStatus.AMBIGUOUS; reason = TemporalResolutionReason.DST_OVERLAP }
            }
        }
        if (parsed.ambiguousClock && status != TemporalResolutionStatus.INVALID) {
            status = TemporalResolutionStatus.AMBIGUOUS
            reason = TemporalResolutionReason.AMBIGUOUS_HOUR
        }
        return TemporalResolution(proposalId, category, pointer, expression, Collections.unmodifiableList(ArrayList(evidence)), sourceId, revision,
            capturedAt, captureZone, status, reason, parsed.localDate, parsed.endLocalDate,
            parsed.monthDayWithoutYear, parsed.localTime, parsed.endLocalTime, parsed.dayPart,
            if (parsed.localDate != null || parsed.localTime != null) zone else parsed.zoneId, instant)
    }

    /** A schema-valid model date is still only a suggestion; conflicts remain visible to the caller. */
    private fun compareProposedResolution(
        derived: TemporalResolution,
        proposed: JsonObject?,
    ): TemporalResolution {
        if (proposed == null || derived.status == TemporalResolutionStatus.INVALID) return derived
        fun proposedValue(key: String): String? = (proposed[key] as? JsonObject)?.stringValue("value")
        val conflict = listOf(
            proposedValue("localDate")?.let { it != derived.localDate },
            proposedValue("localTime")?.let { it != derived.localTime },
            proposedValue("timezoneId")?.let { it != derived.resolvedTimezoneId },
            proposedValue("instantEpochMillis")?.let { it.toLongOrNull() != derived.instantEpochMillis },
        ).any { it == true }
        if (!conflict) return derived
        return derived.copy(
            status = TemporalResolutionStatus.AMBIGUOUS,
            reason = TemporalResolutionReason.PROPOSED_RESOLUTION_CONFLICT,
            proposedResolutionConflict = true,
            instantEpochMillis = null,
        )
    }

    private fun invalidResult(
        proposalId: String,
        category: String,
        pointer: String,
        expression: String,
        evidence: List<TemporalEvidenceReference>,
        sourceId: String,
        revision: SourceTextRevision,
        capturedAt: Long,
        captureZone: String,
        reason: TemporalResolutionReason,
        status: TemporalResolutionStatus = TemporalResolutionStatus.INVALID,
    ) = TemporalResolution(proposalId, category, pointer, expression, Collections.unmodifiableList(ArrayList(evidence)), sourceId, revision,
        capturedAt, captureZone, status, reason)

    private fun parseExpression(text: String, capturedAt: Long, captureZone: String): Parsed {
        val input = text.trim().lowercase(Locale.ROOT)
        val zoneMatch = IANA_ZONE.find(text)?.value
        val explicitZone = zoneMatch?.takeIf(CalendarChecks::validZone)
        if (zoneMatch != null && explicitZone == null) return Parsed.invalid(TemporalResolutionReason.INVALID_SOURCE_ZONE)
        if (!CalendarChecks.validZone(captureZone)) return Parsed.invalid(TemporalResolutionReason.INVALID_SOURCE_ZONE)

        if (MONTH_BOUNDARY.containsMatchIn(input)) return Parsed.ambiguous(TemporalResolutionReason.MONTH_BOUNDARY_UNDEFINED)
        if (AMBIGUOUS_WEEK.containsMatchIn(input)) return Parsed.ambiguous(TemporalResolutionReason.WEEK_PERIOD_UNDEFINED)
        if (NEXT_WEEKDAY.containsMatchIn(input)) return Parsed.ambiguous(TemporalResolutionReason.NEXT_WEEKDAY_NEEDS_POLICY)

        val zone = explicitZone ?: captureZone
        val dateResult = parseDate(input, capturedAt, captureZone)
        if (dateResult.status == TemporalResolutionStatus.INVALID || dateResult.status == TemporalResolutionStatus.AMBIGUOUS) {
            return dateResult.copy(zoneId = zone)
        }
        val weekday = WEEKDAY.find(input)?.groupValues?.get(1)?.let(::weekdayCode)
        if (weekday != null && dateResult.localDate != null && CalendarChecks.weekday(dateResult.localDate) != weekday) {
            return Parsed.invalid(TemporalResolutionReason.INVALID_DATE, zone)
        }
        if (weekday != null && dateResult.localDate == null && dateResult.monthDayWithoutYear == null) {
            return Parsed.ambiguous(TemporalResolutionReason.WEEKDAY_NEEDS_REFERENCE, zone)
        }

        val dayPart = when {
            Regex("\\b(por la mañana|de la mañana|en la mañana)\\b").containsMatchIn(input) -> "MORNING"
            Regex("\\b(por la tarde|de la tarde|en la tarde)\\b").containsMatchIn(input) -> "AFTERNOON"
            Regex("\\b(por la noche|de la noche|en la noche)\\b").containsMatchIn(input) -> "EVENING"
            else -> null
        }
        val clock = parseClock(input)
        if (clock.invalid) return Parsed.invalid(TemporalResolutionReason.INVALID_LOCAL_TIME, zone)
        if (dateResult.status == TemporalResolutionStatus.INVALID) return dateResult.copy(zoneId = zone)
        if (dateResult.status == TemporalResolutionStatus.AMBIGUOUS) return dateResult.copy(zoneId = zone)

        val localDate = dateResult.localDate
        val baseStatus = when {
            dateResult.status == TemporalResolutionStatus.PARTIALLY_RESOLVED -> TemporalResolutionStatus.PARTIALLY_RESOLVED
            localDate == null && (clock.start != null || dayPart != null) -> TemporalResolutionStatus.PARTIALLY_RESOLVED
            localDate != null && (dayPart != null || clock.ambiguous) -> TemporalResolutionStatus.PARTIALLY_RESOLVED
            else -> dateResult.status
        }
        val reason = when {
            clock.ambiguous -> TemporalResolutionReason.AMBIGUOUS_HOUR
            dayPart != null && clock.start == null -> TemporalResolutionReason.DAY_PART_HAS_NO_CLOCK_TIME
            clock.start != null -> if (clock.end != null) TemporalResolutionReason.EXPLICIT_INTERVAL else TemporalResolutionReason.EXPLICIT_TIME
            dayPart != null -> TemporalResolutionReason.DAY_PART
            dateResult.reason != TemporalResolutionReason.UNSUPPORTED_EXPRESSION -> dateResult.reason
            else -> TemporalResolutionReason.UNSUPPORTED_EXPRESSION
        }
        if (clock.end != null && clock.start != null && clock.end <= clock.start) {
            return Parsed.invalid(TemporalResolutionReason.INTERVAL_ORDER, zone)
        }
        if (localDate == null && dateResult.monthDayWithoutYear == null && dateResult.reason == TemporalResolutionReason.UNSUPPORTED_EXPRESSION &&
            weekday == null && dayPart == null && clock.start == null) {
            return Parsed.ambiguous(TemporalResolutionReason.UNSUPPORTED_EXPRESSION, zone)
        }
        val finalStatus = if (clock.ambiguous) TemporalResolutionStatus.AMBIGUOUS else baseStatus
        val finalReason = if (clock.ambiguous) TemporalResolutionReason.AMBIGUOUS_HOUR else reason
        return Parsed(finalStatus, finalReason, localDate, dateResult.endLocalDate,
            dateResult.monthDayWithoutYear, clock.start, clock.end, dayPart, zone, clock.ambiguous)
    }

    /** Uses only immediately adjacent words in the same verified quote to disambiguate meridiem/daypart. */
    private fun contextualExpression(expression: String, quote: String): String {
        val value = expression.trim()
        val start = quote.indexOf(value, ignoreCase = true)
        if (start < 0) return value
        val before = quote.substring(0, start).takeLast(16).lowercase(Locale.ROOT)
        val after = quote.substring(start + value.length).take(20)
        if (value.equals("mañana", ignoreCase = true)) {
            val dayPartPrefix = Regex("(?:por|de|en)\\s+la\\s*$").find(before)?.value?.trim()
            if (dayPartPrefix != null) return "$dayPartPrefix $value"
        }
        if (value.lowercase(Locale.ROOT).matches(Regex("a las (?:[0-9]{1,2}|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce)"))) {
            val dayPartSuffix = Regex("^\\s+(?:de la|por la|en la)\\s+(mañana|tarde|noche)", RegexOption.IGNORE_CASE)
                .find(after)?.value?.trim()
            if (dayPartSuffix != null) return "$value $dayPartSuffix"
        }
        return value
    }

    private fun parseDate(input: String, capturedAt: Long, zoneId: String): Parsed {
        ISO_DATE.find(input)?.let { match ->
            val date = match.value
            if (!CalendarChecks.validDate(date)) return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            val range = ISO_DATE_RANGE.find(input)
            val end = range?.groupValues?.get(1)
            if (end != null && (!CalendarChecks.validDate(end) || end < date)) {
                return Parsed.invalid(if (end < date) TemporalResolutionReason.INTERVAL_ORDER else TemporalResolutionReason.INVALID_DATE)
            }
            return Parsed(TemporalResolutionStatus.RESOLVED,
                if (end == null) TemporalResolutionReason.EXPLICIT_DATE else TemporalResolutionReason.EXPLICIT_INTERVAL,
                date, end)
        }
        SPANISH_COMPACT_RANGE.find(input)?.let { range ->
            val month = monthNumber(range.groupValues[3])
            val startDay = range.groupValues[1].toIntOrNull() ?: return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            val endDay = range.groupValues[2].toIntOrNull() ?: return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            val year = range.groupValues[4].toIntOrNull() ?: return Parsed.ambiguous(TemporalResolutionReason.YEAR_NOT_STATED)
            val start = "%04d-%02d-%02d".format(Locale.ROOT, year, month, startDay)
            val end = "%04d-%02d-%02d".format(Locale.ROOT, year, month, endDay)
            if (!CalendarChecks.validDate(start) || !CalendarChecks.validDate(end)) return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            if (end < start) return Parsed.invalid(TemporalResolutionReason.INTERVAL_ORDER)
            return Parsed(TemporalResolutionStatus.RESOLVED, TemporalResolutionReason.EXPLICIT_INTERVAL, start, end)
        }
        SPANISH_DATE_RANGE.find(input)?.let { range ->
            val startDay = range.groupValues[1].toIntOrNull() ?: return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            val startMonth = monthNumber(range.groupValues[2])
            val endDay = range.groupValues[4].toIntOrNull() ?: return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            val endMonth = monthNumber(range.groupValues[5])
            val startYear = range.groupValues[3].toIntOrNull()
            val endYear = range.groupValues[6].toIntOrNull()
            if (startYear == null && endYear == null) return Parsed.ambiguous(TemporalResolutionReason.YEAR_NOT_STATED)
            val fromYear = startYear ?: endYear!!
            val toYear = endYear ?: startYear!!
            val start = "%04d-%02d-%02d".format(Locale.ROOT, fromYear, startMonth, startDay)
            val end = "%04d-%02d-%02d".format(Locale.ROOT, toYear, endMonth, endDay)
            if (!CalendarChecks.validDate(start) || !CalendarChecks.validDate(end)) return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            if (end < start) return Parsed.invalid(TemporalResolutionReason.INTERVAL_ORDER)
            return Parsed(TemporalResolutionStatus.RESOLVED, TemporalResolutionReason.EXPLICIT_INTERVAL, start, end)
        }
        val spanish = SPANISH_DATE.find(input)
        if (spanish != null) {
            val day = spanish.groupValues[1].toIntOrNull() ?: return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            val month = monthNumber(spanish.groupValues[2])
            val year = spanish.groupValues[3].toIntOrNull()
            if (year == null) {
                val maximumDay = when (month) { 2 -> 29; 4, 6, 9, 11 -> 30; else -> 31 }
                if (day !in 1..maximumDay) return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
                return Parsed(TemporalResolutionStatus.PARTIALLY_RESOLVED, TemporalResolutionReason.YEAR_NOT_STATED,
                    monthDayWithoutYear = "%02d-%02d".format(Locale.ROOT, month, day))
            }
            val date = "%04d-%02d-%02d".format(Locale.ROOT, year, month, day)
            if (!CalendarChecks.validDate(date)) return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            return Parsed(TemporalResolutionStatus.RESOLVED, TemporalResolutionReason.EXPLICIT_DATE, date)
        }
        val folded = input.replace(Regex("\\s+"), " ")
        val relative = when {
            Regex("\\bpasado mañana\\b").containsMatchIn(folded) -> 2
            Regex("\\bayer\\b").containsMatchIn(folded) -> -1
            Regex("\\bhoy\\b").containsMatchIn(folded) -> 0
            Regex("\\bmañana\\b").containsMatchIn(folded) &&
                !Regex("\\b(por|de|en) la mañana\\b").containsMatchIn(folded) -> 1
            else -> null
        }
        if (relative != null) {
            val date = addCalendarDays(capturedAt, zoneId, relative.toLong()) ?: return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            return Parsed(TemporalResolutionStatus.RESOLVED, TemporalResolutionReason.RELATIVE_DATE, date)
        }
        val amount = RELATIVE_AMOUNT.find(folded)
        if (amount != null) {
            val n = amount.groupValues[1].toLongOrNull() ?: return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            if (n !in 1..36500) return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            val multiplier = if (amount.groupValues[2].startsWith("semana")) 7L else 1L
            val date = addCalendarDays(capturedAt, zoneId, n * multiplier) ?: return Parsed.invalid(TemporalResolutionReason.INVALID_DATE)
            return Parsed(TemporalResolutionStatus.RESOLVED, TemporalResolutionReason.RELATIVE_DATE, date)
        }
        return Parsed(TemporalResolutionStatus.PARTIALLY_RESOLVED, TemporalResolutionReason.UNSUPPORTED_EXPRESSION)
    }

    private fun parseClock(input: String): ClockResult {
        val dayPart = when {
            Regex("\\b(de la mañana|por la mañana|en la mañana)\\b").containsMatchIn(input) -> "MORNING"
            Regex("\\b(de la tarde|por la tarde|en la tarde)\\b").containsMatchIn(input) -> "AFTERNOON"
            Regex("\\b(de la noche|por la noche|en la noche)\\b").containsMatchIn(input) -> "EVENING"
            else -> null
        }
        val range = TIME_RANGE.find(input)
        if (range != null) {
            val start = normalize24h(range.groupValues[1], range.groupValues[2], range.groupValues[3])
            val end = normalize24h(range.groupValues[4], range.groupValues[5], range.groupValues[6])
            if (start == null || end == null) return ClockResult(invalid = true)
            return ClockResult(start, end)
        }
        val numeric = NUMERIC_TIME.findAll(input).firstOrNull { it.value.contains(':') || it.value.startsWith("a las ") }
        if (numeric != null) {
            val hour = numeric.groupValues[1].toIntOrNull() ?: return ClockResult(invalid = true)
            val minute = numeric.groupValues[2].ifEmpty { "00" }
            val second = numeric.groupValues[3]
            val periodHour = if (minute != "00" || numeric.groupValues[2].isNotEmpty()) {
                if (dayPart == null || hour > 12) hour else applyDayPart(hour, dayPart)
            } else applyDayPart(hour, dayPart)
            if (periodHour == null) return ClockResult(ambiguous = true)
            val value = normalize24h(periodHour.toString(), minute, second)
                ?: return ClockResult(invalid = true)
            val ambiguousBareHour = numeric.groupValues[2].isEmpty() && dayPart == null && hour in 1..12
            return ClockResult(value, ambiguous = ambiguousBareHour)
        }
        val spoken = SPOKEN_HOUR.find(input)
        if (spoken != null) {
            val hour = WORD_HOURS[spoken.groupValues[1]] ?: return ClockResult(invalid = true)
            val value = applyDayPart(hour, dayPart) ?: return ClockResult(ambiguous = true)
            return ClockResult("%02d:00".format(Locale.ROOT, value), ambiguous = dayPart == null)
        }
        return ClockResult()
    }

    private fun applyDayPart(hour: Int, part: String?): Int? {
        if (hour !in 0..23) return null
        return when (part) {
            "MORNING" -> if (hour in 1..11) hour else if (hour == 12) null else hour
            "AFTERNOON" -> when (hour) { in 1..11 -> hour + 12; 12 -> 12; else -> hour }
            "EVENING" -> when (hour) { in 1..11 -> hour + 12; 12 -> 0; else -> hour }
            else -> if (hour <= 12) null else hour
        }
    }

    private fun normalize24h(hourText: String, minuteText: String, secondText: String): String? {
        val hour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.toIntOrNull() ?: return null
        val second = secondText.ifEmpty { "0" }.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return if (secondText.isEmpty()) "%02d:%02d".format(Locale.ROOT, hour, minute)
        else "%02d:%02d:%02d".format(Locale.ROOT, hour, minute, second)
    }

    private fun addCalendarDays(capturedAt: Long, zoneId: String, count: Long): String? {
        if (count !in -36500..36500) return null
        return try {
            val zone = TimeZone.getTimeZone(zoneId)
            val captureCalendar = GregorianCalendar(zone, Locale.ROOT).apply {
                gregorianChange = java.util.Date(Long.MIN_VALUE)
                isLenient = false
                timeInMillis = capturedAt
            }
            val civil = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
                gregorianChange = java.util.Date(Long.MIN_VALUE)
                isLenient = false
                clear()
                set(captureCalendar.get(Calendar.YEAR), captureCalendar.get(Calendar.MONTH), captureCalendar.get(Calendar.DAY_OF_MONTH), 12, 0, 0)
                add(Calendar.DAY_OF_MONTH, count.toInt())
            }
            "%04d-%02d-%02d".format(Locale.ROOT, civil.get(Calendar.YEAR), civil.get(Calendar.MONTH) + 1,
                civil.get(Calendar.DAY_OF_MONTH)).takeIf(CalendarChecks::validDate)
        } catch (_: IllegalArgumentException) { null }
    }

    private fun weekdayCode(day: String) = when (day) {
        "lunes" -> "MO"; "martes" -> "TU"; "miércoles", "miercoles" -> "WE"; "jueves" -> "TH"
        "viernes" -> "FR"; "sábado", "sabado" -> "SA"; "domingo" -> "SU"; else -> ""
    }

    private fun monthNumber(name: String) = MONTHS.getValue(name)
    private fun escapePointer(value: String) = value.replace("~", "~0").replace("/", "~1")
    private fun JsonObject.stringValue(key: String) = (this[key] as? JsonString)?.value
    private fun JsonObject.intValue(key: String) = (this[key] as? com.yezvi.assistant.data.intelligence.validation.JsonNumber)?.value?.toInt()
    private fun JsonObject.toRevision(): SourceTextRevision? {
        return when (stringValue("kind")) {
            "ORIGINAL" -> SourceTextRevision.Original
            "CORRECTION" -> stringValue("correctionId")?.toLongOrNull()?.takeIf { it > 0 }?.let(SourceTextRevision::Correction)
            else -> null
        }
    }

    private data class ClockResult(val start: String? = null, val end: String? = null, val ambiguous: Boolean = false, val invalid: Boolean = false)
    private data class Parsed(
        val status: TemporalResolutionStatus,
        val reason: TemporalResolutionReason,
        val localDate: String? = null,
        val endLocalDate: String? = null,
        val monthDayWithoutYear: String? = null,
        val localTime: String? = null,
        val endLocalTime: String? = null,
        val dayPart: String? = null,
        val zoneId: String? = null,
        val ambiguousClock: Boolean = false,
    ) {
        fun signature() = listOf(status, reason, localDate, endLocalDate, monthDayWithoutYear, localTime, endLocalTime, dayPart, zoneId, ambiguousClock)
        companion object {
            fun invalid(reason: TemporalResolutionReason, zone: String? = null) = Parsed(TemporalResolutionStatus.INVALID, reason, zoneId = zone)
            fun ambiguous(reason: TemporalResolutionReason, zone: String? = null) = Parsed(TemporalResolutionStatus.AMBIGUOUS, reason, zoneId = zone)
        }
    }

    private companion object {
        val MONTHS = mapOf("enero" to 1, "febrero" to 2, "marzo" to 3, "abril" to 4, "mayo" to 5, "junio" to 6,
            "julio" to 7, "agosto" to 8, "septiembre" to 9, "setiembre" to 9, "octubre" to 10, "noviembre" to 11, "diciembre" to 12)
        val WORD_HOURS = mapOf("una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6,
            "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12)
        val ISO_DATE = Regex("(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}(?![0-9])")
        val ISO_DATE_RANGE = Regex("(?:del?\\s+)?(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}\\s+(?:al|a|hasta)\\s+([0-9]{4}-[0-9]{2}-[0-9]{2})(?![0-9])")
        val SPANISH_DATE = Regex("\\b([0-9]{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)(?:\\s+de\\s+([0-9]{4}))?\\b")
        val SPANISH_COMPACT_RANGE = Regex("\\bdel\\s+([0-9]{1,2})\\s+al\\s+([0-9]{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)(?:\\s+de\\s+([0-9]{4}))\\b")
        val SPANISH_DATE_RANGE = Regex("\\bdel\\s+([0-9]{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)(?:\\s+(?:de|del)\\s+([0-9]{4}))?\\s+al\\s+([0-9]{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)(?:\\s+(?:de|del)\\s+([0-9]{4}))?\\b")
        val RELATIVE_AMOUNT = Regex("\\bdentro de ([0-9]{1,5}) (días?|semanas?)\\b")
        val MONTH_BOUNDARY = Regex("\\b(fin|final|fines) de mes\\b|\\ba fin de mes\\b")
        val AMBIGUOUS_WEEK = Regex("\\b(la otra semana|la próxima semana|la semana que viene|la siguiente semana)\\b")
        val NEXT_WEEKDAY = Regex("\\b(próximo|proximo|siguiente) (lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo)\\b|\\b(lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo) que viene\\b")
        val WEEKDAY = Regex("\\b(lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo)\\b")
        val NUMERIC_TIME = Regex("\\b(?:a las )?([0-9]{1,2})(?::([0-9]{2})(?::([0-9]{2}))?)?\\b")
        val TIME_RANGE = Regex("(?:de|desde|entre)\\s+([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?\\s+(?:a|hasta|y)\\s+([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?")
        val SPOKEN_HOUR = Regex("\\ba las (una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce)\\b")
        val IANA_ZONE = Regex("\\b[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)+\\b")
    }
}
