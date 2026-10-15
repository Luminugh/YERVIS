package com.yezvi.assistant.domain.speech

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechToTextEngineContractTest {
    @Test
    fun emitsPreparationListeningPartialTranscribingAndFinalResult() = runTest {
        val engine = FakeSpeechToTextEngine()
        val states = collectStates(engine)

        assertEquals(
            SpeechToTextStartResult.Started,
            engine.start(SpeechToTextRequest("es-PE")),
        )
        engine.beginListening()
        engine.emitPartial("Reunión el jueves")
        engine.finish()

        assertEquals(
            listOf(
                SpeechToTextState.Idle,
                SpeechToTextState.Preparing,
                SpeechToTextState.Listening(),
                SpeechToTextState.Listening(PartialTranscript("Reunión el jueves")),
                SpeechToTextState.Transcribing(PartialTranscript("Reunión el jueves")),
                SpeechToTextState.Result(FinalTranscript("Nota de voz sintética")),
            ),
            List(6) { states.receive() },
        )
    }

    @Test
    fun cancellationDiscardsDraftAndAllowsAnotherSession() = runTest {
        val engine = FakeSpeechToTextEngine()

        engine.start(SpeechToTextRequest("es-PE"))
        engine.beginListening()
        engine.emitPartial("Borrador que se descartará")
        engine.cancel()

        assertEquals(SpeechToTextState.Cancelled, engine.state.value)
        assertEquals(
            SpeechToTextStartResult.Started,
            engine.start(SpeechToTextRequest("es-PE")),
        )
        assertEquals(SpeechToTextState.Preparing, engine.state.value)
    }

    @Test
    fun reportsDistinctAvailabilityModelPermissionAndRecognitionErrors() = runTest {
        val errors = listOf(
            SpeechToTextError.EngineUnavailable,
            SpeechToTextError.OfflineModelMissing,
            SpeechToTextError.PermissionDenied,
            SpeechToTextError.RecognitionFailed(RecognitionFailureReason.NO_SPEECH),
            SpeechToTextError.RecognitionFailed(RecognitionFailureReason.LANGUAGE_UNAVAILABLE),
        )

        errors.forEach { error ->
            val engine = FakeSpeechToTextEngine(startError = error)
            assertEquals(
                SpeechToTextStartResult.Started,
                engine.start(SpeechToTextRequest("es-PE")),
            )
            assertEquals(SpeechToTextState.Error(error), engine.state.value)
            assertEquals(
                SpeechToTextStartResult.Started,
                engine.start(SpeechToTextRequest("es-PE")),
            )
        }
    }

    @Test
    fun secondStartDoesNotReplaceAnActiveSession() = runTest {
        val engine = FakeSpeechToTextEngine()
        engine.start(SpeechToTextRequest("es-PE"))
        engine.beginListening()
        val activeState = engine.state.value

        assertEquals(
            SpeechToTextStartResult.SessionAlreadyActive,
            engine.start(SpeechToTextRequest("en-US")),
        )
        assertEquals(activeState, engine.state.value)
    }

    @Test
    fun engineWithoutPartialSupportOnlyPublishesFinalTranscript() = runTest {
        val engine = FakeSpeechToTextEngine(
            capabilities = SpeechToTextCapabilities(supportsPartialResults = false),
        )

        engine.start(SpeechToTextRequest("es-PE"))
        engine.beginListening()
        engine.emitPartial("No debe aparecer")
        assertTrue(engine.state.value is SpeechToTextState.Listening)
        engine.finish()

        assertEquals(
            SpeechToTextState.Result(FinalTranscript("Nota de voz sintética")),
            engine.state.value,
        )
    }

    private fun kotlinx.coroutines.test.TestScope.collectStates(
        engine: SpeechToTextEngine,
    ): Channel<SpeechToTextState> {
        val collected = Channel<SpeechToTextState>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.state.collect { collected.send(it) }
        }
        return collected
    }
}
