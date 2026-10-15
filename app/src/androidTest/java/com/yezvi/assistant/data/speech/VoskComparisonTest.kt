package com.yezvi.assistant.data.speech

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in A/B. Same PCM; no microphone, network, reference editing or text replacements. */
@RunWith(AndroidJUnit4::class)
class VoskComparisonTest {
    @Test
    fun comparesSameHumanPcmOnVoskAndGreedyRnntOffline() = runBlocking(Dispatchers.Default) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("voskComparison") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(Looper.getMainLooper().isCurrentThread)
        assertOffline(context)
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val manifest = JSONObject(testContext.assets.open("stt-stage2/audio-manifest.json").bufferedReader().use { it.readText() })
        val samples = manifest.getJSONArray("samples")
        val report = JSONObject().put("sampleRate", 16000).put("runs", JSONArray())
        val output = File(context.noBackupFilesDir, "vosk-comparison.json")
        val voskPreparation = System.nanoTime()
        val voskDirectory = VoskModelFiles(context).prepare()
        report.put("voskPreparationMs", elapsed(voskPreparation))
        val sherpaPreparation = System.nanoTime()
        val sherpaFiles = LocalSpeechModelFiles(context).prepare()
        report.put("sherpaPreparationMs", elapsed(sherpaPreparation))

        suspend fun compare(id: String, pcm: ShortArray, repeats: Int) {
            try {
                for (round in 0 until repeats) {
                    // Alternate ordering to expose rather than systematically favor warm-cache effects.
                    val engines = if (round % 2 == 0) listOf("vosk", "rnnt-greedy") else listOf("rnnt-greedy", "vosk")
                    for (engine in engines) {
                        assertOffline(context)
                        val loadStart = System.nanoTime()
                        val run = JSONObject().put("fixture", id).put("round", round).put("engine", engine)
                            .put("samples", pcm.size).put("durationMs", pcm.size / 16.0)
                        if (engine == "vosk") {
                            val recognizer = VoskStreamingRecognizer.create(voskDirectory)
                            run.put("loadMs", elapsed(loadStart))
                            val before = System.nanoTime()
                            val buffer = ShortArray(320)
                            try {
                                var offset = 0
                                while (offset < pcm.size) {
                                    val count = minOf(buffer.size, pcm.size - offset)
                                    pcm.copyInto(buffer, 0, offset, offset + count)
                                    recognizer.accept(buffer, count)
                                    offset += count
                                }
                                val stop = System.nanoTime()
                                val text = recognizer.finish()
                                run.put("afterFinishMs", elapsed(stop)).put("inferenceMs", elapsed(before)).put("text", text)
                                assertTrue("Local Vosk returned no text", text.isNotBlank())
                            } finally { buffer.fill(0); recognizer.close() }
                        } else {
                            val config = SherpaOfflineRecognizerAdapter.configurationFor(sherpaFiles, 0f)
                            config.decodingMethod = "greedy_search"
                            val recognizer = OfflineRecognizer(config = config)
                            run.put("loadMs", elapsed(loadStart))
                            val source = FloatArray(pcm.size) { pcm[it] / 32768f }
                            val stream = recognizer.createStream()
                            try {
                                val before = System.nanoTime()
                                stream.acceptWaveform(source, 16000)
                                recognizer.decode(stream)
                                run.put("inferenceMs", elapsed(before)).put("text", recognizer.getResult(stream).text)
                            } finally { stream.release(); source.fill(0f); recognizer.release() }
                        }
                        report.getJSONArray("runs").put(run)
                        output.writeText(report.toString()) // Private no_backup only, never stdout/logcat.
                    }
                }
            } finally { pcm.fill(0) }
        }
        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val bytes = testContext.assets.open("stt-stage2/${sample.getString("path")}").use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(sample.getString("sha256"), digest)
            val wave = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(16000, wave.getInt(24))
            assertEquals(16, wave.getShort(34).toInt())
            wave.position(44)
            val pcm = ShortArray(sample.getInt("numSamples")) { wave.short }
            bytes.fill(0)
            compare(sample.getString("path"), pcm, 1)
        }
        args.getString("comparisonPcmPath")?.let { path ->
            val bytes = File(path).readBytes()
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val pcm = ShortArray(bytes.size / 2) { input.short }
            bytes.fill(0)
            compare("authorized-private-recording", pcm, 3)
        }
        assertEquals(if (args.containsKey("comparisonPcmPath")) 14 else 8, report.getJSONArray("runs").length())
    }

    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000.0

    private fun assertOffline(context: Context) {
        assertEquals(1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on", 1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "mobile_data", 1))
    }
}
