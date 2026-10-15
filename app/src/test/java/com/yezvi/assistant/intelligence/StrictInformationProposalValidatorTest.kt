package com.yezvi.assistant.intelligence

import com.yezvi.assistant.data.intelligence.*
import com.yezvi.assistant.data.intelligence.validation.*
import com.yezvi.assistant.domain.intelligence.*
import com.yezvi.assistant.domain.intelligence.ProposalRejectionCode.*
import com.yezvi.assistant.domain.memory.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StrictInformationProposalValidatorTest {
    private val validator = StrictInformationProposalValidator()
    private fun fixture(prefix: String) = F402Fixtures.cases.single { it.name.startsWith(prefix) }
    private fun tree(prefix: String) = BoundedJson(fixture(prefix).json).parse()
    private fun str(text: String) = JsonString(text)
    private fun num(value: Int) = JsonNumber(value.toBigDecimal())
    private fun json(text: String) = BoundedJson(text).parse()
    private fun change(value: JsonValue, pointer: String, replacement: JsonValue?): JsonValue {
        val parts = pointer.removePrefix("/").split('/')
        fun replace(node: JsonValue, index: Int): JsonValue = when (node) {
            is JsonObject -> JsonObject(node.fields.toMutableMap().apply {
                if (index == parts.lastIndex) { if (replacement == null) remove(parts[index]) else put(parts[index], replacement) }
                else put(parts[index], replace(getValue(parts[index]), index + 1))
            })
            is JsonArray -> JsonArray(node.values.toMutableList().apply {
                if (index == parts.lastIndex) set(parts[index].toInt(), replacement!!) else set(parts[index].toInt(), replace(get(parts[index].toInt()), index + 1))
            })
            else -> error("Synthetic mutation has an invalid pointer")
        }
        return replace(value, 0)
    }
    private fun render(value: JsonValue): String = when (value) {
        is JsonObject -> value.fields.entries.joinToString(",", "{", "}") { render(str(it.key)) + ":" + render(it.value) }
        is JsonArray -> value.values.joinToString(",", "[", "]") { render(it) }
        is JsonString -> buildString {
            append('"'); value.value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\")
                in '\u0000'..'\u001f' -> append("\\u%04x".format(c.code))
                else -> append(c)
            } }; append('"')
        }
        is JsonNumber -> value.value.toString()
        is JsonBoolean -> value.value.toString()
        JsonNull -> "null"
    }
    private fun result(prefix: String, vararg edits: Pair<String, JsonValue?>): ProposalValidationResult {
        var value = tree(prefix)
        edits.forEach { (pointer, replacement) -> value = change(value, pointer, replacement) }
        return validator.validateJson(fixture(prefix).request, render(value))
    }
    private fun reject(expected: ProposalRejectionCode, result: ProposalValidationResult) {
        assertTrue("$result", result is ProposalValidationResult.Rejected)
        assertEquals(expected, (result as ProposalValidationResult.Rejected).error.code)
    }
    private fun valid(result: ProposalValidationResult): ProposalValidationResult.Valid {
        assertTrue("$result", result is ProposalValidationResult.Valid)
        return result as ProposalValidationResult.Valid
    }

    @Test fun unknownVersionIsNotDowngraded() { reject(UNSUPPORTED_VERSION, result("06-", "/schemaVersion" to str("1.1.0"))) }
    @Test fun wrongRequestVersionIsRejected() {
        val c = fixture("06-")
        reject(UNSUPPORTED_VERSION, validator.validateJson(InformationExtractionRequest(c.request.requestId, c.request.extractionId, c.request.sources, "2.0.0"), c.json))
    }
    @Test fun malformedJsonAndTrailingDataAreRejected() {
        listOf("", "{", "{} {}", "[1,]", "{\"a\":1,}", "/* comment */{}", "NaN", "Infinity", "01", "1.", "1e", "{\"a\":+1}", "\uFEFF{}")
            .forEach { reject(MALFORMED_JSON, validator.validateJson(fixture("06-").request, it)) }
    }
    @Test fun duplicateDecodedKeysAreRejected() {
        reject(DUPLICATE_KEY, validator.validateJson(fixture("06-").request, "{\"a\":1,\"\\u0061\":2}"))
    }
    @Test fun utf8BudgetIsAppliedBeforeParsing() {
        reject(PAYLOAD_LIMIT, validator.validateJson(fixture("06-").request, " ".repeat(262145)))
        reject(PAYLOAD_LIMIT, validator.validateJson(fixture("06-").request, "\"" + "😀".repeat(65536) + "\""))
        reject(PAYLOAD_LIMIT, validator.validateUtf8(fixture("06-").request, ByteArray(262145)))
    }
    @Test fun exactByteBudgetIsNotAnOffByOneRejection() {
        val c = fixture("06-")
        val padded = c.json + " ".repeat(262144 - c.json.toByteArray(Charsets.UTF_8).size)
        valid(validator.validateUtf8(c.request, padded.toByteArray(Charsets.UTF_8)))
    }
    @Test fun depthAndNodeAndNumericWorkAreBounded() {
        val request = fixture("06-").request
        reject(PROCESSING_LIMIT, validator.validateJson(request, "[".repeat(34) + "0" + "]".repeat(34)))
        reject(PROCESSING_LIMIT, validator.validateJson(request, List(24001) { "0" }.joinToString(",", "[", "]")))
        reject(PROCESSING_LIMIT, validator.validateJson(request, "1e10000"))
        reject(PROCESSING_LIMIT, validator.validateJson(request, "1".repeat(129)))
    }
    @Test fun malformedUtf8AndUnpairedEscapesAreRejected() {
        val r = fixture("06-").request
        reject(INVALID_UNICODE, validator.validateUtf8(r, byteArrayOf(0xc3.toByte(), 0x28)))
        listOf("\"\\uD800\"", "\"\\uDC00\"", "\"\uD800\"").forEach { reject(INVALID_UNICODE, validator.validateJson(r, it)) }
    }
    @Test fun emojisAndCrLfRemainExactUtf16() {
        valid(result("12-"))
        reject(EVIDENCE_QUOTE, result("12-", "/proposals/0/data/title/evidence/0/quote" to str("envie el informe")))
        reject(EVIDENCE_RANGE, result("12-", "/proposals/0/data/title/evidence/0/range/startUtf16" to num(1)))
    }
    @Test fun evidenceMustUseTheExactRevision() {
        reject(REVISION_MISMATCH, result("12-", "/proposals/0/data/title/evidence/0/revision/correctionId" to str("7")))
    }
    @Test fun evidenceCannotUseAnotherAuthorizedEntryWithDifferentText() {
        reject(EVIDENCE_QUOTE, result("08-", "/proposals/1/data/title/evidence/0/sourceEntryId" to str("s-change-new")))
    }
    @Test fun correlationAndSnapshotMetadataCannotBeForged() {
        reject(CORRELATION, result("06-", "/requestId" to str("different-request")))
        reject(SNAPSHOT_MISMATCH, result("06-", "/sourceSnapshots/0/textSha256" to str("0".repeat(64))))
        reject(SNAPSHOT_MISMATCH, result("06-", "/sourceSnapshots/0/capturedAtEpochMillis" to str("0")))
    }
    @Test fun bigintEpochAndCorrectionIdMustFitLong() {
        reject(INT64_RANGE, result("06-", "/sourceSnapshots/0/capturedAtEpochMillis" to str("9223372036854775808")))
        reject(INT64_RANGE, result("12-", "/sourceSnapshots/0/revision/correctionId" to str("9223372036854775808")))
    }
    @Test fun authorizedWindowsDoNotAuthorizeTheWholeRevision() {
        val c = fixture("06-")
        val original = MemoryEntry("s-completed", "Ya terminé el informe.", CaptureSourceType.TEXT, 1792083600000L, "America/Lima", CaptureStatus.SAVED)
        val source = InformationExtractionSource.from(original, ranges = listOf(Utf16TextRange(0, 10)))
        val r = InformationExtractionRequest(c.request.requestId, c.request.extractionId, listOf(source))
        val value = change(tree("06-"), "/sourceSnapshots/0/authorizedRanges", json("[{\"startUtf16\":0,\"endUtf16\":10}]"))
        reject(EVIDENCE_RANGE, validator.validateJson(r, render(value)))
    }
    @Test fun sourceSnapshotDuplicatesCannotHideAnOmittedSource() {
        val snapshot = tree("08-").obj().a("sourceSnapshots").first()
        reject(SNAPSHOT_MISMATCH, result("08-", "/sourceSnapshots/1" to snapshot))
    }
    @Test fun evidenceBudgetIsIndependentOfSchemaPerFieldLimits() {
        var v = tree("03-")
        val p = v.obj().a("proposals").single().obj()
        fun many(node: JsonValue): JsonValue = when (node) {
            is JsonObject -> JsonObject(node.fields.mapValues { (key, value) -> if (key == "evidence" && value is JsonArray && value.values.isNotEmpty()) JsonArray(List(8) { value.values[it % value.values.size] }) else many(value) })
            is JsonArray -> JsonArray(node.values.map { many(it) })
            else -> node
        }
        val ps = List(12) { i -> change(many(p), "/proposalId", str("pattern-$i")) }
        v = change(v, "/proposals", JsonArray(ps))
        // Schema valid, but >1024 literal citations. Parser budget remains separate.
        reject(EVIDENCE_LIMIT, validator.validateJson(fixture("03-").request, render(v)))
    }
    @Test fun refTargetsMustHaveTheRightCategory() {
        reject(REFERENCE_TYPE, result("01-", "/proposals/4/data/project/value/proposalId" to str("ana")))
    }
    @Test fun selfReferenceAndIssueReferencesAreRejected() {
        reject(INVALID_REFERENCE, result("01-", "/proposals/4/data/project/value/proposalId" to str("resumen")))
        reject(INVALID_REFERENCE, result("04-", "/issues/0/affectedProposalIds" to json("[\"missing\"]")))
    }
    @Test fun dependencyCyclesAreRejected() {
        val t = tree("06-").obj().a("proposals").single()
        val title = t.obj().o("data").o("title")
        fun task(id: String, other: String): JsonValue {
            var p = change(t, "/proposalId", str(id))
            val ref = change(title, "/value", json("{\"proposalId\":\"$other\"}"))
            p = change(p, "/data/dependsOn", JsonArray(listOf(ref)))
            return p
        }
        val doc = change(tree("06-"), "/proposals", JsonArray(listOf(task("a", "b"), task("b", "a"))))
        reject(REFERENCE_CYCLE, validator.validateJson(fixture("06-").request, render(doc)))
    }
    @Test fun unknownFieldsAndNullsAreRejectedInsteadOfCoerced() {
        reject(STRUCTURE, result("06-", "/privateSecret" to str("never print me")))
        reject(STRUCTURE, result("06-", "/proposals/0/data/actor" to JsonNull))
        reject(STRUCTURE, result("06-", "/sourceSnapshots/0/capturedAtEpochMillis" to num(123)))
    }
    @Test fun supplementaryStringLengthsCountCodePointsNotUtf16() {
        valid(result("06-", "/proposals/0/data/title/value" to str("😀".repeat(1024))))
        reject(STRUCTURE, result("06-", "/proposals/0/data/title/value" to str("😀".repeat(1025))))
    }
    @Test fun enumAndRequiredRulesAndOutcomeConditionsAreEnforced() {
        reject(STRUCTURE, result("06-", "/proposals/0/data/title" to null))
        reject(STRUCTURE, result("06-", "/proposals/0/data/state/value" to str("CONFIRMED")))
        reject(STRUCTURE, result("06-", "/outcome" to str("NO_PROPOSALS")))
        reject(STRUCTURE, result("06-", "/outcome" to str("PARTIAL")))
    }
    @Test fun negationDoesNotBecomeAnActiveEvent() {
        val field = change(tree("05-").obj().a("proposals").single().obj().o("data").o("title"), "/value", str("SCHEDULED"))
        reject(ACTIVITY_CONFLICT, result("05-", "/proposals/0/data/state" to field))
        reject(ASSESSMENT_CONFLICT, result("05-", "/proposals/0/data/title/assessment/polarity" to str("AFFIRMED")))
    }
    @Test fun hypotheticalEventCannotBeClaimedScheduled() {
        var field = change(tree("04-").obj().a("proposals").single().obj().o("data").o("title"), "/value", str("SCHEDULED"))
        field = change(field, "/assessment/modality", str("ASSERTED"))
        reject(ACTIVITY_CONFLICT, result("04-", "/proposals/0/data/state" to field))
    }
    @Test fun completedTaskCannotBePendingWithTheSameCompletedCitation() {
        reject(ACTIVITY_CONFLICT, result("06-", "/proposals/0/data/state/value" to str("PENDING")))
    }
    @Test fun observedPatternCannotDeclareItsOwnCertaintyValidated() {
        reject(STRUCTURE, result("03-", "/proposals/0/data/patternKind/assessment/certaintyValidation" to str("VALIDATED")))
        assertTrue(ProposalReviewRequirement.OBSERVED_PATTERN in valid(result("03-", "/proposals/0/data/patternKind/assessment/modality" to str("ASSERTED"))).reviewRequirements)
    }
    @Test fun repeatedCitationsOfTheSameEntryAreNotIndependentObservations() {
        val p = tree("03-").obj().a("proposals").single().obj().o("data").o("patternKind")
        reject(OBSERVATION_SUPPORT, result("03-", "/proposals/0/data/patternKind/evidence" to JsonArray(List(2) { p.a("evidence").first() })))
    }
    @Test fun personalConclusionsRequireIndependentEvidenceAndRemainUncertain() {
        val p = tree("11-").obj().a("proposals")[1].obj().o("data").o("title")
        reject(ATTRIBUTION_SUPPORT, result("11-", "/proposals/1/data/title/evidence" to JsonArray(List(2) { p.a("evidence").first() })))
        assertTrue(ProposalReviewRequirement.PERSONAL_CLAIM in valid(result("13-")).reviewRequirements)
    }
    @Test fun inferredPersonalityIsNotConvertedIntoAProfile() {
        reject(ATTRIBUTION_SUPPORT, result("13-", "/proposals/1/data/attribution/kind" to str("MODEL_CONCLUSION")))
    }
    @Test fun remindersRequireExplicitAffirmedRequest() {
        reject(REMINDER_NOT_EXPLICIT, result("07-", "/proposals/2/data/request/assessment/modality" to str("POSSIBLE")))
        reject(REMINDER_NOT_EXPLICIT, result("07-", "/proposals/2/data/request/assessment/polarity" to str("NEGATED")))
    }
    @Test fun invalidRecurrenceCombinationsAndDuplicateValuesAreRejected() {
        reject(STRUCTURE, result("02-", "/proposals/0/data/recurrence/frequency/value" to str("DAILY")))
        reject(DUPLICATE_RECURRENCE, result("02-", "/proposals/0/data/recurrence/weekdays/1/value" to str("MO")))
    }
    @Test fun localDateAndIanaZoneAreRealNotJustWellShaped() {
        reject(STRUCTURE, result("14-", "/proposals/0/data/startsAt/resolved/localDate/value" to str("2026-02-30")))
        reject(INVALID_ZONE, result("14-", "/proposals/0/data/startsAt/resolved/timezoneId/value" to str("Mars/Olympus")))
    }
    @Test fun temporalPrecisionAndAnchorMustBeConsistent() {
        reject(TEMPORAL_INCONSISTENCY, result("14-", "/proposals/0/data/startsAt/precision" to str("DAY")))
        reject(SOURCE_NOT_AUTHORIZED, result("14-", "/proposals/0/data/startsAt/anchor/sourceEntryId" to str("other")))
    }
    @Test fun unresolvedRelativeDatesArePreservedForF405() {
        val r = valid(result("04-"))
        assertTrue(ProposalReviewRequirement.TEMPORAL_RESOLUTION in r.reviewRequirements)
        assertTrue(r.document.json.contains("\"resolved\":null"))
    }
    @Test fun exactInstantMustMatchLocalDateTimeAndZone() {
        reject(TEMPORAL_INCONSISTENCY, result("14-", "/proposals/0/data/startsAt/resolved/instantEpochMillis/value" to str("0")))
    }
    @Test fun dstGapAndFoldAreNotSilentlyNormalized() {
        assertTrue(CalendarChecks.possibleInstants("2026-03-08", "02:30", "America/New_York").isEmpty())
        assertEquals(2, CalendarChecks.possibleInstants("2026-11-01", "01:30", "America/New_York").size)
    }
    private fun resolvedDay(time: JsonValue): JsonValue {
        val t = time.obj()
        var result = change(t, "/resolved", JsonObject(mapOf("localDate" to t.o("expression"))))
        result = change(result, "/uncertainty", str("NONE_REPORTED"))
        return change(result, "/precision", str("DAY"))
    }
    @Test fun invertedResolvedPeriodsAreRejectedWithoutResolvingRelativeDates() {
        val period = tree("03-").obj().a("proposals").single().obj().o("data").o("observedPeriod")
        val reversed = JsonObject(mapOf("from" to resolvedDay(period["to"]!!), "to" to resolvedDay(period["from"]!!)))
        reject(PERIOD_ORDER, result("03-", "/proposals/0/data/observedPeriod" to reversed))
    }
    @Test fun occurrencesCannotFallOutsideAResolvedObservedPeriod() {
        val period = tree("03-").obj().a("proposals").single().obj().o("data").o("observedPeriod")
        val lateStart = JsonObject(mapOf("from" to resolvedDay(period["to"]!!)))
        val at = tree("03-").obj().a("proposals").single().obj().o("data").a("occurrences").first().obj().o("at")
        reject(PERIOD_ORDER, result("03-", "/proposals/0/data/observedPeriod" to lateStart,
            "/proposals/0/data/occurrences/0/at" to resolvedDay(at)))
    }
    @Test fun duplicateOccurrenceIdsAndSourceRevisionsCannotInflateObservations() {
        reject(DUPLICATE_ID, result("03-", "/proposals/0/data/occurrences/1/occurrenceId" to str("o0")))
        val at = tree("03-").obj().a("proposals").single().obj().o("data").a("occurrences").first().obj().o("at")
        reject(OBSERVATION_SUPPORT, result("03-", "/proposals/0/data/occurrences/1/at" to at, "/proposals/0/data/occurrences/2/at" to at))
    }
    @Test fun absenceOfNotesDoesNotProveSkippedOccurrence() {
        reject(OBSERVATION_SUPPORT, result("03-", "/proposals/0/data/occurrences/0/state/value" to str("SKIPPED_EXPLICITLY"),
            "/proposals/0/data/occurrences/0/state/assessment/basis" to str("INFERRED"),
            "/proposals/0/data/occurrences/0/state/assessment/inferenceExplanation" to str("No hay otra nota")))
    }
    @Test fun impossibleCalendarDaysAndRepeatedTimesAreRejected() {
        val frequency = tree("02-").obj().a("proposals").single().obj().o("data").o("recurrence").o("frequency")
        val month = change(frequency, "/value", num(2))
        val day = change(frequency, "/value", num(30))
        val recurrence = JsonObject(mapOf("frequency" to change(frequency, "/value", str("YEARLY")),
            "months" to JsonArray(listOf(month)), "monthDays" to JsonArray(listOf(day))))
        reject(INVALID_RECURRENCE, result("02-", "/proposals/0/data/recurrence" to recurrence))
        val time = change(frequency, "/value", str("10:00"))
        reject(DUPLICATE_RECURRENCE, result("02-", "/proposals/0/data/recurrence/times" to JsonArray(listOf(time, time))))
        reject(DUPLICATE_RECURRENCE, result("02-", "/proposals/0/data/recurrence/times" to JsonArray(listOf(time, change(time, "/value", str("10:00:00"))))))
    }
    @Test fun resolvedWeekdaysMustAgreeWithThePatternWhenNoExceptionIsGiven() {
        val d = tree("03-").obj().a("proposals").single().obj().o("data")
        val weekday = change(d.o("recurrence").o("frequency"), "/value", str("TU"))
        reject(INVALID_RECURRENCE, result("03-", "/proposals/0/data/recurrence/weekdays" to JsonArray(listOf(weekday)),
            "/proposals/0/data/occurrences/0/at" to resolvedDay(d.a("occurrences").first().obj().o("at"))))
    }
    @Test fun consistentEpochDoesNotExcuseAFalseLiteralDate() {
        reject(TEMPORAL_INCONSISTENCY, result("14-", "/proposals/0/data/startsAt/resolved/localDate/value" to str("2026-10-21"),
            "/proposals/0/data/startsAt/resolved/instantEpochMillis/value" to str("1792612800000")))
    }
    @Test fun unsupportedFlagsCannotActivateAlarmsOrAcceptProposals() {
        reject(STRUCTURE, result("07-", "/proposals/2/data/enabled" to JsonBoolean(true)))
        reject(STRUCTURE, result("03-", "/proposals/0/proposalStatus" to str("CONFIRMED")))
    }
    @Test fun referenceTargetsCannotBeExternalSchemaOrNetworkResources() {
        reject(STRUCTURE, result("06-", "/\$ref" to str("https://invalid.example/schema")))
    }
    @Test fun leapYearsAndKnownIanaAliasesAreCheckedOnApi24CompatibleApis() {
        assertTrue(CalendarChecks.validDate("2024-02-29"))
        assertFalse(CalendarChecks.validDate("2100-02-29"))
        assertTrue(CalendarChecks.validDate("2000-02-29"))
        assertFalse(CalendarChecks.validZone("PST"))
        assertFalse(CalendarChecks.validZone("SystemV/EST5"))
        assertTrue(CalendarChecks.validZone("America/Lima"))
        assertTrue(CalendarChecks.validZone("CET"))
        assertTrue(CalendarChecks.validZone("EST"))
    }
    @Test fun uncertainReportedActivityIsNotMistakenForAcceptedTruth() {
        val r = valid(result("06-", "/proposals/0/data/title/assessment/modality" to str("UNCERTAIN"),
            "/proposals/0/data/state/assessment/modality" to str("UNCERTAIN")))
        assertTrue(r.document.requiresHumanReview)
        assertTrue(ProposalReviewRequirement.UNCERTAIN_CLAIM in r.reviewRequirements)
    }
    @Test fun localIdsMustNotContainAnUnseenFinalNewline() {
        reject(STRUCTURE, result("06-", "/proposals/0/proposalId" to str("informe\n")))
    }
    @Test fun completionOfASubstepIsNotForcedOntoTheWholeTask() {
        val text = "Ya terminé la primera parte del informe."
        val entry = MemoryEntry("s-completed", text, CaptureSourceType.TEXT, 1792083600000L, "America/Lima", CaptureStatus.SAVED)
        val source = InformationExtractionSource.from(entry)
        val c = fixture("06-")
        val request = InformationExtractionRequest(c.request.requestId, c.request.extractionId, listOf(source))
        var v = tree("06-")
        val edits = listOf(
            "/sourceSnapshots/0/textSha256" to str(source.snapshot.textSha256),
            "/sourceSnapshots/0/textLengthUtf16" to num(text.length),
            "/sourceSnapshots/0/authorizedRanges/0/endUtf16" to num(text.length),
            "/proposals/0/data/title/evidence/0/range/startUtf16" to num(text.indexOf("el informe")),
            "/proposals/0/data/title/evidence/0/range/endUtf16" to num(text.indexOf("el informe") + "el informe".length),
            "/proposals/0/data/state/value" to str("PENDING"),
            "/proposals/0/data/state/evidence/0/quote" to str(text.dropLast(1)),
            "/proposals/0/data/state/evidence/0/range/endUtf16" to num(text.length - 1),
        )
        edits.forEach { (path, replacement) -> v = change(v, path, replacement) }
        val checked = valid(validator.validateJson(request, render(v)))
        assertTrue(ProposalReviewRequirement.LINGUISTIC_SUPPORT_UNDECIDED in checked.reviewRequirements)
    }
    @Test fun secondPrecisionCannotInventMissingSeconds() {
        reject(TEMPORAL_INCONSISTENCY, result("14-", "/proposals/0/data/startsAt/precision" to str("SECOND")))
    }
    @Test fun wrapperMetadataCannotDisagreeWithCheckedJson() {
        val c = fixture("06-")
        reject(DOCUMENT_METADATA, validator.validate(c.request, UnvalidatedProposalDocument("1.0.0", ExtractionOutcome.NO_PROPOSALS, c.json)))
    }
    @Test fun validNoProposalsIsDifferentFromExtractionFailure() = runTest {
        val c = fixture("09-")
        val fake = FakeInformationExtractor(listOf(RecordedExtractionScenario(c.request, RecordedExtractionResponse.Document(c.json, c.outcome))))
        val extracted = fake.extract(c.request) as InformationExtractionResult.NoProposals
        assertEquals(0, valid(validator.validate(c.request, extracted.document)).document.proposalCount)
        assertEquals(ExtractionError.NO_MATCHING_FIXTURE, (fake.extract(fixture("06-").request) as InformationExtractionResult.Failure).error)
    }
    @Test fun simulatedErrorIsNotPresentedAsValidatedProposals() = runTest {
        val c = fixture("06-")
        val fake = FakeInformationExtractor(listOf(RecordedExtractionScenario(c.request, RecordedExtractionResponse.SimulatedFailure)))
        assertEquals(InformationExtractionResult.Failure(ExtractionError.SIMULATED_FAILURE, ExtractionMode.SYNTHETIC_FIXTURE), fake.extract(c.request))
    }
    @Test fun fakeOutputRequiresExplicitValidationEvenWhenItIsBroken() = runTest {
        val c = fixture("06-")
        val fake = FakeInformationExtractor(listOf(RecordedExtractionScenario(c.request, RecordedExtractionResponse.Document("not json", c.outcome))))
        val output = fake.extract(c.request) as InformationExtractionResult.Proposals
        reject(MALFORMED_JSON, validator.validate(c.request, output.document))
    }
    @Test fun scoresNeverBecomeCalibratedCertainty() {
        val score = json("{\"value\":0.9,\"scale\":\"UNNORMALIZED_MODEL_SCORE\",\"methodId\":\"unknown-method\"}")
        val r = valid(result("06-", "/proposals/0/data/title/assessment/technicalScore" to score))
        assertTrue(ProposalReviewRequirement.TECHNICAL_SCORE_NOT_TRUSTED in r.reviewRequirements)
        assertTrue(r.document.requiresHumanReview)
    }
    @Test fun diagnosticsDoNotContainProviderTextUnknownKeysOrPrivateQuotes() {
        val r = result("06-", "/secret-personal-key" to str("private text"))
        val message = r.toString()
        assertFalse(message.contains("secret-personal-key"))
        assertFalse(message.contains("private text"))
        assertFalse(valid(result("06-")).toString().contains("terminé"))
    }
    @Test fun canonicalResourceIsByteIdenticalAndNoFixturesArePackagedAsResources() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/proposals.schema.json")).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(CanonicalProposalSchema.SHA256, hash)
        assertNull(javaClass.getResource("/fixtures/sources.json"))
    }
    @Test fun validatorHasNoRoomNetworkUiOrExtractorEffects() {
        val actual = File(F402Fixtures.root, "../../../../app/src/main/java/com/yezvi/assistant/data/intelligence/validation").canonicalFile
        val files = actual.listFiles { f -> f.extension == "kt" }!!.toList()
        assertEquals(5, files.size)
        val forbidden = Regex("(?m)^import (android\\.|androidx\\.|java\\.net\\.|okhttp|retrofit|com\\.yezvi\\.assistant\\.(ui|data\\.(local|repository)))")
        files.forEach { assertFalse(it.name, forbidden.containsMatchIn(it.readText())) }
    }
}
