package com.yezvi.assistant.data.speech

import com.yezvi.assistant.domain.speech.FinalTranscript
import com.yezvi.assistant.domain.speech.RecognitionFailureReason
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextStartResult
import com.yezvi.assistant.domain.speech.SpeechToTextState
import com.yezvi.assistant.presentation.capture.VoiceCaptureSessionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidOnDeviceSpeechToTextEngineTest {
    @Test
    fun api24To30DoesNotCreateAnyRecognizerOrUseTheGenericPath() = runTest {
        val factory = FakeOnDeviceSpeechRecognizerFactory(apiLevel = 30)
        val engine = engine(factory)

        assertEquals(SpeechToTextStartResult.Started, engine.start(SpeechToTextRequest("es-PE")))

        assertEquals(SpeechToTextState.Error(SpeechToTextError.EngineUnavailable), engine.state.value)
        assertEquals(0, factory.availabilityChecks)
        assertEquals(0, factory.recognizers.size)
    }

    @Test
    fun permissionIsCheckedBeforeAvailabilityAndCreation() = runTest {
        val factory = FakeOnDeviceSpeechRecognizerFactory(permissionGranted = false)
        val engine = engine(factory)

        engine.start(SpeechToTextRequest("es-PE"))

        assertEquals(SpeechToTextState.Error(SpeechToTextError.PermissionDenied), engine.state.value)
        assertEquals(0, factory.availabilityChecks)
        assertEquals(0, factory.recognizers.size)
    }

    @Test
    fun unavailableOnDeviceServiceNeverCreatesRecognizer() = runTest {
        val factory = FakeOnDeviceSpeechRecognizerFactory(onDeviceAvailable = false)
        val engine = engine(factory)

        engine.start(SpeechToTextRequest("es-PE"))

        assertEquals(SpeechToTextState.Error(SpeechToTextError.EngineUnavailable), engine.state.value)
        assertEquals(1, factory.availabilityChecks)
        assertEquals(0, factory.recognizers.size)
    }

    @Test
    fun serviceDisappearingBetweenAvailabilityCheckAndCreationStaysEngineUnavailable() = runTest {
        val factory = FakeOnDeviceSpeechRecognizerFactory(
            createException = UnsupportedOperationException("synthetic service race"),
        )
        val engine = engine(factory)

        engine.start(SpeechToTextRequest("es-PE"))

        assertEquals(SpeechToTextState.Error(SpeechToTextError.EngineUnavailable), engine.state.value)
        assertEquals(1, factory.availabilityChecks)
        assertEquals(0, factory.recognizers.size)
    }

    @Test
    fun api31RecognitionStreamsPartialAndFinalResultAndStopWaitsForFinalCallback() = runTest {
        val factory = FakeOnDeviceSpeechRecognizerFactory(apiLevel = 31)
        val engine = engine(factory)

        engine.start(SpeechToTextRequest("es-PE"))
        val recognizer = factory.onlyRecognizer()
        assertEquals(0, recognizer.languageChecks)
        assertEquals(1, recognizer.startCalls)

        recognizer.callbackListener.onReadyForSpeech()
        recognizer.callbackListener.onPartialResult("Reunión mañana")
        assertEquals(
            SpeechToTextState.Listening(
                com.yezvi.assistant.domain.speech.PartialTranscript("Reunión mañana"),
            ),
            engine.state.value,
        )

        engine.finish()
        assertEquals(1, recognizer.stopCalls)
        assertEquals(
            SpeechToTextState.Transcribing(
                com.yezvi.assistant.domain.speech.PartialTranscript("Reunión mañana"),
            ),
            engine.state.value,
        )
        assertEquals(0, recognizer.cancelCalls)
        assertEquals(0, recognizer.destroyCalls)

        recognizer.callbackListener.onFinalResult("Reunión mañana a las nueve")
        assertEquals(
            SpeechToTextState.Result(FinalTranscript("Reunión mañana a las nueve")),
            engine.state.value,
        )
        assertEquals(1, recognizer.destroyCalls)
    }

    @Test
    fun api33MissingSpanishModelAndUnsupportedLanguageAreDistinctAndCleanedUp() = runTest {
        val missingFactory = FakeOnDeviceSpeechRecognizerFactory(
            languageSupport = OnDeviceLanguageSupport.ModelMissing,
        )
        val missingEngine = engine(missingFactory)
        missingEngine.start(SpeechToTextRequest("es-PE"))
        assertEquals(
            SpeechToTextState.Error(SpeechToTextError.OfflineModelMissing),
            missingEngine.state.value,
        )
        assertEquals(0, missingFactory.onlyRecognizer().startCalls)
        assertEquals(1, missingFactory.onlyRecognizer().destroyCalls)

        val unsupportedFactory = FakeOnDeviceSpeechRecognizerFactory(
            languageSupport = OnDeviceLanguageSupport.Unsupported,
        )
        val unsupportedEngine = engine(unsupportedFactory)
        unsupportedEngine.start(SpeechToTextRequest("es-PE"))
        assertEquals(
            SpeechToTextState.Error(
                SpeechToTextError.RecognitionFailed(RecognitionFailureReason.LANGUAGE_NOT_SUPPORTED),
            ),
            unsupportedEngine.state.value,
        )
        assertEquals(1, unsupportedFactory.onlyRecognizer().destroyCalls)
    }

    @Test
    fun nonSpanishRequestsAreRejectedBeforeCreatingAnAndroidRecognizer() = runTest {
        val factory = FakeOnDeviceSpeechRecognizerFactory()
        val engine = engine(factory)

        engine.start(SpeechToTextRequest("en-US"))

        assertEquals(
            SpeechToTextState.Error(
                SpeechToTextError.RecognitionFailed(RecognitionFailureReason.LANGUAGE_NOT_SUPPORTED),
            ),
            engine.state.value,
        )
        assertEquals(0, factory.recognizers.size)
    }

    @Test
    fun cancellationDiscardsTranscriptAndIgnoresLateCallbacksAcrossSessions() = runTest {
        val factory = FakeOnDeviceSpeechRecognizerFactory()
        val engine = engine(factory)
        engine.start(SpeechToTextRequest("es-PE"))
        val oldRecognizer = factory.onlyRecognizer()
        oldRecognizer.callbackListener.onPartialResult("Borrador que se descarta")

        assertEquals(
            SpeechToTextStartResult.SessionAlreadyActive,
            engine.start(SpeechToTextRequest("es-PE")),
        )
        engine.cancel()
        assertEquals(SpeechToTextState.Cancelled, engine.state.value)
        assertEquals(1, oldRecognizer.cancelCalls)
        assertEquals(1, oldRecognizer.destroyCalls)

        engine.start(SpeechToTextRequest("es-PE"))
        oldRecognizer.callbackListener.onFinalResult("Resultado tardío")
        assertEquals(SpeechToTextState.Preparing, engine.state.value)
        assertEquals(2, factory.recognizers.size)

        factory.recognizers.last().callbackListener.onReadyForSpeech()
        assertTrue(engine.state.value is SpeechToTextState.Listening)
        engine.cancel()
        assertEquals(1, factory.recognizers.last().destroyCalls)
    }

    @Test
    fun permissionRevocationDuringSessionStopsAndDestroysRecognizer() = runTest {
        val factory = FakeOnDeviceSpeechRecognizerFactory()
        val engine = engine(factory)
        engine.start(SpeechToTextRequest("es-PE"))
        val recognizer = factory.onlyRecognizer()

        factory.permissionGranted = false
        recognizer.callbackListener.onPartialResult("No debe publicarse")

        assertEquals(SpeechToTextState.Error(SpeechToTextError.PermissionDenied), engine.state.value)
        assertEquals(1, recognizer.cancelCalls)
        assertEquals(1, recognizer.destroyCalls)
    }

    @Test
    fun permissionErrorDuringLanguageCheckRemainsDistinct() = runTest {
        val factory = FakeOnDeviceSpeechRecognizerFactory(
            languageSupport = OnDeviceLanguageSupport.PermissionDenied,
        )
        val engine = engine(factory)

        engine.start(SpeechToTextRequest("es-PE"))

        assertEquals(SpeechToTextState.Error(SpeechToTextError.PermissionDenied), engine.state.value)
        assertEquals(0, factory.onlyRecognizer().startCalls)
        assertEquals(1, factory.onlyRecognizer().destroyCalls)
    }

    @Test
    fun f305ControllerRevocationCancelsRealEngineSession() = runTest {
        val factory = FakeOnDeviceSpeechRecognizerFactory()
        val engine = engine(factory)
        var permissionGranted = true
        val controller = VoiceCaptureSessionController(
            engine = engine,
            scope = backgroundScope,
            hasRecordAudioPermission = { permissionGranted },
        )

        controller.start()
        runCurrent()
        val recognizer = factory.onlyRecognizer()
        permissionGranted = false
        controller.onPermissionRevoked()

        assertEquals(
            SpeechToTextState.Error(SpeechToTextError.PermissionDenied),
            controller.state.value,
        )
        assertEquals(1, recognizer.cancelCalls)
        assertEquals(1, recognizer.destroyCalls)
        assertFalse(engine.state.value is SpeechToTextState.Result)
        controller.close()
    }

    @Test
    fun recognitionErrorsAreTypedAndAlwaysReleaseTheRecognizer() = runTest {
        val factory = FakeOnDeviceSpeechRecognizerFactory()
        val engine = engine(factory)
        engine.start(SpeechToTextRequest("es-PE"))
        val recognizer = factory.onlyRecognizer()

        recognizer.callbackListener.onError(
            SpeechRecognizerFailure.RecognitionFailed(RecognitionFailureReason.NO_SPEECH),
        )

        assertEquals(
            SpeechToTextState.Error(
                SpeechToTextError.RecognitionFailed(RecognitionFailureReason.NO_SPEECH),
            ),
            engine.state.value,
        )
        assertEquals(1, recognizer.destroyCalls)
        assertEquals(0, recognizer.cancelCalls)
    }

    private fun kotlinx.coroutines.test.TestScope.engine(
        factory: FakeOnDeviceSpeechRecognizerFactory,
    ) = AndroidOnDeviceSpeechToTextEngine(
        factory = factory,
        mainDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun FakeOnDeviceSpeechRecognizerFactory.onlyRecognizer(): FakeOnDeviceSpeechRecognizer =
        recognizers.single()
}

private class FakeOnDeviceSpeechRecognizerFactory(
    override val apiLevel: Int = 35,
    var permissionGranted: Boolean = true,
    private val onDeviceAvailable: Boolean = true,
    private val languageSupport: OnDeviceLanguageSupport = OnDeviceLanguageSupport.Installed,
    private val createException: Exception? = null,
) : OnDeviceSpeechRecognizerFactory {
    var availabilityChecks = 0
        private set
    val recognizers = mutableListOf<FakeOnDeviceSpeechRecognizer>()

    override fun hasRecordAudioPermission(): Boolean = permissionGranted

    override fun isOnDeviceRecognitionAvailable(): Boolean {
        availabilityChecks += 1
        return onDeviceAvailable
    }

    override fun createOnDeviceRecognizer(): OnDeviceSpeechRecognizer {
        createException?.let { throw it }
        return FakeOnDeviceSpeechRecognizer(languageSupport).also(recognizers::add)
    }
}

private class FakeOnDeviceSpeechRecognizer(
    private val languageSupport: OnDeviceLanguageSupport,
) : OnDeviceSpeechRecognizer {
    lateinit var callbackListener: OnDeviceSpeechRecognizerListener
    var languageChecks = 0
        private set
    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    var cancelCalls = 0
        private set
    var destroyCalls = 0
        private set

    override fun setListener(listener: OnDeviceSpeechRecognizerListener) {
        callbackListener = listener
    }

    override fun checkLanguageSupport(
        languageTag: String,
        callback: (OnDeviceLanguageSupport) -> Unit,
    ) {
        languageChecks += 1
        callback(languageSupport)
    }

    override fun startListening(languageTag: String) {
        startCalls += 1
    }

    override fun stopListening() {
        stopCalls += 1
    }

    override fun cancel() {
        cancelCalls += 1
    }

    override fun destroy() {
        destroyCalls += 1
    }
}
