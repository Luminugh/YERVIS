package com.yezvi.assistant.data.speech

import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.yezvi.assistant.domain.speech.SpeechToTextState
import com.yezvi.assistant.presentation.capture.VoiceCaptureSessionController
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real JNI decode, controlled PCM/VAD; no AudioRecord, normal DB or private recording. */
class NativeModelCancellationTest {
    @Test fun cancellingInFlightNativeDecodeWaitsForReleaseAndDiscardsResult() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manifest = JSONObject(instrumentation.context.assets.open("stt-stage2/audio-manifest.json")
            .bufferedReader().use { it.readText() })
        val samples = manifest.getJSONArray("samples")
        val item = (0 until samples.length()).map(samples::getJSONObject)
            .single { it.getString("path") == "fleurs-es_419-san_pedro.wav" }
        val bytes = instrumentation.context.assets.open("stt-stage2/${item.getString("path")}").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(item.getString("sha256"), sha)
        val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16_000, wav.getInt(24))
        assertEquals(1, wav.getShort(22).toInt())
        wav.position(44)
        val input = ControlledInput(ShortArray(minOf(item.getInt("numSamples"), 128_000)) { wav.short })
        val entered = CountDownLatch(1)
        val decoding = AtomicBoolean(false)
        val nativeReleased = AtomicInteger()
        val vadReleased = AtomicInteger()
        val unsafeRelease = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val files = LocalSpeechModelFiles(context).prepare()
        val vocabulary = LocalSpeechVocabulary.fromFiles(files.tokens, files.bpeVocab)
        val engine = SherpaOfflineSpeechToTextEngine(
            scope, { true }, { LocalPcmCaptureLease(input) },
            segmenterFactory = { SileroPcmSegmenter(object : SileroPcmSegmenter.FrameDetector {
                override fun probability(frame: FloatArray) = 1f
                override fun release() { vadReleased.incrementAndGet() }
            }) },
            recognizerFactory = {
                SherpaOfflineRecognizerAdapter.open(files, vocabulary, decoderFactory = { config ->
                    val native = OfflineRecognizer(assetManager = null, config = config)
                    object : SherpaOfflineRecognizerAdapter.Decoder {
                        override fun decode(pcm: FloatArray, sampleRate: Int, hotwords: String): SherpaOfflineRecognizerAdapter.DecodedText {
                            val stream = native.createStream()
                            try {
                                stream.acceptWaveform(pcm, sampleRate)
                                decoding.set(true)
                                entered.countDown() // Immediately before synchronous JNI decode.
                                native.decode(stream)
                                val result = native.getResult(stream)
                                return SherpaOfflineRecognizerAdapter.DecodedText(result.text, emptyList(), emptyList())
                            } finally { decoding.set(false); stream.release() }
                        }
                        override fun release() {
                            if (decoding.get()) unsafeRelease.set(true)
                            native.release()
                            nativeReleased.incrementAndGet()
                        }
                    }
                })
            },
        )
        val controller = VoiceCaptureSessionController(engine, scope, { true })
        try {
            controller.start()
            withTimeout(60_000) { input.allRead.await() }
            controller.stop()
            assertTrue(withContext(Dispatchers.IO) { entered.await(30, TimeUnit.SECONDS) })
            assertTrue("Decode must still be in flight when cancellation is requested", decoding.get())
            withTimeout(60_000) { controller.cancel() }
            assertEquals(SpeechToTextState.Cancelled, controller.state.value)
            assertFalse(decoding.get())
            assertFalse(unsafeRelease.get())
            assertEquals(1, nativeReleased.get())
            assertEquals(1, vadReleased.get())
            assertEquals(1, input.closes)
            assertTrue(input.pcm.all { it == 0.toShort() })
        } finally {
            withContext(NonCancellable) { controller.close() }
            scope.cancel()
        }
    }

    private class ControlledInput(val pcm: ShortArray) : LocalPcmInput {
        val allRead = CompletableDeferred<Unit>()
        private val stopped = CompletableDeferred<Unit>()
        private var position = 0
        var closes = 0
        override suspend fun open() = Unit
        override suspend fun start() = Unit
        override suspend fun read(samples: ShortArray): Int {
            if (position == pcm.size) { stopped.await(); return 0 }
            val count = minOf(samples.size, pcm.size - position)
            pcm.copyInto(samples, 0, position, position + count)
            position += count
            if (position == pcm.size) allRead.complete(Unit)
            return count
        }
        override suspend fun stop() { stopped.complete(Unit) }
        override suspend fun close() { closes++; stopped.complete(Unit); pcm.fill(0) }
    }
}
