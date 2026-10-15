package com.yezvi.assistant.data.speech

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.domain.speech.RecognitionFailureReason
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextState
import com.yezvi.assistant.presentation.capture.VoiceCaptureSessionController
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Vosk/JNA over licensed memory PCM. This does not open or validate a microphone. */
@RunWith(AndroidJUnit4::class)
class VoskOfflineRuntimeTest {
    @Test
    fun humanSpanishFixtureFlowsThroughRealVoskAndControllerOffline() = runBlocking(Dispatchers.Default) {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        assertOffline(appContext)
        val result = recognize(appContext, readFixture(testContext))
        assertTrue("Expected a final local Vosk result", result is SpeechToTextState.Result)
        assertTrue("Vosk returned an empty local transcript", (result as SpeechToTextState.Result).transcript.text.isNotBlank())
        assertOffline(appContext)
    }

    @Test
    fun controlledPcmSilenceReturnsNoSpeechAndClosesResourcesOffline() = runBlocking(Dispatchers.Default) {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        assertOffline(appContext)
        val result = recognize(appContext, ShortArray(VoskStreamingRecognizer.SAMPLE_RATE * 2))
        assertTrue(
            "Controlled silence must report NO_SPEECH without logging a hypothesis",
            result is SpeechToTextState.Error &&
                result.cause == SpeechToTextError.RecognitionFailed(RecognitionFailureReason.NO_SPEECH),
        )
        assertOffline(appContext)
    }

    private suspend fun recognize(context: Context, pcm: ShortArray): SpeechToTextState {
        assertFalse("STT test must execute outside main", Looper.getMainLooper().isCurrentThread)
        val input = MemoryInput(pcm)
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var nativeStream: VoskStreamingRecognizer? = null
        val engine = VoskSpeechToTextEngine(
            workerScope = ownerScope,
            hasRecordAudioPermission = { true }, // Memory PCM never acquires RECORD_AUDIO.
            captureFactory = { LocalPcmCaptureLease(input) },
            recognizerFactory = {
                assertFalse("Model loading must execute outside main", Looper.getMainLooper().isCurrentThread)
                VoskStreamingRecognizer.create(context).also { nativeStream = it }
            },
            workerDispatcher = Dispatchers.IO,
        )
        val controller = VoiceCaptureSessionController(engine, ownerScope, { true }, SpeechToTextRequest("es-PE"))
        try {
            withTimeout(60_000) { controller.start() }
            withTimeout(60_000) { input.allSamplesRead.await() }
            controller.stop()
            val state = withTimeout(60_000) {
                controller.state.first {
                    it is SpeechToTextState.Result || it is SpeechToTextState.Error || it == SpeechToTextState.Cancelled
                }
            }
            assertEquals("Expected one prepared memory input", 1, input.opens)
            assertEquals("Expected one explicit memory capture start", 1, input.starts)
            assertEquals("Expected one explicit memory capture stop", 1, input.stops)
            assertEquals("Terminal state must follow capture cleanup", 1, input.closes)
            assertTrue("Consumed PCM must be cleared after cleanup", pcm.all { it == 0.toShort() })
            var closedNativeStream = false
            try {
                checkNotNull(nativeStream).finish()
            } catch (_: IllegalStateException) {
                closedNativeStream = true
            }
            assertTrue("Terminal state must follow Vosk native-session cleanup", closedNativeStream)
            return state
        } finally {
            try {
                withContext(NonCancellable) {
                    try {
                        controller.close()
                    } finally {
                        try { nativeStream?.close() } finally { input.closeIfNeeded(); pcm.fill(0) }
                    }
                }
            } finally {
                ownerScope.cancel()
            }
        }
    }

    private suspend fun readFixture(context: Context): ShortArray = withContext(Dispatchers.IO) {
        val manifest = JSONObject(context.assets.open("stt-stage2/audio-manifest.json").bufferedReader().use { it.readText() })
        assertEquals("CC-BY-4.0", manifest.getString("license"))
        assertEquals("human_read_speech", manifest.getString("speechType"))
        val fixtures = manifest.getJSONArray("samples")
        val spec = (0 until fixtures.length()).map(fixtures::getJSONObject)
            .single { it.getString("path") == "fleurs-es_419-san_pedro.wav" }
        val bytes = context.assets.open("stt-stage2/${spec.getString("path")}").use { it.readBytes() }
        try {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals("Fixture integrity mismatch", spec.getString("sha256"), digest)
            val wave = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WAVEfmt ", bytes.copyOfRange(8, 16).toString(Charsets.US_ASCII))
            assertEquals(16, wave.getInt(16))
            assertEquals(1, wave.getShort(20).toInt()) // PCM, not compressed audio.
            assertEquals(1, wave.getShort(22).toInt())
            assertEquals(VoskStreamingRecognizer.SAMPLE_RATE, wave.getInt(24))
            assertEquals(16, wave.getShort(34).toInt())
            assertEquals("data", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
            val count = spec.getInt("numSamples")
            assertEquals(count * 2, wave.getInt(40))
            assertEquals(44 + count * 2, bytes.size)
            wave.position(44)
            ShortArray(count) { wave.short }
        } finally {
            bytes.fill(0)
        }
    }

    private class MemoryInput(private val pcm: ShortArray) : LocalPcmInput {
        val allSamplesRead = CompletableDeferred<Unit>()
        private val stopped = CompletableDeferred<Unit>()
        private var cursor = 0
        @Volatile var opens = 0
        @Volatile var starts = 0
        @Volatile var stops = 0
        @Volatile var closes = 0
        override suspend fun open() { opens++ }
        override suspend fun start() { starts++ }
        override suspend fun read(samples: ShortArray): Int {
            assertFalse("PCM decoding worker must execute outside main", Looper.getMainLooper().isCurrentThread)
            if (cursor < pcm.size) {
                val count = minOf(samples.size, pcm.size - cursor)
                pcm.copyInto(samples, 0, cursor, cursor + count)
                cursor += count
                if (cursor == pcm.size) allSamplesRead.complete(Unit)
                return count
            }
            stopped.await()
            return 0
        }
        override suspend fun stop() { stops++; stopped.complete(Unit) }
        override suspend fun close() { closes++; stopped.complete(Unit); pcm.fill(0) }
        fun closeIfNeeded() { if (closes == 0) { closes++; stopped.complete(Unit); pcm.fill(0) } }
    }

    private fun assertOffline(context: Context) {
        assertEquals("Airplane mode must be enabled", 1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0))
        assertEquals("Wi-Fi must be disabled", 0, Settings.Global.getInt(context.contentResolver, "wifi_on", 1))
        assertEquals("Mobile data must be disabled", 0, Settings.Global.getInt(context.contentResolver, "mobile_data", 1))
    }
}
