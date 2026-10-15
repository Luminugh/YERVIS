package com.yezvi.assistant.data.speech

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in diagnostics only. Private input/output stay out of logs and APK assets. No microphone. */
@RunWith(AndroidJUnit4::class)
class SpeechAccuracyDiagnosticTest {
    @Test
    fun comparesIdenticalPcmDirectAndThroughSilero() = runBlocking(Dispatchers.Default) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("speechAccuracyDiagnostic") == "true")
        assertFalse(Looper.getMainLooper().isCurrentThread)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = LocalSpeechModelFiles(context).prepare()
        val vocabulary = LocalSpeechVocabulary.fromFiles(files.tokens, files.bpeVocab)
        val report = JSONObject().put("sampleRate", 16000).put("runs", JSONArray())
        val output = File(context.cacheDir, "speech-accuracy-diagnostic.json")
        val cropControl = args.getString("cropControl") == "true"
        for (preparation in if (cropControl) listOf("fir") else listOf("box", "fir")) {
            val inputFile = File(requireNotNull(args.getString("${preparation}Path")))
            val pcm = readPcm(inputFile)
            val source = FloatArray(pcm.size) { pcm[it] / 32768f }
            val vadStart = System.nanoTime()
            val segmenter = SileroPcmSegmenter.create(context)
            val vadInitMs = elapsed(vadStart)
            val vad = mutableListOf<PcmSpeechSegment>()
            val vadWork = System.nanoTime()
            try {
                var offset = 0
                while (offset < pcm.size) {
                    val count = minOf(4096, pcm.size - offset)
                    vad += segmenter.accept(pcm, offset, count)
                    offset += count
                }
                vad += segmenter.finish()
                assertTrue(segmenter.finish().isEmpty())
            } finally {
                segmenter.close()
            }
            val vadMs = elapsed(vadWork)
            var previousEnd = 0L
            val boundaries = JSONArray()
            for (segment in vad) {
                assertTrue(segment.startSample >= previousEnd)
                val end = segment.startSample + segment.samples.size
                assertTrue(end <= source.size)
                // Exact offsets and PCM identity, including the unpadded last fragment.
                for (i in segment.samples.indices) {
                    assertEquals(source[segment.startSample.toInt() + i], segment.samples[i], 0f)
                }
                previousEnd = end
                boundaries.put(JSONObject().put("startSample", segment.startSample)
                    .put("endSample", end).put("endedByLimit", segment.endedByLimit))
            }
            report.put(preparation, JSONObject().put("sha256", sha256(inputFile))
                .put("samples", pcm.size).put("rms", kotlin.math.sqrt(source.sumOf { it.toDouble() * it } / source.size))
                .put("peak", source.maxOf { kotlin.math.abs(it) }).put("nonZero", pcm.count { it.toInt() != 0 })
                .put("vadInitMs", vadInitMs).put("vadMs", vadMs).put("segments", boundaries)
                .put("retainedSamples", vad.sumOf { it.samples.size }))
            try {
                val profiles = if (cropControl) listOf("crop-control") else if (preparation == "box") listOf("mbs4")
                    else listOf("mbs4", "greedy", "mbs8", "hotwords2")
                for (profile in profiles) {
                    val adapter = SherpaOfflineRecognizerAdapter.open(files, vocabulary,
                        hotwordsScore = if (profile == "hotwords2") 2f else 1.5f,
                        decoderFactory = { config ->
                            config.decodingMethod = if (profile == "greedy") "greedy_search" else "modified_beam_search"
                            config.maxActivePaths = if (profile == "mbs8") 8 else 4
                            NativeDecoder(OfflineRecognizer(config = config))
                        })
                    val terms = if (profile == "hotwords2")
                        listOf("Discord", "CPU", "Edge", "YEZVI", "Kotlin", "Jetpack Compose", "Nalvero") else emptyList()
                    try {
                        for (mode in if (cropControl) listOf("vadJoined", "limitJoined") else if (profile == "mbs8" || profile == "greedy") listOf("direct") else listOf("direct", "vad")) {
                            val pieces = when (mode) {
                                "direct" -> listOf(source)
                                "vadJoined" -> listOf(joinPcm(vad.map { it.samples }))
                                "limitJoined" -> {
                                    val groups = mutableListOf<FloatArray>()
                                    val pending = mutableListOf<FloatArray>()
                                    for (segment in vad) {
                                        pending += segment.samples
                                        if (!segment.endedByLimit) {
                                            groups += joinPcm(pending); pending.clear()
                                        }
                                    }
                                    if (pending.isNotEmpty()) groups += joinPcm(pending)
                                    groups
                                }
                                else -> vad.map { it.samples }
                            }
                            val parts = pieces.map { adapter.transcribe(it, hotwords = terms) }
                            report.getJSONArray("runs").put(JSONObject()
                                .put("preparation", preparation).put("profile", profile).put("mode", mode)
                                .put("initMs", adapter.initializationMs).put("asrMs", parts.sumOf { it.inferenceMs })
                                .put("text", parts.map { it.text.trim() }.filter(String::isNotBlank).joinToString(" "))
                                .put("partTexts", JSONArray(parts.map { it.text })))
                            output.writeText(report.toString(), Charsets.UTF_8)
                            assertTrue(parts.any { it.text.isNotBlank() })
                            if (cropControl) pieces.forEach { it.fill(0f) }
                        }
                    } finally {
                        adapter.close()
                    }
                }
            } finally {
                pcm.fill(0); source.fill(0f); vad.forEach { it.samples.fill(0f) }
            }
        }
    }

    @Test
    fun testWavPreparationRejectsAliasingAndPreservesLastSampleCount() {
        val highTone = ShortArray(48000) { (12000 * kotlin.math.sin(2 * Math.PI * 12000 * it / 48000)).toInt().toShort() }
        val filtered = Pcm16TestResampler.to16k(highTone, 48000)
        assertEquals(16000, filtered.size)
        val interiorRms = kotlin.math.sqrt(filtered.drop(64).dropLast(64).map { it.toDouble() * it }.average())
        assertTrue("12 kHz alias must be attenuated", interiorRms < 20)
        assertEquals(3, Pcm16TestResampler.to16k(ShortArray(7) { 100 }, 48000).size)
        val native16k = shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE)
        assertTrue(Pcm16TestResampler.to16k(native16k, 16000).contentEquals(native16k))
    }

    private fun joinPcm(pieces: List<FloatArray>): FloatArray {
        val joined = FloatArray(pieces.sumOf { it.size })
        var offset = 0
        for (piece in pieces) { piece.copyInto(joined, offset); offset += piece.size }
        return joined
    }

    @Test
    fun comparesExistingWhisperWithoutBundlingIt() = runBlocking(Dispatchers.Default) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("speechAccuracyWhisper") == "true")
        val directory = File(requireNotNull(args.getString("whisperDirectory")))
        val pcm = readPcm(File(requireNotNull(args.getString("firPath"))))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(directory, "base-encoder.int8.onnx").absolutePath,
                    decoder = File(directory, "base-decoder.int8.onnx").absolutePath,
                    language = "es", task = "transcribe",
                ),
                tokens = File(directory, "base-tokens.txt").absolutePath,
                modelType = "whisper", numThreads = 2, provider = "cpu", debug = false,
            ),
            decodingMethod = "greedy_search",
        )
        val started = System.nanoTime()
        val recognizer = OfflineRecognizer(config = config)
        val initMs = elapsed(started)
        val parts = JSONArray()
        var totalMs = 0.0
        try {
            // Explicit contiguous 25 s chunks: Whisper's 30 s context cannot take 66 s directly.
            var offset = 0
            while (offset < pcm.size) {
                val count = minOf(25 * 16000, pcm.size - offset)
                val samples = FloatArray(count) { pcm[offset + it] / 32768f }
                val stream = recognizer.createStream()
                val before = System.nanoTime()
                try {
                    stream.acceptWaveform(samples, 16000)
                    recognizer.decode(stream)
                    val time = elapsed(before)
                    totalMs += time
                    parts.put(JSONObject().put("startSample", offset).put("endSample", offset + count)
                        .put("asrMs", time).put("text", recognizer.getResult(stream).text))
                } finally {
                    stream.release(); samples.fill(0f)
                }
                offset += count
            }
            File(context.cacheDir, "speech-accuracy-whisper.json").writeText(
                JSONObject().put("initMs", initMs).put("asrMs", totalMs).put("parts", parts).toString())
            assertEquals(pcm.size, parts.getJSONObject(parts.length() - 1).getInt("endSample"))
        } finally {
            recognizer.release(); pcm.fill(0)
        }
    }

    private class NativeDecoder(private val recognizer: OfflineRecognizer) : SherpaOfflineRecognizerAdapter.Decoder {
        override fun decode(samples: FloatArray, sampleRate: Int, hotwords: String): SherpaOfflineRecognizerAdapter.DecodedText {
            val stream = if (hotwords.isEmpty()) recognizer.createStream() else recognizer.createStream(hotwords)
            try {
                stream.acceptWaveform(samples, sampleRate)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                return SherpaOfflineRecognizerAdapter.DecodedText(result.text, result.tokens.toList(), result.timestamps.toList())
            } finally { stream.release() }
        }
        override fun release() = recognizer.release()
    }

    private suspend fun readPcm(file: File): ShortArray = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        require(bytes.isNotEmpty() && bytes.size % 2 == 0)
        try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            ShortArray(buffer.remaining()).also(buffer::get)
        } finally { bytes.fill(0) }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000.0
}
