package com.yezvi.assistant.intelligence

import com.yezvi.assistant.data.intelligence.FakeInformationExtractor
import com.yezvi.assistant.data.intelligence.RecordedExtractionResponse
import com.yezvi.assistant.data.intelligence.RecordedExtractionScenario
import com.yezvi.assistant.domain.intelligence.*
import com.yezvi.assistant.domain.memory.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InformationExtractorTest {
    private val cases get() = F402Fixtures.cases
    private fun case(prefix: String) = cases.single { it.name.startsWith(prefix) }
    private fun scenarios() = cases.map {
        RecordedExtractionScenario(it.request, RecordedExtractionResponse.Document(it.json, it.outcome))
    }
    private fun extractor() = FakeInformationExtractor(scenarios())

    private fun document(result: InformationExtractionResult): UnvalidatedProposalDocument = when (result) {
        is InformationExtractionResult.Proposals -> result.document
        is InformationExtractionResult.NoProposals -> result.document
        is InformationExtractionResult.Failure -> error("Unexpected controlled error: ${result.error}")
    }

    @Test
    fun allFourteenPositiveFixturesAreReplayedExactlyWithoutChangingTheContract() = runTest {
        val fake = extractor()
        assertEquals(14, cases.size)
        cases.forEach { fixture ->
            val result = fake.extract(fixture.request)
            val payload = document(result)
            assertEquals(ExtractionMode.SYNTHETIC_FIXTURE, result.mode)
            assertEquals(fixture.json, payload.json)
            assertEquals(fixture.outcome, payload.outcome)
            assertEquals("1.0.0", payload.schemaVersion)
            assertEquals(fixture.sha256, sha256(payload.json))
        }
    }

    @Test
    fun repeatedAndConcurrentRequestsHaveTheSameValueIndependentOfInvocationOrder() = runTest {
        val fake = extractor()
        val request = case("01-").request
        val expected = fake.extract(request)
        repeat(8) { assertEquals(expected, fake.extract(request)) }
        val simultaneous = List(8) { async { fake.extract(request) } }.awaitAll()
        assertTrue(simultaneous.all { it == expected })
        cases.reversed().forEach { fake.extract(it.request) }
        assertEquals(expected, fake.extract(request))
        assertEquals(expected, extractor().extract(request))
    }

    @Test
    fun fixturesCoverExactlyTheElevenCategoriesAndNeverAcceptedOrConfirmedProposals() {
        val categories = cases.flatMap { fixture ->
            Regex("\"category\": \"([A-Z_]+)\"").findAll(fixture.json).map { it.groupValues[1] }.toList()
        }.toSet()
        assertEquals(setOf("TASK", "EVENT", "ROUTINE", "HABIT", "REMINDER", "IDEA", "FACT", "DECISION", "GOAL", "PERSON", "PROJECT"), categories)
        cases.forEach { fixture ->
            assertFalse(fixture.json.contains("\"proposalStatus\": \"ACCEPTED\""))
            assertFalse(fixture.json.contains("\"certaintyValidation\": \"VALIDATED\""))
        }
    }

    @Test
    fun multipleProposalsAndRelationsRemainInTheSameCanonicalEnvelope() = runTest {
        val fixture = case("01-")
        val result = extractor().extract(fixture.request) as InformationExtractionResult.Proposals
        assertEquals(5, Regex("\"proposalStatus\":").findAll(result.document.json).count())
        assertTrue(result.document.json.contains("\"proposalId\": \"ana\""))
        assertTrue(result.document.json.contains("\"proposalId\": \"atlas\""))
        assertTrue(result.document.json.contains("\"factKind\""))
        assertEquals(ExtractionOutcome.COMPLETE, result.document.outcome)
    }

    @Test
    fun recordedNoProposalsRetainsItsIssueInsteadOfInventingAnAction() = runTest {
        val fixture = case("09-")
        val result = extractor().extract(fixture.request) as InformationExtractionResult.NoProposals
        assertEquals(fixture.json, result.document.json)
        assertEquals(ExtractionOutcome.NO_PROPOSALS, result.document.outcome)
        assertTrue(result.document.json.contains("\"proposals\": []"))
        assertTrue(result.document.json.contains("INSUFFICIENT_EVIDENCE"))
    }

    @Test
    fun ambiguityAndContradictionArePartialResultsRatherThanTransportErrors() = runTest {
        for (prefix in listOf("04-", "08-")) {
            val fixture = case(prefix)
            val result = extractor().extract(fixture.request) as InformationExtractionResult.Proposals
            assertEquals(ExtractionOutcome.PARTIAL, result.document.outcome)
            assertEquals(fixture.json, result.document.json)
        }
    }

    @Test
    fun unknownTextIsAControlledSyntheticFailureNotACannedGuess() = runTest {
        val fixture = case("06-")
        val changed = entry("Texto sintético que no pertenece a ningún escenario.", id = "s-completed")
        val request = InformationExtractionRequest(fixture.request.requestId, fixture.request.extractionId,
            listOf(InformationExtractionSource.from(changed)))
        val result = extractor().extract(request)
        assertEquals(InformationExtractionResult.Failure(ExtractionError.NO_MATCHING_FIXTURE, ExtractionMode.SYNTHETIC_FIXTURE), result)
    }

    @Test
    fun unknownMetadataOrCorrelationDoesNotReuseEvidenceFromADifferentRequest() = runTest {
        val fixture = case("06-")
        val wrongCorrelation = InformationExtractionRequest("another-request", fixture.request.extractionId, fixture.request.sources)
        val wrongCapture = InformationExtractionRequest(fixture.request.requestId, fixture.request.extractionId,
            listOf(InformationExtractionSource.from(entry("Ya terminé el informe.", "s-completed", captured = 7L))))
        for (request in listOf(wrongCorrelation, wrongCapture)) {
            val result = extractor().extract(request) as InformationExtractionResult.Failure
            assertEquals(ExtractionError.NO_MATCHING_FIXTURE, result.error)
        }
    }

    @Test
    fun unsupportedSchemaIsReportedWithoutDowngradingOrReadingAProvider() = runTest {
        val fixture = case("01-")
        val request = InformationExtractionRequest(fixture.request.requestId, fixture.request.extractionId,
            fixture.request.sources, schemaVersion = "2.0.0")
        assertEquals(InformationExtractionResult.Failure(ExtractionError.UNSUPPORTED_SCHEMA_VERSION, ExtractionMode.SYNTHETIC_FIXTURE), extractor().extract(request))
    }

    @Test
    fun configuredFailureIsDeterministicAndContainsNoPrivateExceptionOrText() = runTest {
        val fixture = case("01-")
        val fake = FakeInformationExtractor(listOf(RecordedExtractionScenario(fixture.request, RecordedExtractionResponse.SimulatedFailure)))
        val first = fake.extract(fixture.request)
        assertEquals(InformationExtractionResult.Failure(ExtractionError.SIMULATED_FAILURE, ExtractionMode.SYNTHETIC_FIXTURE), first)
        assertEquals(first, fake.extract(fixture.request))
        assertFalse(first.toString().contains("Ana"))
    }

    @Test
    fun cancellationPropagatesWithoutProducingALateSyntheticResult() = runTest {
        var cancellationObserved = false
        var returned = false
        val fake = extractor()
        val child = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.cancel()
            try {
                fake.extract(case("01-").request)
                returned = true
            } catch (cancelled: CancellationException) {
                cancellationObserved = true
                throw cancelled
            }
        }
        child.join()
        assertTrue(child.isCancelled)
        assertTrue(cancellationObserved)
        assertFalse(returned)
    }

    @Test
    fun captureInstantIsNotUsedToResolveThePastOccurrenceOrAmbiguousFriday() = runTest {
        val multiple = case("01-")
        val json = document(extractor().extract(multiple.request)).json
        val source = multiple.request.sources.single().snapshot
        assertTrue(json.contains("\"capturedAtEpochMillis\": \"${source.capturedAtEpochMillis}\""))
        assertTrue(json.contains("\"occurredAt\""))
        assertTrue(json.contains("\"value\": \"Ayer\""))
        assertTrue(json.contains("\"resolved\": null"))
        val ambiguous = document(extractor().extract(case("04-").request)).json
        assertTrue(ambiguous.contains("\"uncertainty\": \"AMBIGUOUS\""))
        assertFalse(ambiguous.contains("\"instantEpochMillis\""))
    }

    @Test
    fun correctedFixtureEvidenceUsesAbsoluteUtf16IndicesIncludingTheLeadingEmoji() = runTest {
        val fixture = case("12-")
        val source = fixture.request.sources.single()
        val text = source.fragments.single().text
        val json = document(extractor().extract(fixture.request)).json
        // Targeted assertion of this canonical fixture, not a generic JSON parser/validator.
        val match = Regex("\"quote\": \"envié el informe\",\\s*\"range\": \\{\\s*\"startUtf16\": (\\d+),\\s*\"endUtf16\": (\\d+)").find(json)!!
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].toInt()
        assertEquals(8, start)
        assertEquals("envié el informe", text.substring(start, end))
        assertTrue(text.startsWith("📝 "))
        assertTrue(text.contains("\r\n"))
        assertEquals(SourceTextRevision.Correction(9007199254740993L), source.snapshot.revision)
        assertEquals(CaptureSourceType.VOICE, source.snapshot.sourceType)
        assertEquals(text.length, source.snapshot.textLengthUtf16)
    }

    @Test
    fun everyPreparedSnapshotMatchesThePinnedSyntheticTextHash() {
        cases.flatMap { it.request.sources }.forEach { source ->
            assertEquals(F402Fixtures.expectedHashes.getValue(source.snapshot.sourceEntryId), source.snapshot.textSha256)
            assertEquals(sha256(source.fragments.single().text), source.snapshot.textSha256)
        }
    }

    @Test
    fun correctedTextPreservesOriginalCaptureMetadataAndDoesNotMutateRawText() {
        val original = entry("Original intacto", captured = 12L)
        val correction = MemoryTextVersion(original.id, "Texto corregido", 5L, 99L)
        val selected = InformationExtractionSource.from(original, correction)
        assertEquals("Texto corregido", selected.fragments.single().text)
        assertEquals("Original intacto", original.rawText)
        assertEquals(12L, selected.snapshot.capturedAtEpochMillis)
        assertEquals("America/Lima", selected.snapshot.timezoneId)
        assertEquals(SourceTextRevision.Correction(5L), selected.snapshot.revision)
    }

    @Test
    fun aSelectionRetainsOnlyAuthorizedTextWithOriginalAbsoluteOffsets() = runTest {
        val original = entry("Fuera de la selección. Texto autorizado.")
        val start = original.rawText.indexOf("Texto")
        val ranges = mutableListOf(Utf16TextRange(start, original.rawText.length))
        val source = InformationExtractionSource.from(original, ranges = ranges)
        ranges.clear()
        assertEquals("Texto autorizado.", source.fragments.single().text)
        assertEquals(start, source.fragments.single().range.startUtf16)
        assertEquals(sha256(original.rawText), source.snapshot.textSha256)
        assertFalse(source.toString().contains("Fuera"))
        assertFalse(source.fragments.single().toString().contains("Texto autorizado"))
        val result = FakeInformationExtractor().extract(InformationExtractionRequest("test-request", "test-extraction", listOf(source)))
        assertEquals(ExtractionError.NO_MATCHING_FIXTURE, (result as InformationExtractionResult.Failure).error)
    }

    @Test
    fun callerMutationCannotChangeTheRequestOrTheRecordedCatalog() = runTest {
        val fixture = case("01-")
        val mutableSources = fixture.request.sources.toMutableList()
        val request = InformationExtractionRequest(fixture.request.requestId, fixture.request.extractionId, mutableSources)
        val mutableScenarios = scenarios().toMutableList()
        val fake = FakeInformationExtractor(mutableScenarios)
        mutableSources.clear()
        mutableScenarios.clear()
        assertEquals(fixture.request, request)
        assertEquals(fixture.json, document(fake.extract(request)).json)
    }

    @Test
    fun restrictingARecognizedSourceDoesNotReplayEvidenceOutsideTheSelection() = runTest {
        val fixture = case("06-")
        val snapshot = fixture.request.sources.single().snapshot
        val memory = entry("Ya terminé el informe.", snapshot.sourceEntryId, snapshot.capturedAtEpochMillis)
        val source = InformationExtractionSource.from(memory, ranges = listOf(Utf16TextRange(14, 21)))
        val request = InformationExtractionRequest(fixture.request.requestId, fixture.request.extractionId, listOf(source))
        assertEquals("informe", source.fragments.single().text)
        assertEquals(ExtractionError.NO_MATCHING_FIXTURE, (extractor().extract(request) as InformationExtractionResult.Failure).error)
    }

    @Test
    fun sourceIdentifierBudgetCountsUnicodeCharactersAsTheSchemaSpecifies() {
        val permitted = InformationExtractionSource.from(entry("Texto sintético", id = "📝".repeat(128)))
        assertEquals(256, permitted.snapshot.sourceEntryId.length)
        assertThrows(IllegalArgumentException::class.java) {
            InformationExtractionSource.from(entry("Texto sintético", id = "📝".repeat(129)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotSelectHalfOfAnEmoji() {
        InformationExtractionSource.from(entry("📝 nota"), ranges = listOf(Utf16TextRange(1, 2)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotSelectOverlappingWindows() {
        InformationExtractionSource.from(entry("Una nota sintética"), ranges = listOf(Utf16TextRange(0, 8), Utf16TextRange(7, 10)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCorrectionCannotBorrowTheIdentityOfAnotherMemory() {
        InformationExtractionSource.from(entry("Original"), MemoryTextVersion("another-entry", "Corregido", 1L, 2L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anOriginalRevisionCannotSilentlyReplaceRawText() {
        val original = entry("Original")
        InformationExtractionSource.from(original, MemoryTextVersion(original.id, "Otro texto", null, null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateScenarioConfigurationFailsBeforeAnyExtraction() {
        val recorded = scenarios().first()
        FakeInformationExtractor(listOf(recorded, recorded))
    }

    @Test
    fun theOtherFourJsonFilesAreSupportArtifactsNotUnclassifiedResponses() {
        val all = F402Fixtures.root.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        val positive = File(F402Fixtures.root, "fixtures/valid").listFiles()!!.filter { it.extension == "json" }
        val negative = File(F402Fixtures.root, "fixtures/invalid").listFiles()!!.filter { it.extension == "json" }
        assertEquals(40, all.size)
        assertEquals(14, positive.size)
        assertEquals(12, negative.count { it.name.startsWith("s") })
        assertEquals(10, negative.count { it.name.startsWith("k") })
        val support = (all - positive.toSet() - negative.toSet()).map { it.relativeTo(F402Fixtures.root).invariantSeparatorsPath }.toSet()
        assertEquals(setOf("proposals.schema.json", "fixtures/sources.json", "fixtures/manifest.json", "fixtures/validation-report.json"), support)
    }

    @Test
    fun extractionBoundaryHasNoAndroidNetworkOrPersistenceDependencies() {
        val project = requireNotNull(F402Fixtures.root.parentFile?.parentFile?.parentFile?.parentFile)
        val paths = listOf(
            "app/src/main/java/com/yezvi/assistant/domain/intelligence/InformationExtractor.kt",
            "app/src/main/java/com/yezvi/assistant/domain/intelligence/InformationExtractionRequest.kt",
            "app/src/main/java/com/yezvi/assistant/data/intelligence/FakeInformationExtractor.kt",
        )
        val forbidden = Regex("(?m)^import (android\\.|androidx\\.|java\\.net\\.|java\\.io\\.|okhttp|retrofit|com\\.yezvi\\.assistant\\.data\\.(local|repository))")
        paths.forEach { path -> assertFalse("Unexpected platform, network or persistence dependency: $path", forbidden.containsMatchIn(File(project, path).readText())) }
    }

    private fun entry(text: String, id: String = "synthetic-entry", captured: Long = 1L) =
        MemoryEntry(id, text, CaptureSourceType.TEXT, captured, "America/Lima", CaptureStatus.SAVED)

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
