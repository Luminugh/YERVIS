package com.yezvi.assistant.data.speech

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** Local licensed file fixtures. Never opens microphones or touches memory entries. */
@RunWith(AndroidJUnit4::class)
class SherpaSpanishInferenceTest {
    @Test
    fun comparesHumanSpanishWithAndWithoutContextOnIdenticalPcm() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val manifest = withContext(Dispatchers.IO) {
            JSONObject(testContext.assets.open("stt-stage2/audio-manifest.json").bufferedReader().use { it.readText() })
        }
        val samples = manifest.getJSONArray("samples")
        val fixtures = (0 until samples.length()).map { index ->
            val sample = samples.getJSONObject(index)
            Fixture(sample.getString("path"), sample.getString("sha256"), sample.getInt("numSamples"),
                sample.getString("rawText"), sample.getString("role"))
        }
        compare(fixtures, "public", listOf("Carpanedo", "Plaza de San Pedro", "Luna", "YEZVI", "Kotlin", "Jetpack Compose", "Nalvero"))
    }

    @Test
    fun comparesAuthorizedRecordingIncludingNegativeContextControls() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val available = testContext.assets.list("stt-stage2/user")?.contains("sample.wav") == true
        assumeTrue("User audio is local and intentionally excluded from Git; provision it to run this test", available)
        val sample = withContext(Dispatchers.IO) {
            JSONObject(testContext.assets.open("stt-stage2/user/audio-manifest.json").bufferedReader().use { it.readText() })
        }
        compare(listOf(Fixture("user/sample.wav", sample.getString("sha256"), sample.getInt("numSamples"),
            sample.getString("expectedText"), "user_supplied_human_recording_reference_approximate")),
            "user", listOf("Discord", "CPU", "Edge", "YEZVI", "Kotlin", "Jetpack Compose", "Nalvero"))
    }

    @Test
    fun rejectsInvalidAudioAndVocabularyBeforeNativeDecode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = SherpaOfflineRecognizerAdapter.create(context)
        try {
            try {
                adapter.transcribe(FloatArray(512), sampleRate = 48_000)
                throw AssertionError("Expected unsupported sample rate")
            } catch (_: IllegalArgumentException) { }
            try {
                adapter.transcribe(FloatArray(512), hotwords = listOf("YEZVI:100"))
                throw AssertionError("Expected unsafe hotwords")
            } catch (_: IllegalArgumentException) { }
            // The same real recognizer remains usable after preflight failures.
            assertTrue(adapter.transcribe(FloatArray(16_000)).text.isEmpty())
        } finally {
            adapter.close()
        }
    }

    private suspend fun compare(fixtures: List<Fixture>, corpus: String, hotwords: List<String>) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val waves = withContext(Dispatchers.IO) { fixtures.map { it to readVerifiedPcm(testContext, it) } }
        val results = JSONArray()
        // Instrumentation executes with the target UID; the separate test APK's
        // application data directory is not writable by that process.
        val output = File(context.noBackupFilesDir, "stt-stage2-$corpus-results.json")
        val profiles = listOf("baseline" to 1.5f, "bias-1" to 1f, "bias-1.5" to 1.5f, "bias-2" to 2f)
        for ((profile, score) in profiles) {
            val adapter = SherpaOfflineRecognizerAdapter.create(context, hotwordsScore = score)
            try {
                for ((fixture, samples) in waves) {
                    val terms = if (profile == "baseline") emptyList() else hotwords
                    val result = withContext(Dispatchers.Default) {
                        assertFalse(Looper.getMainLooper().isCurrentThread)
                        adapter.transcribe(samples, hotwords = terms)
                    }
                    assertTrue(result.inferenceMs.isFinite() && result.inferenceMs >= 0.0)
                    assertTrue(result.timestamps.all { it.isFinite() && it >= 0f })
                    assertFalse(result.text.contains('\u0000'))
                    assertTrue("Real speech must produce a result; quality is reported separately", result.text.isNotBlank())
                    results.put(JSONObject()
                        .put("fixture", fixture.path).put("fixtureSha256", fixture.sha256).put("role", fixture.role)
                        .put("expectedText", fixture.reference).put("profile", profile).put("hotwordsScore", score)
                        .put("hotwords", JSONArray(terms)).put("text", result.text).put("tokens", JSONArray(result.tokens))
                        .put("timestamps", JSONArray(result.timestamps)).put("audioDurationMs", result.audioDurationMs)
                        .put("inferenceMs", result.inferenceMs).put("initializationMs", adapter.initializationMs))
                    withContext(Dispatchers.IO) {
                        output.writeText(JSONObject().put("corpus", corpus).put("results", results).toString(2))
                    }
                    // No transcribed text in logcat, including the private user fixture.
                    println("F3_06_STAGE2 corpus=$corpus fixture=${fixture.path} profile=$profile " +
                        "audioMs=${result.audioDurationMs} inferenceMs=${result.inferenceMs} chars=${result.text.length}")
                }
            } finally {
                adapter.close()
            }
        }
        assertEquals(fixtures.size * profiles.size, results.length())
    }

    private fun readVerifiedPcm(context: Context, fixture: Fixture): FloatArray {
        val bytes = context.assets.open("stt-stage2/${fixture.path}").use { it.readBytes() }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(sha256 == fixture.sha256) { "Local audio fixture integrity failure" }
        // Only our fixed, generated PCM16 WAVE fixtures; not a general-purpose user WAV parser.
        val wave = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF")
        check(bytes.copyOfRange(8, 16).toString(Charsets.US_ASCII) == "WAVEfmt ")
        check(wave.getInt(16) == 16 && wave.getShort(20).toInt() == 1 && wave.getShort(22).toInt() == 1)
        check(wave.getInt(24) == 16_000 && wave.getShort(34).toInt() == 16)
        check(bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII) == "data")
        check(wave.getInt(40) == fixture.numSamples * 2 && bytes.size == 44 + fixture.numSamples * 2)
        wave.position(44)
        return FloatArray(fixture.numSamples) { wave.short.toInt() / 32768f }
    }

    private data class Fixture(val path: String, val sha256: String, val numSamples: Int, val reference: String, val role: String)
}
