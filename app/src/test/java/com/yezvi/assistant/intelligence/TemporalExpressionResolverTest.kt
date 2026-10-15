package com.yezvi.assistant.intelligence

import com.yezvi.assistant.data.intelligence.temporal.DeterministicTemporalExpressionResolver
import com.yezvi.assistant.domain.intelligence.*
import com.yezvi.assistant.domain.memory.*
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class TemporalExpressionResolverTest {
    private val resolver = DeterministicTemporalExpressionResolver()

    @Test fun relativeDaysUseHistoricalCaptureInstantAndItsLocalDate() {
        val captured = utc(2026, Calendar.OCTOBER, 16, 4, 30) // 23:30 on Oct 15 in Lima.
        assertEquals("2026-10-16", resolve("mañana", captured, "America/Lima").localDate)
        assertEquals("2026-10-17", resolve("mañana", captured, "America/New_York").localDate)
        assertEquals("2026-10-17", resolve("mañana", captured, "Europe/Madrid").localDate)
        assertEquals("2026-10-15", resolve("hoy", captured, "America/Lima").localDate)
        assertEquals("2026-10-14", resolve("ayer", captured, "America/Lima").localDate)
        assertEquals("2026-10-17", resolve("pasado mañana", captured, "America/Lima").localDate)
        assertEquals("2026-10-29", resolve("dentro de 2 semanas", captured, "America/Lima").localDate)
        assertEquals("2026-10-18", resolve("dentro de 3 días", captured, "America/Lima").localDate)
        assertEquals("2027-01-01", resolve("mañana", utc(2026, Calendar.DECEMBER, 31, 17, 0), "America/Lima").localDate)
        assertEquals("2024-03-10", resolve("mañana", utc(2024, Calendar.MARCH, 10, 4, 30), "America/New_York").localDate)
        assertEquals("2020-01-01", resolve("mañana", utc(2020, Calendar.JANUARY, 1, 4, 30), "America/New_York").localDate)
    }

    @Test fun explicitAndYearlessSpanishDatesRetainTheirPrecision() {
        val leap = resolve("29 de febrero de 2024", DEFAULT_CAPTURE, "America/Lima")
        assertEquals(TemporalResolutionStatus.RESOLVED, leap.status)
        assertEquals("2024-02-29", leap.localDate)
        assertEquals(TemporalResolutionStatus.INVALID, resolve("29 de febrero de 2025", DEFAULT_CAPTURE, "America/Lima").status)
        assertEquals(TemporalResolutionStatus.INVALID, resolve("30 de febrero de 2024", DEFAULT_CAPTURE, "America/Lima").status)
        val yearless = resolve("5 de marzo", DEFAULT_CAPTURE, "America/Lima")
        assertEquals(TemporalResolutionStatus.PARTIALLY_RESOLVED, yearless.status)
        assertEquals("03-05", yearless.monthDayWithoutYear)
        assertNull(yearless.localDate)
    }

    @Test fun weekdayAndCalendarPeriodsRemainAmbiguousWithoutPolicy() {
        assertEquals(TemporalResolutionReason.WEEKDAY_NEEDS_REFERENCE,
            resolve("el viernes", DEFAULT_CAPTURE, "America/Lima").reason)
        assertEquals(TemporalResolutionReason.NEXT_WEEKDAY_NEEDS_POLICY,
            resolve("el próximo viernes", DEFAULT_CAPTURE, "America/Lima").reason)
        assertEquals(TemporalResolutionReason.MONTH_BOUNDARY_UNDEFINED,
            resolve("a fin de mes", DEFAULT_CAPTURE, "America/Lima").reason)
        assertEquals(TemporalResolutionReason.WEEK_PERIOD_UNDEFINED,
            resolve("la otra semana", DEFAULT_CAPTURE, "America/Lima").reason)
        assertEquals(TemporalResolutionReason.DAY_PART_HAS_NO_CLOCK_TIME,
            resolve("en la tarde", DEFAULT_CAPTURE, "America/Lima").reason)
    }

    @Test fun explicitWeekdayMustAgreeWithDateAndIntervalsMustBeCoherent() {
        assertEquals(TemporalResolutionStatus.RESOLVED,
            resolve("viernes 20 de octubre de 2023", DEFAULT_CAPTURE, "America/Lima").status)
        assertEquals(TemporalResolutionReason.INVALID_DATE,
            resolve("lunes 20 de octubre de 2023", DEFAULT_CAPTURE, "America/Lima").reason)
        val dates = resolve("del 20 al 22 de octubre de 2026", DEFAULT_CAPTURE, "America/Lima")
        assertEquals("2026-10-20", dates.localDate)
        assertEquals("2026-10-22", dates.endLocalDate)
        val crossYear = resolve("del 30 de diciembre de 2026 al 2 de enero de 2027", DEFAULT_CAPTURE, "America/Lima")
        assertEquals("2026-12-30", crossYear.localDate)
        assertEquals("2027-01-02", crossYear.endLocalDate)
        assertEquals("15:00", resolve("de 15:00 a 16:00", DEFAULT_CAPTURE, "America/Lima").localTime)
        assertEquals("16:00", resolve("de 15:00 a 16:00", DEFAULT_CAPTURE, "America/Lima").endLocalTime)
        assertEquals(TemporalResolutionReason.INTERVAL_ORDER,
            resolve("de 16:00 a 15:00", DEFAULT_CAPTURE, "America/Lima").reason)
        assertEquals(TemporalResolutionReason.INTERVAL_ORDER,
            resolve("del 22 al 20 de octubre de 2026", DEFAULT_CAPTURE, "America/Lima").reason)
    }

    @Test fun explicitHoursDoNotInventAmPmAndPartOfDayNeverCreatesClockTime() {
        val morning = resolve("hoy a las nueve de la mañana", DEFAULT_CAPTURE, "America/Lima")
        assertEquals("09:00", morning.localTime)
        assertNotNull(morning.instantEpochMillis)
        val evening = resolve("hoy a las nueve de la noche", DEFAULT_CAPTURE, "America/Lima")
        assertEquals("21:00", evening.localTime)
        assertEquals(TemporalResolutionStatus.AMBIGUOUS, resolve("hoy a las nueve", DEFAULT_CAPTURE, "America/Lima").status)
        val afternoon = resolve("mañana por la tarde", DEFAULT_CAPTURE, "America/Lima")
        assertEquals("2026-10-16", afternoon.localDate)
        assertEquals("AFTERNOON", afternoon.dayPart)
        assertNull(afternoon.localTime)
        assertNull(afternoon.instantEpochMillis)
        assertEquals(TemporalResolutionStatus.INVALID,
            resolve("2026-02-30", DEFAULT_CAPTURE, "America/Lima").status)
        assertEquals(TemporalResolutionReason.INVALID_LOCAL_TIME,
            resolve("2026-10-20 25:00", DEFAULT_CAPTURE, "America/Lima").reason)

        val morningContext = setup("Por la mañana", DEFAULT_CAPTURE, "America/Lima")
        val morningInput = document(morningContext).copyJson(document(morningContext).json
            .replace("\"value\":\"Por la mañana\",\"evidence\"", "\"value\":\"mañana\",\"evidence\""))
        val morningResult = (resolver.resolve(morningContext.request, morningInput) as TemporalResolutionResult.Completed).resolutions.single()
        assertEquals("MORNING", morningResult.dayPart)
        assertNull(morningResult.localDate)

        val hourContext = setup("a las nueve de la noche", DEFAULT_CAPTURE, "America/Lima")
        val hourInput = document(hourContext).copyJson(document(hourContext).json
            .replace("\"value\":\"a las nueve de la noche\",\"evidence\"", "\"value\":\"a las nueve\",\"evidence\""))
        val hourResult = (resolver.resolve(hourContext.request, hourInput) as TemporalResolutionResult.Completed).resolutions.single()
        assertEquals("21:00", hourResult.localTime)
        assertEquals(TemporalResolutionStatus.PARTIALLY_RESOLVED, hourResult.status)
    }

    @Test fun dstGapsAndOverlapsAreNotSilentlyChosen() {
        assertEquals(TemporalResolutionReason.DST_GAP,
            resolve("2024-03-10 02:30 America/New_York", DEFAULT_CAPTURE, "America/Lima").reason)
        val nyOverlap = resolve("2024-11-03 01:30 America/New_York", DEFAULT_CAPTURE, "America/Lima")
        assertEquals(TemporalResolutionStatus.AMBIGUOUS, nyOverlap.status)
        assertEquals(TemporalResolutionReason.DST_OVERLAP, nyOverlap.reason)
        assertNull(nyOverlap.instantEpochMillis)
        val madridOverlap = resolve("2024-10-27 02:30 Europe/Madrid", DEFAULT_CAPTURE, "America/Lima")
        assertEquals(TemporalResolutionReason.DST_OVERLAP, madridOverlap.reason)
        val invalidZone = setup("hoy", DEFAULT_CAPTURE, "Not/AZone")
        val rejected = resolver.resolve(invalidZone.request, document(invalidZone)) as TemporalResolutionResult.Rejected
        assertEquals(ProposalRejectionCode.INVALID_ZONE, rejected.error.code)
    }

    @Test fun outputPreservesExactJsonEvidenceUtf16AndDoesNotPromoteNegatedOrHypotheticalProposals() {
        val text = "📝 No tengo reunión el viernes."
        val setup = setup(text, DEFAULT_CAPTURE, "America/Lima", polarity = "NEGATED", modality = "QUESTION")
        val unvalidated = document(setup)
        val result = resolver.resolve(setup.request, unvalidated) as TemporalResolutionResult.Completed
        assertEquals(unvalidated.json, result.validatedDocument.json)
        val resolution = result.resolutions.single()
        assertEquals(TemporalResolutionStatus.AMBIGUOUS, resolution.status)
        assertEquals(text.length, resolution.evidence.single().range.endUtf16) // Kotlin String length is UTF-16.
        assertEquals(text, resolution.evidence.single().quote)
        assertEquals(SourceTextRevision.Original, resolution.anchorRevision)
        assertEquals(DEFAULT_CAPTURE, resolution.capturedAtEpochMillis)
        assertEquals("America/Lima", resolution.captureTimezoneId)

        val hypothetical = setup("Quizá tenga reunión mañana.", DEFAULT_CAPTURE, "America/Lima", modality = "POSSIBLE")
        val tentative = resolver.resolve(hypothetical.request, document(hypothetical)) as TemporalResolutionResult.Completed
        assertEquals("2026-10-16", tentative.resolutions.single().localDate)
        assertTrue(tentative.validatedDocument.requiresHumanReview)
        assertTrue(tentative.validatedDocument.json.contains("\"proposalStatus\":\"PROPOSED\""))
    }

    @Test fun eventExpressionIsRecomputedFromEvidenceInsteadOfTrustingProposedResolution() {
        val setup = setup("2026-10-20 15:00 America/Lima", DEFAULT_CAPTURE, "America/Lima")
        val valid = document(setup)
        val completed = resolver.resolve(setup.request, valid) as TemporalResolutionResult.Completed
        val resolution = completed.resolutions.single()
        assertEquals(TemporalResolutionStatus.RESOLVED, resolution.status)
        assertEquals("2026-10-20", resolution.localDate)
        assertEquals("15:00", resolution.localTime)
        assertEquals(1792526400000L, resolution.instantEpochMillis)
        assertEquals("2026-10-20 15:00 America/Lima", resolution.originalExpression)
        assertEquals(valid.json, completed.validatedDocument.json)
    }

    @Test fun taskDueDateAndCanonicalFactRoutineReminderFixturesKeepTheirRoles() {
        val task = setup("mañana", DEFAULT_CAPTURE, "America/Lima", category = "TASK", temporalKey = "dueAt")
        val taskResult = resolver.resolve(task.request, document(task)) as TemporalResolutionResult.Completed
        assertEquals("TASK", taskResult.resolutions.single().category)
        assertTrue(taskResult.resolutions.single().jsonPointer.endsWith("/dueAt"))

        val fixtureNames = setOf("01-multiple", "02-rutina-semanal", "03-patron-observado", "04-evento-ambiguo", "07-recordatorio-explicito", "12-revision-utf16")
        val outputs = F402Fixtures.cases.filter { it.name in fixtureNames }.map { fixture ->
            val input = UnvalidatedProposalDocument("1.0.0", fixture.outcome, fixture.json)
            val output = resolver.resolve(fixture.request, input) as TemporalResolutionResult.Completed
            assertEquals(input.json, output.validatedDocument.json)
            output.resolutions
        }.flatten()
        assertTrue(outputs.any { it.category == "FACT" && it.jsonPointer.contains("occurredAt") })
        assertTrue("$outputs", outputs.any { it.category == "ROUTINE" && it.jsonPointer.contains("occurrences") })
        assertTrue(outputs.any { it.category == "EVENT" && it.jsonPointer.contains("startsAt") })
        assertTrue(outputs.any { it.category == "REMINDER" && it.jsonPointer.contains("trigger") })
    }

    @Test fun mismatchedEvidenceIsRejectedBeforeResolution() {
        val setup = setup("mañana", DEFAULT_CAPTURE, "America/Lima")
        val corrupted = document(setup).copyJson(document(setup).json.replace("s-temporal", "s-forged"))
        val result = resolver.resolve(setup.request, corrupted)
        assertTrue(result is TemporalResolutionResult.Rejected)
    }

    @Test fun schemaValidButIncorrectRelativeDateIsReportedAsAConflict() {
        val setup = setup("mañana", DEFAULT_CAPTURE, "America/Lima")
        val input = document(setup, proposedLocalDate = "2026-10-15")
        val output = resolver.resolve(setup.request, input) as TemporalResolutionResult.Completed
        val resolution = output.resolutions.single()
        assertEquals("2026-10-16", resolution.localDate) // recomputed from the capture, not the model date.
        assertEquals(TemporalResolutionStatus.AMBIGUOUS, resolution.status)
        assertEquals(TemporalResolutionReason.PROPOSED_RESOLUTION_CONFLICT, resolution.reason)
        assertTrue(resolution.proposedResolutionConflict)
        assertEquals(input.json, output.validatedDocument.json)
    }

    private fun resolve(expression: String, capturedAt: Long, zoneId: String): TemporalResolution {
        val setup = setup(expression, capturedAt, zoneId)
        val result = resolver.resolve(setup.request, document(setup))
        assertTrue("$expression: $result", result is TemporalResolutionResult.Completed)
        return (result as TemporalResolutionResult.Completed).resolutions.single()
    }

    private data class Setup(
        val request: InformationExtractionRequest,
        val source: InformationExtractionSource,
        val polarity: String,
        val modality: String,
        val category: String,
        val temporalKey: String,
    )

    private fun setup(
        text: String,
        capturedAt: Long,
        zone: String,
        polarity: String = "AFFIRMED",
        modality: String = "ASSERTED",
        category: String = "EVENT",
        temporalKey: String = "startsAt",
    ): Setup {
        val entry = MemoryEntry("s-temporal", text, CaptureSourceType.TEXT, capturedAt, zone, CaptureStatus.SAVED)
        val source = InformationExtractionSource.from(entry)
        return Setup(InformationExtractionRequest("request-temporal", "extraction-temporal", listOf(source)), source,
            polarity, modality, category, temporalKey)
    }

    private fun document(setup: Setup, proposedLocalDate: String? = null): UnvalidatedProposalDocument {
        val snapshot = setup.source.snapshot
        val range = setup.source.fragments.single().range
        val quote = setup.source.fragments.single().text
        val evidence = """{"sourceEntryId":"${snapshot.sourceEntryId}","revision":{"kind":"ORIGINAL"},"quote":"${escape(quote)}","range":{"startUtf16":${range.startUtf16},"endUtf16":${range.endUtf16}}}"""
        val assessment = """{"basis":"EXPLICIT","modality":"${setup.modality}","polarity":"${setup.polarity}","certaintyValidation":"UNVALIDATED"}"""
        val snapshotJson = """{"sourceEntryId":"${snapshot.sourceEntryId}","revision":{"kind":"ORIGINAL"},"sourceType":"TEXT","capturedAtEpochMillis":"${snapshot.capturedAtEpochMillis}","timezoneId":"${snapshot.timezoneId}","textSha256":"${snapshot.textSha256}","textLengthUtf16":${snapshot.textLengthUtf16},"authorizedRanges":[{"startUtf16":${range.startUtf16},"endUtf16":${range.endUtf16}}]}"""
        val resolved = proposedLocalDate?.let {
            """{"localDate":{"value":"$it","evidence":[$evidence],"assessment":$assessment}}"""
        } ?: "null"
        val precision = if (proposedLocalDate == null) "UNSPECIFIED" else "DAY"
        val uncertainty = if (proposedLocalDate == null) "UNRESOLVED" else "NONE_REPORTED"
        val json = """{"schemaVersion":"1.0.0","requestId":"request-temporal","extractionId":"extraction-temporal","offsetUnit":"UTF16_CODE_UNIT","sourceSnapshots":[$snapshotJson],"outcome":"COMPLETE","proposals":[{"proposalId":"event-temporal","category":"${setup.category}","proposalStatus":"PROPOSED","data":{"title":{"value":"Evento","evidence":[$evidence],"assessment":$assessment},"${setup.temporalKey}":{"expression":{"value":"${escape(quote)}","evidence":[$evidence],"assessment":$assessment},"anchor":{"sourceEntryId":"${snapshot.sourceEntryId}","revision":{"kind":"ORIGINAL"}},"precision":"$precision","uncertainty":"$uncertainty","resolved":$resolved}}}],"issues":[]}"""
        return UnvalidatedProposalDocument("1.0.0", ExtractionOutcome.COMPLETE, json)
    }

    private fun UnvalidatedProposalDocument.copyJson(json: String) = UnvalidatedProposalDocument(schemaVersion, outcome, json)
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        isLenient = false
        clear()
        set(year, month, day, hour, minute, 0)
    }.timeInMillis

    companion object {
        private const val DEFAULT_CAPTURE = 1792083600000L // Synthetic fixture capture, 2026-10-15 17:00Z.
    }
}
