package com.yezvi.assistant.presentation.capture

import com.yezvi.assistant.domain.speech.FakeSpeechToTextEngine
import com.yezvi.assistant.domain.speech.RecognitionFailureReason
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextCapabilities
import com.yezvi.assistant.domain.speech.SpeechToTextState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCaptureSessionControllerTest {
    @Test
    fun pauseAndResumeKeepOneRecognitionSessionAndStopStillFinalizesIt() = runTest {
        val engine = FakeSpeechToTextEngine(
            capabilities = SpeechToTextCapabilities(
                supportsPartialResults = true,
                supportedLanguageTags = setOf("es-PE"),
                supportsPause = true,
            ),
        )
        val controller = controller(engine, backgroundScope)

        controller.start()
        runCurrent()
        engine.beginListening()
        runCurrent()

        assertTrue(controller.supportsPause)
        assertTrue(controller.pause())
        assertTrue(controller.state.value is SpeechToTextState.Paused)
        assertEquals(1, engine.pauseCallCount)
        assertTrue(engine.isSessionActive)

        assertTrue(controller.resume())
        assertTrue(controller.state.value is SpeechToTextState.Listening)
        assertEquals(1, engine.resumeCallCount)
        assertTrue(engine.isSessionActive)

        controller.stop()
        runCurrent()
        assertTrue(controller.state.value is SpeechToTextState.Result)
        assertEquals(1, engine.startCallCount)
        assertEquals(1, engine.finishCallCount)
        controller.close()
    }

    @Test
    fun permissionRevokedWhilePausedCannotResumeAndCancelsSession() = runTest {
        var permission = true
        val engine = FakeSpeechToTextEngine(
            capabilities = SpeechToTextCapabilities(
                supportsPartialResults = true,
                supportsPause = true,
            ),
        )
        val controller = controller(engine, backgroundScope) { permission }
        controller.start()
        runCurrent()
        engine.beginListening()
        runCurrent()
        assertTrue(controller.pause())

        permission = false
        assertFalse(controller.resume())
        assertEquals(SpeechToTextState.Error(SpeechToTextError.PermissionDenied), controller.state.value)
        assertFalse(engine.isSessionActive)
        assertEquals(1, engine.cancelCallCount)
        controller.close()
    }

    @Test
    fun permissionIsCheckedBeforeStartAndAgainAfterEnginePreparation() = runTest {
        var permissionGranted = false
        val gate = CompletableDeferred<Unit>()
        val engine = FakeSpeechToTextEngine(startGate = gate)
        val controller = controller(engine, backgroundScope) { permissionGranted }

        controller.start()
        assertEquals(SpeechToTextState.Error(SpeechToTextError.PermissionDenied), controller.state.value)
        assertEquals(0, engine.startCallCount)

        permissionGranted = true
        val startJob = backgroundScope.launch { controller.start() }
        runCurrent()
        assertEquals(SpeechToTextState.Preparing, controller.state.value)
        assertEquals(1, engine.startCallCount)

        permissionGranted = false
        gate.complete(Unit)
        runCurrent()
        startJob.join()

        assertEquals(SpeechToTextState.Error(SpeechToTextError.PermissionDenied), controller.state.value)
        assertEquals(1, engine.cancelCallCount)
        assertFalse(engine.isSessionActive)
        controller.close()
    }

    @Test
    fun engineFailureAfterPermissionRevocationIsReportedAsPermissionDenied() = runTest {
        var permissionGranted = true
        val gate = CompletableDeferred<Unit>()
        val engine = FakeSpeechToTextEngine(
            startGate = gate,
            startException = SecurityException("synthetic permission revocation"),
        )
        val controller = controller(engine, backgroundScope) { permissionGranted }
        val startJob = backgroundScope.launch { controller.start() }
        runCurrent()

        permissionGranted = false
        gate.complete(Unit)
        runCurrent()
        startJob.join()

        assertEquals(SpeechToTextState.Error(SpeechToTextError.PermissionDenied), controller.state.value)
        assertEquals(1, engine.cancelCallCount)
        assertFalse(engine.isSessionActive)
        controller.close()
    }

    @Test
    fun serializesRepeatedStartsAndStopsAndPublishesRealEngineTransitions() = runTest {
        val finishGate = CompletableDeferred<Unit>()
        val engine = FakeSpeechToTextEngine(finishGate = finishGate)
        val controller = controller(engine, backgroundScope)

        controller.start()
        runCurrent()
        controller.start()
        assertEquals(1, engine.startCallCount)

        engine.beginListening()
        engine.emitPartial("Reunión mañana")
        runCurrent()
        assertEquals(
            SpeechToTextState.Listening(
                com.yezvi.assistant.domain.speech.PartialTranscript("Reunión mañana"),
            ),
            controller.state.value,
        )

        val firstStop = backgroundScope.launch { controller.stop() }
        runCurrent()
        assertTrue(controller.state.value is SpeechToTextState.Transcribing)
        val repeatedStop = backgroundScope.launch { controller.stop() }
        runCurrent()
        assertEquals(1, engine.finishCallCount)

        finishGate.complete(Unit)
        runCurrent()
        firstStop.join()
        repeatedStop.join()

        assertTrue(controller.state.value is SpeechToTextState.Result)
        assertEquals(1, engine.finishCallCount)
        assertEquals(0, engine.activeStateCollectors)
        controller.close()
    }

    @Test
    fun cancellationAndLeavingCaptureReleaseTheSessionAndStateCollector() = runTest {
        val engine = FakeSpeechToTextEngine()
        val controller = controller(engine, backgroundScope)

        controller.start()
        runCurrent()
        engine.beginListening()
        runCurrent()
        assertEquals(1, engine.activeStateCollectors)

        controller.cancel()
        runCurrent()
        assertEquals(SpeechToTextState.Cancelled, controller.state.value)
        assertFalse(engine.isSessionActive)
        assertEquals(1, engine.cancelCallCount)
        assertEquals(0, engine.activeStateCollectors)

        controller.start()
        runCurrent()
        assertEquals(2, engine.startCallCount)
        assertEquals(1, engine.activeStateCollectors)
        controller.close()
        runCurrent()

        assertEquals(SpeechToTextState.Cancelled, controller.state.value)
        assertFalse(engine.isSessionActive)
        assertEquals(2, engine.cancelCallCount)
        assertEquals(0, engine.activeStateCollectors)

        controller.start()
        assertEquals(2, engine.startCallCount)
    }

    @Test
    fun revocationCancelsTheActiveSessionAndReportsPermissionError() = runTest {
        var permissionGranted = true
        val engine = FakeSpeechToTextEngine()
        val controller = controller(engine, backgroundScope) { permissionGranted }

        controller.start()
        runCurrent()
        engine.beginListening()
        runCurrent()
        permissionGranted = false
        controller.onPermissionRevoked()
        runCurrent()

        assertEquals(SpeechToTextState.Error(SpeechToTextError.PermissionDenied), controller.state.value)
        assertEquals(1, engine.cancelCallCount)
        assertFalse(engine.isSessionActive)

        controller.onPermissionRevoked()
        assertEquals(1, engine.cancelCallCount)
        controller.close()
    }

    @Test
    fun engineBusyAndFailuresAreHonestAndDoNotClaimSuccessfulCapture() = runTest {
        val busyEngine = FakeSpeechToTextEngine()
        busyEngine.start(com.yezvi.assistant.domain.speech.SpeechToTextRequest("es-PE"))
        val busyController = controller(busyEngine, backgroundScope)
        busyController.start()
        assertEquals(
            SpeechToTextState.Error(
                SpeechToTextError.RecognitionFailed(RecognitionFailureReason.BUSY),
            ),
            busyController.state.value,
        )
        assertEquals(0, busyEngine.cancelCallCount)
        assertTrue(busyEngine.isSessionActive)
        busyEngine.cancel()
        busyController.close()

        val startFailureEngine = FakeSpeechToTextEngine(
            startException = IllegalStateException("synthetic startup failure"),
        )
        val startFailureController = controller(startFailureEngine, backgroundScope)
        startFailureController.start()
        assertEquals(
            SpeechToTextState.Error(
                SpeechToTextError.RecognitionFailed(RecognitionFailureReason.UNKNOWN),
            ),
            startFailureController.state.value,
        )
        assertFalse(startFailureEngine.isSessionActive)
        assertEquals(1, startFailureEngine.cancelCallCount)
        startFailureController.close()

        val finishFailureEngine = FakeSpeechToTextEngine(
            finishException = IllegalStateException("synthetic finish failure"),
        )
        val finishFailureController = controller(finishFailureEngine, backgroundScope)
        finishFailureController.start()
        runCurrent()
        finishFailureEngine.beginListening()
        runCurrent()
        finishFailureController.stop()
        assertEquals(
            SpeechToTextState.Error(
                SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE),
            ),
            finishFailureController.state.value,
        )
        assertFalse(finishFailureEngine.isSessionActive)
        assertEquals(1, finishFailureEngine.cancelCallCount)
        finishFailureController.close()
    }

    @Test
    fun cleanupFailureStaysAnErrorAndCloseCanRetryCleanup() = runTest {
        val engine = FakeSpeechToTextEngine(
            cancelException = IllegalStateException("synthetic cleanup failure"),
        )
        val controller = controller(engine, backgroundScope)
        controller.start()
        runCurrent()

        controller.cancel()
        assertEquals(
            SpeechToTextState.Error(
                SpeechToTextError.RecognitionFailed(RecognitionFailureReason.AUDIO_CAPTURE),
            ),
            controller.state.value,
        )
        assertTrue(engine.isSessionActive)
        assertEquals(1, engine.cancelCallCount)

        controller.close()
        runCurrent()
        assertEquals(SpeechToTextState.Cancelled, controller.state.value)
        assertFalse(engine.isSessionActive)
        assertEquals(2, engine.cancelCallCount)
    }

    @Test
    fun cancelledStartStillCleansUpAndPropagatesCoroutineCancellation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeSpeechToTextEngine(startGate = gate)
        val controller = controller(engine, backgroundScope)
        val startJob = backgroundScope.launch { controller.start() }
        runCurrent()

        startJob.cancel()
        runCurrent()
        startJob.join()

        assertTrue(startJob.isCancelled)
        assertEquals(SpeechToTextState.Cancelled, controller.state.value)
        assertEquals(1, engine.cancelCallCount)
        assertFalse(engine.isSessionActive)
        controller.close()
    }

    @Test
    fun cancelledCleanupCallStillReleasesEngineAndPropagatesCancellation() = runTest {
        val cancelGate = CompletableDeferred<Unit>()
        val engine = FakeSpeechToTextEngine(cancelGate = cancelGate)
        val controller = controller(engine, backgroundScope)
        controller.start()
        runCurrent()

        val cancelJob = backgroundScope.launch { controller.cancel() }
        runCurrent()
        assertEquals(1, engine.cancelCallCount)

        cancelJob.cancel()
        cancelGate.complete(Unit)
        runCurrent()
        cancelJob.join()

        assertTrue(cancelJob.isCancelled)
        assertEquals(SpeechToTextState.Cancelled, controller.state.value)
        assertEquals(2, engine.cancelCallCount)
        assertFalse(engine.isSessionActive)
        assertEquals(0, engine.activeStateCollectors)
        controller.close()
    }

    @Test
    fun leavingCaptureCompletesCleanupEvenWhenTheOwnerScopeIsCancelled() = runTest {
        val cancelGate = CompletableDeferred<Unit>()
        val engine = FakeSpeechToTextEngine(cancelGate = cancelGate)
        val controller = controller(engine, backgroundScope)
        controller.start()
        runCurrent()

        val closeJob = backgroundScope.launch { controller.close() }
        runCurrent()
        closeJob.cancel()
        cancelGate.complete(Unit)
        runCurrent()
        closeJob.join()

        assertTrue(closeJob.isCancelled)
        assertEquals(SpeechToTextState.Cancelled, controller.state.value)
        assertFalse(engine.isSessionActive)
        assertEquals(1, engine.cancelCallCount)
        assertEquals(0, engine.activeStateCollectors)
    }

    private fun controller(
        engine: FakeSpeechToTextEngine,
        scope: CoroutineScope,
        hasPermission: () -> Boolean = { true },
    ) = VoiceCaptureSessionController(
        engine = engine,
        scope = scope,
        hasRecordAudioPermission = hasPermission,
    )
}
