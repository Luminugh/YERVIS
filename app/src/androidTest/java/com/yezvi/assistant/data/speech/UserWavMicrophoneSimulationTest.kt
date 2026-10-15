package com.yezvi.assistant.data.speech

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextState
import com.yezvi.assistant.presentation.capture.VoiceCaptureSessionController
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in test: injects a user-authorized WAV into the local mic-input seam; it is not live mic. */
@RunWith(AndroidJUnit4::class)
class UserWavMicrophoneSimulationTest {
    @Test
    fun userWavPassesThroughRealLocalVadAndSherpaAndComparesToPrivateReference() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("yezviLocalWavSimulation") == "true")
        val includeTranscript = args.getString("includeTranscript") == "true"
        val wav = File(requireNotNull(args.getString("wavPath")))
        val referenceFile = File(requireNotNull(args.getString("referencePath")))
        require(wav.isFile && referenceFile.isFile)

        val wavBytes = wav.readBytes()
        val parsed = try {
            decodePcm16WavTo16kMono(wavBytes)
        } finally {
            wavBytes.fill(0)
        }
        val pcm = parsed.samples
        val reference = referenceFile.readText(Charsets.UTF_8)
        val input = UserWavPcmInput(pcm)
        val appContext = ApplicationProvider.getApplicationContext<Context>()
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
        val startedAt = SystemClock.elapsedRealtime()
        try {
            controller.start()
            withTimeout(120_000) { input.allSamplesRead.await() }
            controller.stop()
            val terminal = withTimeout(240_000) {
                controller.state.first { it is SpeechToTextState.Result || it is SpeechToTextState.Error }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val report = JSONObject()
                .put("status", if (terminal is SpeechToTextState.Result) "result" else "error")
                .put("sourceSampleRate", parsed.sourceSampleRate)
                .put("sourceChannels", parsed.sourceChannels)
                .put("sourceDurationMs", parsed.sourceDurationMs)
                .put("processedSampleRate", SherpaOfflineRecognizerAdapter.SAMPLE_RATE)
                .put("processedSamples", pcm.size)
                .put("processedDurationMs", pcm.size * 1000.0 / SherpaOfflineRecognizerAdapter.SAMPLE_RATE)
                .put("nonZeroSamples", parsed.nonZeroSamples)
                .put("rms", parsed.rms)
                .put("pipelineElapsedMs", elapsedMs)
            if (terminal is SpeechToTextState.Result) {
                val transcript = terminal.transcript.text
                val wer = wordError(reference, transcript)
                report.put("referenceWords", wer.referenceWords)
                    .put("hypothesisWords", wer.hypothesisWords)
                    .put("substitutions", wer.substitutions)
                    .put("deletions", wer.deletions)
                    .put("insertions", wer.insertions)
                    .put("wer", wer.rate)
                    .put("keywordMatches", listOf("Discord", "CPU", "memoria", "8792", "4668", "Edge")
                        .count { transcript.contains(it, ignoreCase = true) })
                if (includeTranscript) {
                    // Only the explicit local one-off run stores text, in app cache, never logs.
                    report.put("transcript", transcript)
                }
            } else {
                report.put("errorType", (terminal as SpeechToTextState.Error).cause::class.simpleName)
            }
            val cache = appContext.cacheDir
            if (!cache.isDirectory) check(cache.mkdirs() || cache.isDirectory)
            File(cache, RESULT_FILE).writeText(report.toString(), Charsets.UTF_8)
            assertTrue("Local pipeline ended in ${report.optString("errorType", "unknown error")}", terminal is SpeechToTextState.Result)
        } finally {
            withContext(NonCancellable) { controller.close() }
            ownerScope.cancel()
            pcm.fill(0)
        }
    }

    private fun decodePcm16WavTo16kMono(bytes: ByteArray): DecodedWav {
        require(bytes.size >= 44 && fourCc(bytes, 0) == "RIFF" && fourCc(bytes, 8) == "WAVE")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var blockAlign = 0
        var dataOffset = -1
        var dataLength = -1
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunk = fourCc(bytes, offset)
            val lengthLong = buffer.getInt(offset + 4).toLong() and 0xffff_ffffL
            require(lengthLong <= Int.MAX_VALUE && offset + 8L + lengthLong <= bytes.size)
            val length = lengthLong.toInt()
            val payload = offset + 8
            when (chunk) {
                "fmt " -> {
                    require(length >= 16)
                    format = buffer.getShort(payload).toInt() and 0xffff
                    channels = buffer.getShort(payload + 2).toInt() and 0xffff
                    sampleRate = buffer.getInt(payload + 4)
                    blockAlign = buffer.getShort(payload + 12).toInt() and 0xffff
                    bitsPerSample = buffer.getShort(payload + 14).toInt() and 0xffff
                }
                "data" -> { dataOffset = payload; dataLength = length }
            }
            offset = payload + length + (length and 1)
        }
        require(format == 1 && channels in 1..2 && bitsPerSample == 16 && sampleRate >= 16_000)
        require(sampleRate % 16_000 == 0 && blockAlign == channels * 2 && dataOffset >= 0)
        val frameCount = dataLength / blockAlign
        require(frameCount > 0)
        val mono = ShortArray(frameCount) { frame ->
            var sum = 0
            repeat(channels) { channel ->
                sum += buffer.getShort(dataOffset + (frame * channels + channel) * 2).toInt()
            }
            (sum / channels).toShort()
        }
        val output = Pcm16TestResampler.to16k(mono, sampleRate)
        mono.fill(0)
        var sumSquares = 0.0
        var nonZero = 0
        output.forEach {
            if (it.toInt() != 0) nonZero++
            val normalized = it / 32768.0
            sumSquares += normalized * normalized
        }
        return DecodedWav(
            samples = output,
            sourceSampleRate = sampleRate,
            sourceChannels = channels,
            sourceDurationMs = frameCount * 1000.0 / sampleRate,
            nonZeroSamples = nonZero,
            rms = kotlin.math.sqrt(sumSquares / output.size),
        )
    }

    private fun fourCc(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, StandardCharsets.US_ASCII)

    private fun wordError(reference: String, hypothesis: String): WordError {
        fun words(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .replace("[^\\p{L}\\p{N}\\s]".toRegex(), " ")
            .trim().split("\\s+".toRegex()).filter(String::isNotBlank)
        val expected = words(reference)
        val actual = words(hypothesis)
        val distance = Array(expected.size + 1) { IntArray(actual.size + 1) }
        val direction = Array(expected.size + 1) { ByteArray(actual.size + 1) }
        for (i in 1..expected.size) { distance[i][0] = i; direction[i][0] = 2 }
        for (j in 1..actual.size) { distance[0][j] = j; direction[0][j] = 3 }
        for (i in 1..expected.size) for (j in 1..actual.size) {
            if (expected[i - 1] == actual[j - 1]) {
                distance[i][j] = distance[i - 1][j - 1]
                direction[i][j] = 0
            } else {
                val substitute = distance[i - 1][j - 1] + 1
                val delete = distance[i - 1][j] + 1
                val insert = distance[i][j - 1] + 1
                val best = minOf(substitute, delete, insert)
                distance[i][j] = best
                direction[i][j] = if (best == substitute) 1 else if (best == delete) 2 else 3
            }
        }
        var i = expected.size
        var j = actual.size
        var substitutions = 0
        var deletions = 0
        var insertions = 0
        while (i > 0 || j > 0) when {
            i > 0 && j > 0 && direction[i][j].toInt() == 0 -> { i--; j-- }
            i > 0 && j > 0 && direction[i][j].toInt() == 1 -> { substitutions++; i--; j-- }
            i > 0 && (j == 0 || direction[i][j].toInt() == 2) -> { deletions++; i-- }
            else -> { insertions++; j-- }
        }
        val errors = substitutions + deletions + insertions
        return WordError(expected.size, actual.size, substitutions, deletions, insertions,
            errors.toDouble() / expected.size.coerceAtLeast(1))
    }

    private data class DecodedWav(
        val samples: ShortArray,
        val sourceSampleRate: Int,
        val sourceChannels: Int,
        val sourceDurationMs: Double,
        val nonZeroSamples: Int,
        val rms: Double,
    )

    private data class WordError(
        val referenceWords: Int,
        val hypothesisWords: Int,
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        val rate: Double,
    )

    private class UserWavPcmInput(private val pcm: ShortArray) : LocalPcmInput {
        val allSamplesRead = CompletableDeferred<Unit>()
        private val stopped = CompletableDeferred<Unit>()
        private var cursor = 0
        override suspend fun open() = Unit
        override suspend fun start() = Unit
        override suspend fun read(samples: ShortArray): Int {
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
        override suspend fun stop() { stopped.complete(Unit) }
        override suspend fun close() { stopped.complete(Unit); pcm.fill(0) }
    }

    private companion object {
        const val RESULT_FILE = "yezvi_local_wav_simulation.json"
    }
}
