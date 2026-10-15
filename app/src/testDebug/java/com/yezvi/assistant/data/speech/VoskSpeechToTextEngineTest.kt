package com.yezvi.assistant.data.speech

import com.yezvi.assistant.domain.speech.FinalTranscript
import com.yezvi.assistant.domain.speech.PartialTranscript
import com.yezvi.assistant.domain.speech.RecognitionFailureReason
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextStartResult
import com.yezvi.assistant.domain.speech.SpeechToTextState
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PCM/state/resource tests use a deterministic decoder; these do not measure ASR quality. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoskSpeechToTextEngineTest {
    @Test
    fun endpointsAndRevisablePartialsComposeIntoOneFinalAfterFinishAndCleanup() = runTest {
        val fixture = fixture(backgroundScope, testScheduler)
        fixture.engine.start(REQUEST)
        runCurrent()
        assertEquals(SpeechToTextState.Listening(PartialTranscript("nota local")), fixture.engine.state.value)
        assertEquals(0, fixture.decoder.finalCalls)

        fixture.engine.finish()
        runCurrent()

        assertEquals(SpeechToTextState.Result(FinalTranscript("nota local sintética")), fixture.engine.state.value)
        assertEquals(640, fixture.decoder.acceptedSamples)
        assertEquals(1, fixture.decoder.finalCalls)
        assertReleased(fixture)
    }

    @Test
    fun doubleFinishStopsOnceAndRepeatedStartCannotReplaceTheSession() = runTest {
        val fixture = fixture(backgroundScope, testScheduler)
        fixture.engine.start(REQUEST)
        runCurrent()
        val previous = fixture.engine.state.value
        assertEquals(SpeechToTextStartResult.SessionAlreadyActive, fixture.engine.start(REQUEST))
        assertEquals(previous, fixture.engine.state.value)
        fixture.engine.finish()
        fixture.engine.finish()
        runCurrent()

        assertEquals(1, fixture.input.starts)
        assertEquals(1, fixture.input.stops)
        assertEquals(1, fixture.decoder.finalCalls)
        assertReleased(fixture)
    }

    @Test
    fun silenceReportsNoSpeechInsteadOfInventingAResult() = runTest {
        val fixture = fixture(backgroundScope, testScheduler, silence = true)
        fixture.engine.start(REQUEST)
        runCurrent()
        fixture.engine.finish()
        runCurrent()

        assertEquals(error(RecognitionFailureReason.NO_SPEECH), fixture.engine.state.value)
        assertReleased(fixture)
    }

    @Test
    fun cancelDiscardsPartialAndDoesNotFlushFinalResult() = runTest {
        val fixture = fixture(backgroundScope, testScheduler)
        fixture.engine.start(REQUEST)
        runCurrent()
        fixture.engine.cancel()

        assertEquals(SpeechToTextState.Cancelled, fixture.engine.state.value)
        assertEquals(0, fixture.decoder.finalCalls)
        assertReleased(fixture)
    }

    @Test
    fun decodingErrorIsTypedAndStillReleasesCaptureAndDecoder() = runTest {
        val fixture = fixture(backgroundScope, testScheduler)
        fixture.decoder.acceptError = IllegalStateException("Synthetic decoder failure")
        fixture.engine.start(REQUEST)
        runCurrent()

        assertEquals(error(RecognitionFailureReason.UNKNOWN), fixture.engine.state.value)
        assertReleased(fixture)
    }

    @Test
    fun deniedPermissionDoesNotLoadModelOrOpenCapture() = runTest {
        val fixture = fixture(backgroundScope, testScheduler)
        fixture.permission = false
        fixture.engine.start(REQUEST)

        assertEquals(SpeechToTextState.Error(SpeechToTextError.PermissionDenied), fixture.engine.state.value)
        assertEquals(0, fixture.recognizerCreations)
        assertEquals(0, fixture.input.opens)
    }

    @Test
    fun revokedPermissionAtStopDiscardsTextAndClosesEveryResource() = runTest {
        val fixture = fixture(backgroundScope, testScheduler)
        fixture.engine.start(REQUEST)
        runCurrent()
        fixture.permission = false
        fixture.engine.finish()
        runCurrent()

        assertEquals(SpeechToTextState.Error(SpeechToTextError.PermissionDenied), fixture.engine.state.value)
        assertEquals(0, fixture.decoder.finalCalls)
        assertReleased(fixture)
    }

    @Test
    fun lifecycleLossDoesNotConvertInterruptedAudioIntoAnAcceptedTranscript() = runTest {
        val fixture = fixture(backgroundScope, testScheduler)
        fixture.engine.start(REQUEST)
        runCurrent()
        fixture.ownerActive = false
        fixture.input.stop()
        runCurrent()

        assertEquals(SpeechToTextState.Cancelled, fixture.engine.state.value)
        assertEquals(0, fixture.decoder.finalCalls)
        assertReleased(fixture)
    }

    @Test
    fun captureSetupFailureClosesTheAlreadyLoadedRecognizerAndPartialCapture() = runTest {
        val fixture = fixture(backgroundScope, testScheduler)
        fixture.input.openError = PcmCaptureException(PcmCaptureFailure.INITIALIZATION_FAILED)
        fixture.engine.start(REQUEST)

        assertEquals(error(RecognitionFailureReason.AUDIO_CAPTURE), fixture.engine.state.value)
        assertEquals(0, fixture.input.starts)
        assertReleased(fixture)
    }

    @Test
    fun missingModelNeverOpensCaptureAndUnsupportedLanguageDoesNotLoadModel() = runTest {
        val missing = fixture(backgroundScope, testScheduler)
        missing.modelError = IOException("Synthetic absent model")
        missing.engine.start(REQUEST)
        assertEquals(SpeechToTextState.Error(SpeechToTextError.OfflineModelMissing), missing.engine.state.value)
        assertEquals(0, missing.input.opens)

        val unsupported = fixture(backgroundScope, testScheduler)
        unsupported.engine.start(SpeechToTextRequest("en-US"))
        assertEquals(error(RecognitionFailureReason.LANGUAGE_NOT_SUPPORTED), unsupported.engine.state.value)
        assertEquals(0, unsupported.recognizerCreations)
    }

    @Test
    fun startCancellationPropagatesAndCleansPartiallyPreparedResources() = runTest {
        val fixture = fixture(backgroundScope, testScheduler)
        val openGate = CompletableDeferred<Unit>()
        fixture.input.openGate = openGate
        val starting = backgroundScope.async { fixture.engine.start(REQUEST) }
        runCurrent()
        assertEquals(1, fixture.input.opens)
        starting.cancel()
        runCurrent()
        starting.join()

        assertTrue(starting.isCancelled)
        assertEquals(SpeechToTextState.Cancelled, fixture.engine.state.value)
        assertReleased(fixture)
    }

    @Test
    fun cancellingBlockedNativeFinalWaitsForItAndNeverPublishesItsLateText() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = fixture(backgroundScope, testScheduler, dispatcher = Dispatchers.IO)
        fixture.decoder.onFinal = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Synthetic final gate timed out" }
        }
        try {
            fixture.engine.start(REQUEST)
            fixture.input.waitingForStop.await()
            fixture.engine.finish()
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertTrue(fixture.engine.state.value is SpeechToTextState.Transcribing)
            val cancelJob = backgroundScope.launch { fixture.engine.cancel() }
            runCurrent()
            assertTrue("Native handles must remain alive until inference returns", cancelJob.isActive)
            assertEquals(0, fixture.decoder.closes)
            release.countDown()
            cancelJob.join()

            assertEquals(SpeechToTextState.Cancelled, fixture.engine.state.value)
            assertReleased(fixture)
        } finally {
            release.countDown()
            fixture.engine.cancel()
        }
    }

    @Test
    fun decoderCleanupFailurePreventsAFalseSuccess() = runTest {
        val fixture = fixture(backgroundScope, testScheduler)
        fixture.decoder.closeError = IllegalStateException("Synthetic release failure")
        fixture.engine.start(REQUEST)
        runCurrent()
        fixture.engine.finish()
        runCurrent()

        assertEquals(error(RecognitionFailureReason.AUDIO_CAPTURE), fixture.engine.state.value)
        assertReleased(fixture)
    }

    @Test
    fun streamFinishAndCloseAreIdempotentAndPreserveEndpointOrder() = runTest {
        val decoder = FakeDecoder(false)
        val stream = VoskStreamingRecognizer(decoder, StandardTestDispatcher(testScheduler))
        assertEquals("nota", stream.accept(ShortArray(320), 320))
        assertEquals("nota local", stream.accept(ShortArray(320), 320))
        assertEquals("nota local sintética", stream.finish())
        assertEquals("nota local sintética", stream.finish())
        stream.close()
        stream.close()

        assertEquals(1, decoder.finalCalls)
        assertEquals(1, decoder.closes)
    }

    private fun fixture(
        scope: CoroutineScope,
        scheduler: TestCoroutineScheduler,
        silence: Boolean = false,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler),
    ): Fixture = Fixture(scope, dispatcher, silence)

    private class Fixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher, silence: Boolean) {
        val input = FakeInput()
        val decoder = FakeDecoder(silence)
        var permission = true
        var ownerActive = true
        var modelError: Exception? = null
        var recognizerCreations = 0
        val engine = VoskSpeechToTextEngine(
            workerScope = scope,
            hasRecordAudioPermission = { permission },
            captureFactory = { LocalPcmCaptureLease(input, isOwnerActive = { ownerActive }) },
            recognizerFactory = {
                recognizerCreations++
                modelError?.let { throw it }
                VoskStreamingRecognizer(decoder, dispatcher)
            },
            workerDispatcher = dispatcher,
        )
    }

    private class FakeInput : LocalPcmInput {
        var opens = 0
        var starts = 0
        var stops = 0
        var closes = 0
        var openError: Exception? = null
        var openGate: CompletableDeferred<Unit>? = null
        val waitingForStop = CompletableDeferred<Unit>()
        private val stopped = CompletableDeferred<Unit>()
        private var blocks = 0
        override suspend fun open() { opens++; openError?.let { throw it }; openGate?.await() }
        override suspend fun start() { starts++ }
        override suspend fun read(samples: ShortArray): Int {
            if (blocks++ < 2) { samples.fill(2_000); return samples.size }
            waitingForStop.complete(Unit)
            stopped.await()
            return 0
        }
        override suspend fun stop() { stops++; stopped.complete(Unit) }
        override suspend fun close() { closes++; stopped.complete(Unit) }
    }

    private class FakeDecoder(private val silence: Boolean) : VoskStreamingRecognizer.Decoder {
        var acceptedSamples = 0
        var finalCalls = 0
        var closes = 0
        var acceptError: Exception? = null
        var closeError: Exception? = null
        var onFinal: () -> Unit = {}
        override fun accept(samples: ShortArray, length: Int): VoskStreamingRecognizer.Chunk {
            acceptError?.let { throw it }
            acceptedSamples += length
            val endpoint = acceptedSamples == 320
            return VoskStreamingRecognizer.Chunk(if (silence) "" else if (endpoint) "nota" else "local", endpoint)
        }
        override fun finish(): String {
            finalCalls++
            onFinal()
            return if (silence) "" else "local sintética"
        }
        override fun close() { closes++; closeError?.let { throw it } }
    }

    private fun assertReleased(fixture: Fixture) {
        assertEquals(1, fixture.input.closes)
        assertEquals(1, fixture.decoder.closes)
    }

    private fun error(reason: RecognitionFailureReason) = SpeechToTextState.Error(SpeechToTextError.RecognitionFailed(reason))

    companion object { private val REQUEST = SpeechToTextRequest("es-PE") }
}
