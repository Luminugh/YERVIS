package com.yezvi.assistant.data.speech

import android.content.Context
import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.yezvi.assistant.developer.WordUncertaintySession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public, licensed recorded PCM only. No microphone, private audio, or transcript logs. */
@RunWith(AndroidJUnit4::class)
class SpeechConfidenceRuntimeTest {
    @Test fun scoresAreFiniteAndReadingThemCannotRewriteTextWithOrWithoutContext() = runBlocking(Dispatchers.Default) {
        offline()
        assertNull(SherpaTokenScores.readTokenLogProbabilities(0L))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = LocalSpeechModelFiles(context).prepare()
        val pcm = publicPcm()
        try {
            for (score in listOf(0f, 1.5f, 2f)) {
                val config = SherpaOfflineRecognizerAdapter.configurationFor(files, score)
                val recognizer = OfflineRecognizer(config = config)
                try {
                    val stream = if (score == 0f) recognizer.createStream()
                        else recognizer.createStream("Plaza de San Pedro/Carpanedo/YEZVI/Kotlin")
                    try {
                        stream.acceptWaveform(pcm, 16000)
                        recognizer.decode(stream)
                        val result = recognizer.getResult(stream)
                        val scores = checkNotNull(SherpaTokenScores.readTokenLogProbabilities(stream.ptr))
                        assertTrue(result.text.isNotBlank())
                        assertEquals(result.tokens.size, scores.size)
                        assertTrue(scores.isNotEmpty() && scores.all { it.isFinite() && it <= 0f })
                        assertArrayEquals(scores, SherpaTokenScores.readTokenLogProbabilities(stream.ptr), 0f)
                        assertEquals(result.text, recognizer.getResult(stream).text)
                        assertArrayEquals(result.timestamps, recognizer.getResult(stream).timestamps, 0f)
                    } finally { stream.release() }
                } finally { recognizer.release() }
            }
        } finally { pcm.fill(0f) }
        offline()
    }

    @Test fun optionalReaderFailureRetainsTheRealTranscript() = runBlocking(Dispatchers.Default) {
        offline()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = SherpaOfflineRecognizerAdapter.create(context, tokenScoreReader = { throw IllegalStateException("Synthetic metadata failure") })
        val pcm = publicPcm()
        try {
            val result = adapter.transcribe(pcm)
            assertTrue(result.text.isNotBlank())
            assertNull(result.tokenLogProbabilities)
        } finally { pcm.fill(0f); adapter.close() }
    }

    @Test fun publicCorpusAndPairedScoreReadBenchmark() = runBlocking(Dispatchers.Default) {
        val args = InstrumentationRegistry.getArguments()
        val rootPath = args.getString("confidenceCorpusRoot")
        assumeTrue("Explicit public corpus required", rootPath != null)
        offline()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(checkNotNull(rootPath))
        val manifest = JSONObject(File(root, "corpus-manifest.json").readText())
        assertEquals("CC-BY-4.0", manifest.getString("license"))
        val samples = manifest.getJSONArray("samples")
        val mode = args.getString("confidenceMode") ?: "scored"
        val enabled = mode == "scored"
        val files = LocalSpeechModelFiles(context).prepare()
        val start = System.nanoTime()
        val recognizer = OfflineRecognizer(config = SherpaOfflineRecognizerAdapter.configurationFor(files, 0f))
        val loadMs = elapsed(start)
        val rows = JSONArray()
        val report = JSONObject().put("format", 1).put("mode", mode).put("license", "CC-BY-4.0")
            .put("decoder", "modified_beam_search").put("maxActivePaths", 4).put("threads", 2).put("loadMs", loadMs).put("rows", rows)
        val output = File(context.noBackupFilesDir, "confidence-$mode.json")
        fun save() { output.writeText(report.toString()) }
        fun decode(pcm: FloatArray, fixture: String, phase: String, scored: Boolean, round: Int): JSONObject {
            assertFalse(Looper.getMainLooper().isCurrentThread)
            val stream = recognizer.createStream()
            try {
                val cpu = SystemClock.currentThreadTimeMillis()
                val started = System.nanoTime()
                stream.acceptWaveform(pcm, 16000)
                recognizer.decode(stream)
                val decodeMs = elapsed(started)
                val result = recognizer.getResult(stream)
                val scoreStart = System.nanoTime()
                val scores = if (scored) SherpaTokenScores.readTokenLogProbabilities(stream.ptr) else null
                val scoreMs = if (scored) elapsed(scoreStart) else 0.0
                if (scored) {
                    assertNotNull("Raw scores unavailable", scores)
                    assertEquals(result.tokens.size, scores!!.size)
                    assertTrue(scores.all { it.isFinite() && it <= 0f })
                }
                val analysisStart = System.nanoTime()
                val analysis = if (scored) WordUncertaintySession.analyzeSegment(
                    result.text.trim(), result.tokens.toList(), result.timestamps.toList(), scores, pcm.size / 16.0,
                ) else null
                val analysisMs = if (scored) elapsed(analysisStart) else 0.0
                if (scored) {
                    assertNull("Real JNI pieces must map to words", analysis?.unavailableReason)
                    assertTrue("Public speech must contain lexical words", analysis!!.words.isNotEmpty())
                }
                val cpuMs = SystemClock.currentThreadTimeMillis() - cpu
                return JSONObject().put("fixture", fixture).put("phase", phase).put("round", round)
                    .put("scoresEnabled", scored).put("text", result.text).put("tokens", JSONArray(result.tokens.toList()))
                    .put("timestamps", JSONArray(result.timestamps.toList()))
                    .put("tokenLogProbabilities", if (scores == null) JSONObject.NULL else JSONArray(scores.toList()))
                    .put("loadMs", loadMs).put("inferenceMs", decodeMs).put("scoreReadMs", scoreMs)
                    .put("wordAnalysisMs", analysisMs).put("analysisWords", analysis?.words?.size ?: 0)
                    .put("analysisUnavailable", analysis?.unavailableReason ?: JSONObject.NULL)
                    .put("durationMs", pcm.size / 16.0).put("cpuMs", cpuMs)
                    .put("pssKb", Debug.getPss())
            } finally { stream.release() }
        }
        fun pcm(spec: JSONObject): FloatArray {
            val relative = spec.getString("path")
            val file = File(root, relative).canonicalFile
            require(file.toPath().startsWith(root.canonicalFile.toPath()))
            val bytes = file.readBytes()
            try {
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(spec.getString("sha256"), sha)
                return readWave(bytes)
            } finally { bytes.fill(0) }
        }
        try {
            for (index in 0 until samples.length()) {
                val spec = samples.getJSONObject(index)
                val input = pcm(spec)
                try { rows.put(decode(input, spec.getString("fixture"), "corpus", enabled, 0)); save() }
                finally { input.fill(0f) }
            }
            if (enabled) {
                val spec = samples.getJSONObject(0)
                val input = pcm(spec)
                try {
                    // Warm once; alternating order avoids attributing warm-up to one condition.
                    decode(input, spec.getString("fixture"), "warmup", false, -1)
                    repeat(args.getString("confidenceRounds")?.toInt() ?: 30) { round ->
                        val first = round % 2 == 0
                        val a = decode(input, spec.getString("fixture"), "benchmark", first, round)
                        val b = decode(input, spec.getString("fixture"), "benchmark", !first, round)
                        assertEquals(a.getString("text"), b.getString("text"))
                        rows.put(a); rows.put(b); save()
                    }
                } finally { input.fill(0f) }
            }
        } finally { recognizer.release(); save() }
        offline()
    }

    private fun publicPcm(): FloatArray {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open("stt-stage2/fleurs-es_419-san_pedro.wav").use { it.readBytes() }
        return try { readWave(bytes) } finally { bytes.fill(0) }
    }
    private fun readWave(bytes: ByteArray): FloatArray {
        val wave = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVEfmt ", bytes.copyOfRange(8, 16).toString(Charsets.US_ASCII))
        assertEquals(16, wave.getInt(16)); assertEquals(1, wave.getShort(20).toInt())
        assertEquals(1, wave.getShort(22).toInt()); assertEquals(16000, wave.getInt(24))
        assertEquals(16, wave.getShort(34).toInt()); assertEquals("data", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        val count = wave.getInt(40) / 2
        assertEquals(44 + count * 2, bytes.size)
        wave.position(44)
        return FloatArray(count) { wave.short / 32768f }
    }
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000.0
    private fun offline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on", 1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "mobile_data", 1))
    }
}
