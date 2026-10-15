package com.yezvi.assistant.data.speech

import com.yezvi.assistant.domain.speech.FinalTranscript
import com.yezvi.assistant.domain.speech.RecognitionFailureReason
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextStartResult
import com.yezvi.assistant.domain.speech.SpeechToTextState
import com.yezvi.assistant.presentation.capture.VoiceCaptureSessionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SherpaOfflineSpeechToTextEngineTest {
    @Test
    fun prolongedPauseDoesNotDecodeFinalizeOrReopenCapture() = runTest {
        val fixture = fixture(ShortArray(1_600) { 9_000 }, backgroundScope, testScheduler)
        val controller = controller(fixture)
        try {
            controller.start(); runCurrent(); fixture.input.readStarted.await()
            assertTrue(controller.pause()); runCurrent()
            advanceTimeBy(180_000); runCurrent()
            assertTrue(controller.state.value is SpeechToTextState.Paused)
            assertEquals(0, fixture.decoder.decodedSamples)
            assertEquals(1, fixture.input.starts)
            assertEquals(0, fixture.input.closes)
            controller.cancel()
            assertEquals(SpeechToTextState.Cancelled, controller.state.value)
            assertResourcesReleased(fixture)
        } finally { controller.close() }
    }

    @Test
    fun emptyDecoderResultIsNoSpeechAndReleasesResources() = runTest {
        val fixture = fixture(ShortArray(1_600) { 9_000 }, backgroundScope, testScheduler, decodedText = "   ")
        val controller = controller(fixture)
        try {
            controller.start(); runCurrent(); fixture.input.readStarted.await()
            controller.stop(); runCurrent()
            assertEquals(SpeechToTextState.Error(SpeechToTextError.RecognitionFailed(
                RecognitionFailureReason.NO_SPEECH)), controller.state.value)
            assertResourcesReleased(fixture)
        } finally { controller.close() }
    }

    @Test
    fun corruptModelAndMissingNativeRuntimeNeverAcquireMicrophone() = runTest {
        for ((failure, expected) in listOf(
            java.io.IOException("Synthetic checksum mismatch") to SpeechToTextError.OfflineModelMissing,
            UnsatisfiedLinkError("Synthetic unavailable JNI") to SpeechToTextError.EngineUnavailable,
        )) {
            val fixture = fixture(ShortArray(1_600), backgroundScope, testScheduler, recognizerError = failure)
            fixture.engine.start(SpeechToTextRequest("es"))
            assertEquals(SpeechToTextState.Error(expected), fixture.engine.state.value)
            assertEquals(0, fixture.input.opens)
            assertEquals(1, fixture.detector.releases)
        }
    }

    @Test
    fun consecutiveSessionsOnSameEngineUseFreshResourcesAndNoInheritedTranscript() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val inputs = List(3) { FakeInput(ShortArray(1_600) { 9_000 }) }
        val detectors = List(3) { CountingDetector() }
        val decoders = List(3) { FakeDecoder(null, {}, "sesión ${it + 1}") }
        var current = -1
        val engine = SherpaOfflineSpeechToTextEngine(
            backgroundScope, { true },
            captureFactory = { LocalPcmCaptureLease(inputs[current]) },
            segmenterFactory = { current++; SileroPcmSegmenter(detectors[current], dispatcher) },
            recognizerFactory = { SherpaOfflineRecognizerAdapter(decoders[current], vocabulary(), dispatcher) },
            workerDispatcher = dispatcher,
        )
        val controller = VoiceCaptureSessionController(engine, backgroundScope, { true })
        try {
            repeat(3) { index ->
                controller.start(); runCurrent(); inputs[index].readStarted.await()
                assertEquals(SpeechToTextStartResult.SessionAlreadyActive, engine.start(SpeechToTextRequest("es")))
                controller.stop(); runCurrent()
                assertEquals(SpeechToTextState.Result(FinalTranscript("sesión ${index + 1}")), controller.state.value)
                assertEquals(1, inputs[index].closes)
                assertEquals(1, detectors[index].releases)
                assertEquals(1, decoders[index].releases)
            }
        } finally { controller.close() }
    }
    @Test
    fun pauseAndResumeKeepAudioVadAndRecognizerSessionUntilExplicitStop() = runTest {
        val fixture = fixture(ShortArray(1_600) { 9_000 }, backgroundScope, testScheduler)
        val controller = controller(fixture)

        controller.start()
        runCurrent()
        fixture.input.readStarted.await()
        runCurrent()
        assertTrue(controller.state.value is SpeechToTextState.Listening)

        assertTrue(controller.pause())
        runCurrent()
        assertTrue(controller.state.value is SpeechToTextState.Paused)
        assertEquals(0, fixture.input.stops)
        assertEquals(0, fixture.input.closes)
        assertEquals(0, fixture.detector.releases)
        assertEquals(0, fixture.decoder.releases)

        assertTrue(controller.resume())
        runCurrent()
        assertTrue(controller.state.value is SpeechToTextState.Listening)
        assertEquals(1, fixture.input.starts)

        controller.stop()
        runCurrent()
        assertTrue(controller.state.value is SpeechToTextState.Result)
        assertResourcesReleased(fixture)
        controller.close()
    }

    @Test
    fun optionalDiagnosticsReceiveApprovedTextAndObserverFailureCannotLoseIt() = runTest {
        val seen = mutableListOf<LocalSpeechDiagnosticSegment>()
        val fixture = fixture(ShortArray(1600) { 9000 }, backgroundScope, testScheduler,
            onSegment = { seen += it; throw IllegalStateException("Synthetic diagnostic failure") })
        val controller = controller(fixture)
        controller.start(); runCurrent(); fixture.input.readStarted.await()
        controller.stop(); runCurrent()
        assertEquals(SpeechToTextState.Result(FinalTranscript("nota local sintética")), controller.state.value)
        assertEquals(1, seen.size)
        assertTrue(seen.single().sessionId > 0L)
        assertEquals("", seen.single().textBefore)
        assertEquals("nota local sintética", seen.single().recognition.text)
        assertResourcesReleased(fixture)
        controller.close()
    }

    @Test
    fun lifecycleLossDuringNativeDecodeNeverPublishesDiagnosticMetadata() = runTest {
        val seen = mutableListOf<LocalSpeechDiagnosticSegment>()
        lateinit var fixture: Fixture
        fixture = fixture(ShortArray(1600) { 9000 }, backgroundScope, testScheduler,
            onDecode = { fixture.markLifecycleStopped() }, onSegment = { seen += it })
        val controller = controller(fixture)
        controller.start(); runCurrent(); fixture.input.readStarted.await()
        controller.stop(); runCurrent()
        assertEquals(SpeechToTextState.Cancelled, controller.state.value)
        assertTrue(seen.isEmpty())
        assertResourcesReleased(fixture)
        controller.close()
    }
    @Test
    fun pcmFlowsThroughVadAndSherpaToControllerOnlyAfterFinish() = runTest {
        val fixture = fixture(shortArrayOf(12_000, 9_000, -7_000).let { seed -> ShortArray(1_600) { seed[it % seed.size] } }, backgroundScope, testScheduler)
        val controller = controller(fixture)

        controller.start()
        runCurrent()
        fixture.input.readStarted.await()
        assertTrue(controller.state.value is SpeechToTextState.Listening)
        controller.stop()
        runCurrent()

        assertEquals(SpeechToTextState.Result(FinalTranscript("nota local sintética")), controller.state.value)
        assertTrue(fixture.decoder.decodedSamples > 0)
        assertEquals(1, fixture.input.opens)
        assertEquals(1, fixture.input.starts)
        assertEquals(1, fixture.input.stops)
        assertEquals(1, fixture.input.closes)
        assertEquals(1, fixture.detector.releases)
        assertEquals(1, fixture.decoder.releases)
        controller.close()
    }

    @Test
    fun deterministicSilenceProducesNoSpeechAndDoesNotCallSherpa() = runTest {
        val fixture = fixture(ShortArray(960), backgroundScope, testScheduler)
        val controller = controller(fixture)
        controller.start()
        runCurrent()
        fixture.input.readStarted.await()
        controller.stop()
        runCurrent()

        assertEquals(
            SpeechToTextState.Error(SpeechToTextError.RecognitionFailed(RecognitionFailureReason.NO_SPEECH)),
            controller.state.value,
        )
        assertEquals(0, fixture.decoder.decodedSamples)
        assertResourcesReleased(fixture)
        controller.close()
    }

    @Test
    fun recognitionFailureIsHonestAndStillReleasesEveryResource() = runTest {
        val fixture = fixture(ShortArray(1_200) { 10_000 }, backgroundScope, testScheduler, decodeFailure = IllegalStateException("synthetic decode failure"))
        val controller = controller(fixture)
        controller.start()
        runCurrent()
        fixture.input.readStarted.await()
        controller.stop()
        runCurrent()

        assertEquals(
            SpeechToTextState.Error(SpeechToTextError.RecognitionFailed(RecognitionFailureReason.UNKNOWN)),
            controller.state.value,
        )
        assertResourcesReleased(fixture)
        controller.close()
    }

    @Test
    fun cancelDiscardsPartialTranscriptAndClosesPcmVadAndSherpa() = runTest {
        val fixture = fixture(ShortArray(1_600) { 11_000 }, backgroundScope, testScheduler)
        val controller = controller(fixture)
        controller.start()
        runCurrent()
        fixture.input.readStarted.await()
        controller.cancel()

        assertEquals(SpeechToTextState.Cancelled, controller.state.value)
        assertResourcesReleased(fixture)
        controller.close()
    }

    @Test
    fun permissionRevocationCancelsActivePipelineAndReportsPermissionError() = runTest {
        var permission = true
        val fixture = fixture(ShortArray(1_600) { 9_000 }, backgroundScope, testScheduler)
        val controller = controller(fixture) { permission }
        controller.start()
        runCurrent()
        fixture.input.readStarted.await()

        permission = false
        controller.onPermissionRevoked()

        assertEquals(SpeechToTextState.Error(SpeechToTextError.PermissionDenied), controller.state.value)
        assertResourcesReleased(fixture)
        controller.close()
    }

    @Test
    fun lifecycleOwnerStopDiscardsThePendingResultInsteadOfFinalizingIt() = runTest {
        val fixture = fixture(ShortArray(1_600) { 9_000 }, backgroundScope, testScheduler)
        val controller = controller(fixture)
        controller.start()
        runCurrent()
        fixture.input.readStarted.await()

        fixture.markLifecycleStopped()
        fixture.input.stop()
        runCurrent()

        assertEquals(SpeechToTextState.Cancelled, controller.state.value)
        assertEquals(0, fixture.decoder.decodedSamples)
        assertResourcesReleased(fixture)
        controller.close()
    }

    @Test
    fun cancelDuringFinalDecodeDiscardsTheLateTranscript() = runTest {
        val decodeEntered = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        val fixture = fixture(
            pcm = ShortArray(1_600) { 10_000 },
            scope = backgroundScope,
            scheduler = testScheduler,
            workerDispatcher = Dispatchers.IO,
            onDecode = {
                decodeEntered.countDown()
                check(releaseDecode.await(5, TimeUnit.SECONDS)) { "Synthetic decoder gate timed out" }
            },
        )
        val controller = controller(fixture)
        try {
            controller.start()
            fixture.input.readStarted.await()
            controller.stop() // Stops capture and returns while final ASR remains cancellable.
            assertTrue(withContext(Dispatchers.IO) { decodeEntered.await(5, TimeUnit.SECONDS) })
            assertTrue(controller.state.value is SpeechToTextState.Transcribing)

            val cancelJob = backgroundScope.launch { controller.cancel() }
            runCurrent()
            assertTrue("Cancellation should wait for native decode to leave safely", cancelJob.isActive)
            releaseDecode.countDown()
            cancelJob.join()

            assertEquals(SpeechToTextState.Cancelled, controller.state.value)
            assertResourcesReleased(fixture)
        } finally {
            releaseDecode.countDown()
            controller.close()
        }
    }

    @Test
    fun activeSessionRejectsSecondStartAndUnsupportedLanguageNeverOpensMicrophone() = runTest {
        val fixture = fixture(ShortArray(1_600) { 8_000 }, backgroundScope, testScheduler)
        val engine = fixture.engine
        assertEquals(SpeechToTextStartResult.Started, engine.start(SpeechToTextRequest("es-PE")))
        runCurrent()
        fixture.input.readStarted.await()
        assertEquals(SpeechToTextStartResult.SessionAlreadyActive, engine.start(SpeechToTextRequest("es-PE")))
        engine.cancel()
        assertResourcesReleased(fixture)

        val unsupported = fixture(ShortArray(1_600) { 8_000 }, backgroundScope, testScheduler)
        assertEquals(SpeechToTextStartResult.Started, unsupported.engine.start(SpeechToTextRequest("en-US")))
        assertEquals(
            SpeechToTextState.Error(SpeechToTextError.RecognitionFailed(RecognitionFailureReason.LANGUAGE_NOT_SUPPORTED)),
            unsupported.engine.state.value,
        )
        assertEquals(0, unsupported.input.opens)
        assertEquals(0, unsupported.detector.releases)
        assertEquals(0, unsupported.decoder.releases)
    }

    @Test
    fun deniedPermissionAndMissingModelAreTypedWithoutStartingCapture() = runTest {
        val denied = fixture(ShortArray(1_600) { 8_000 }, backgroundScope, testScheduler, permission = false)
        assertEquals(SpeechToTextStartResult.Started, denied.engine.start(SpeechToTextRequest("es-PE")))
        assertEquals(SpeechToTextState.Error(SpeechToTextError.PermissionDenied), denied.engine.state.value)
        assertEquals(0, denied.input.opens)

        val missing = fixture(ShortArray(1_600) { 8_000 }, backgroundScope, testScheduler, recognizerError = java.io.IOException("synthetic missing model"))
        assertEquals(SpeechToTextStartResult.Started, missing.engine.start(SpeechToTextRequest("es-PE")))
        assertEquals(SpeechToTextState.Error(SpeechToTextError.OfflineModelMissing), missing.engine.state.value)
        assertEquals(0, missing.input.opens)
        assertEquals(1, missing.detector.releases)
    }

    private fun controller(fixture: Fixture, permission: () -> Boolean = { true }) =
        VoiceCaptureSessionController(fixture.engine, fixture.scope, permission)

    private fun fixture(
        pcm: ShortArray,
        scope: CoroutineScope,
        scheduler: TestCoroutineScheduler,
        permission: Boolean = true,
        decodeFailure: Exception? = null,
        recognizerError: Throwable? = null,
        workerDispatcher: CoroutineDispatcher? = null,
        onDecode: () -> Unit = {},
        onSegment: ((LocalSpeechDiagnosticSegment) -> Unit)? = null,
        decodedText: String = "nota local sintética",
    ): Fixture {
        val dispatcher = workerDispatcher ?: StandardTestDispatcher(scheduler)
        val input = FakeInput(pcm)
        val detector = CountingDetector()
        val decoder = FakeDecoder(decodeFailure, onDecode, decodedText)
        val vocabulary = vocabulary()
        val recognizer = SherpaOfflineRecognizerAdapter(decoder, vocabulary, dispatcher)
        var lifecycleActive = true
        val engine = SherpaOfflineSpeechToTextEngine(
            workerScope = scope,
            hasRecordAudioPermission = { permission },
            captureFactory = { LocalPcmCaptureLease(input, isOwnerActive = { lifecycleActive }) },
            segmenterFactory = {
                SileroPcmSegmenter(detector, dispatcher, preRollSamples = 128, silenceSamples = 512)
            },
            recognizerFactory = {
                if (recognizerError != null) throw recognizerError
                recognizer
            },
            workerDispatcher = dispatcher,
            onSegmentRecognition = onSegment,
        )
        return Fixture(engine, input, detector, decoder, scope) { lifecycleActive = false }
    }

    private fun assertResourcesReleased(fixture: Fixture) {
        assertEquals(1, fixture.input.opens)
        assertEquals(1, fixture.input.starts)
        assertEquals(1, fixture.input.stops)
        assertEquals(1, fixture.input.closes)
        assertEquals(1, fixture.detector.releases)
        assertEquals(1, fixture.decoder.releases)
    }

    private fun vocabulary(): LocalSpeechVocabulary {
        val pieces = listOf("<unk>", "▁", "n", "o", "t", "a", "l", "c", "s", "i", "é", "<blk>")
        val tokens = pieces.mapIndexed { index, piece -> "$piece $index\n" }.joinToString("")
        val bpe = pieces.joinToString("") { "$it\t-1.0\n" }
        return LocalSpeechVocabulary.fromTexts(tokens, bpe, pieces.size)
    }

    private data class Fixture(
        val engine: SherpaOfflineSpeechToTextEngine,
        val input: FakeInput,
        val detector: CountingDetector,
        val decoder: FakeDecoder,
        val scope: CoroutineScope,
        val markLifecycleStopped: () -> Unit,
    )

    private class FakeInput(pcm: ShortArray) : LocalPcmInput {
        private val pcmSamples = pcm.copyOf()
        private val readSignals = Channel<Unit>(Channel.CONFLATED)
        val readStarted = CompletableDeferred<Unit>()
        var opens = 0
        var starts = 0
        var stops = 0
        var closes = 0
        private var cursor = 0
        @Volatile private var paused = false
        @Volatile private var stopped = false

        override suspend fun open() { opens++ }
        override suspend fun start() { starts++ }
        override suspend fun read(samples: ShortArray): Int {
            if (cursor < pcmSamples.size) {
                val count = minOf(samples.size, pcmSamples.size - cursor)
                pcmSamples.copyInto(samples, 0, cursor, cursor + count)
                cursor += count
                readStarted.complete(Unit)
                return count
            }
            readStarted.complete(Unit)
            while (!paused && !stopped) readSignals.receive()
            return 0
        }
        override suspend fun stop() { stops++; stopped = true; readSignals.trySend(Unit) }
        override suspend fun pause() { paused = true; readSignals.trySend(Unit) }
        override suspend fun resume() { paused = false; readSignals.trySend(Unit) }
        override suspend fun close() { closes++; stopped = true; readSignals.trySend(Unit); pcmSamples.fill(0) }
    }

    private class CountingDetector : SileroPcmSegmenter.FrameDetector {
        var releases = 0
        override fun probability(frame: FloatArray) = if (frame.any { it != 0f }) 0.9f else 0f
        override fun release() { releases++ }
    }

    private class FakeDecoder(
        private val failure: Exception?,
        private val onDecode: () -> Unit,
        private val text: String = "nota local sintética",
    ) : SherpaOfflineRecognizerAdapter.Decoder {
        var decodedSamples = 0
        var releases = 0
        override fun decode(samples: FloatArray, sampleRate: Int, hotwords: String): SherpaOfflineRecognizerAdapter.DecodedText {
            failure?.let { throw it }
            decodedSamples += samples.size
            onDecode()
            return SherpaOfflineRecognizerAdapter.DecodedText(text, emptyList(), emptyList())
        }
        override fun release() { releases++ }
    }
}
