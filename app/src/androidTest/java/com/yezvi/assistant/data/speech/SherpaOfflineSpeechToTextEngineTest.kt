package com.yezvi.assistant.data.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Full offline engine integration over a licensed PCM fixture; it does not claim mic validation. */
@RunWith(AndroidJUnit4::class)
class SherpaOfflineSpeechToTextEngineTest {
    @Test
    fun controllerRunsPublicSpanishPcmThroughRealSileroAndSherpaWithoutNetwork() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val input = PublicFixtureInput(readFixture(testContext))
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val engine = SherpaOfflineSpeechToTextEngine(
            workerScope = ownerScope,
            hasRecordAudioPermission = { true },
            captureFactory = { LocalPcmCaptureLease(input) },
            segmenterFactory = { SileroPcmSegmenter.create(appContext) },
            recognizerFactory = { SherpaOfflineRecognizerAdapter.create(appContext) },
            workerDispatcher = Dispatchers.IO,
        )
        val controller = VoiceCaptureSessionController(
            engine = engine,
            scope = ownerScope,
            hasRecordAudioPermission = { true },
            request = SpeechToTextRequest("es-PE"),
        )
        try {
            controller.start()
            withTimeout(60_000) { input.allSamplesRead.await() }
            controller.stop()
            val state = withTimeout(60_000) {
                controller.state.first { it is SpeechToTextState.Result || it is SpeechToTextState.Error }
            }
            assertTrue("Expected final local transcript, received $state", state is SpeechToTextState.Result)
            val text = (state as SpeechToTextState.Result).transcript.text
            // Stage 3 measured this exact CC-BY fixture through VAD and the same local model.
            assertTrue("Transcript did not preserve the spoken proper name: $text", text.contains("San Pedro", ignoreCase = true))
            println("F3_06_STAGE4_PUBLIC_PCM fixture=FLEURS-es_419-san_pedro mode=local-only transcript=$text")
        } finally {
            withContext(NonCancellable) { controller.close() }
            ownerScope.cancel()
        }
    }

    private suspend fun readFixture(context: Context): ShortArray = withContext(Dispatchers.IO) {
        val root = JSONObject(context.assets.open("stt-stage2/audio-manifest.json").bufferedReader().use { it.readText() })
        val fixtures = root.getJSONArray("samples")
        val item = (0 until fixtures.length()).map(fixtures::getJSONObject)
            .single { it.getString("path") == "fleurs-es_419-san_pedro.wav" }
        val bytes = context.assets.open("stt-stage2/${item.getString("path")}").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(hash == item.getString("sha256")) { "Public fixture integrity mismatch" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF")
        check(bytes.copyOfRange(8, 16).toString(Charsets.US_ASCII) == "WAVEfmt ")
        check(input.getInt(16) == 16 && input.getShort(20).toInt() == 1 && input.getShort(22).toInt() == 1)
        check(input.getInt(24) == AudioRecordPcmSource.SAMPLE_RATE)
        check(input.getShort(34).toInt() == 16 && bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII) == "data")
        val count = item.getInt("numSamples")
        check(input.getInt(40) == count * 2 && bytes.size == 44 + count * 2)
        input.position(44)
        ShortArray(count) { input.short }
    }

    private class PublicFixtureInput(private val pcm: ShortArray) : LocalPcmInput {
        val allSamplesRead = CompletableDeferred<Unit>()
        private val stopped = CompletableDeferred<Unit>()
        private var cursor = 0
        override suspend fun open() = Unit
        override suspend fun start() = Unit
        override suspend fun read(samples: ShortArray): Int {
            if (cursor < pcm.size) {
                val size = minOf(samples.size, pcm.size - cursor)
                pcm.copyInto(samples, 0, cursor, cursor + size)
                cursor += size
                if (cursor == pcm.size) allSamplesRead.complete(Unit)
                return size
            }
            stopped.await()
            return 0
        }
        override suspend fun stop() { stopped.complete(Unit) }
        override suspend fun close() { stopped.complete(Unit); pcm.fill(0) }
    }
}
