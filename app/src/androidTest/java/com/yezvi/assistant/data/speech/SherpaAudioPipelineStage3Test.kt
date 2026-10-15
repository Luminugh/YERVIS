package com.yezvi.assistant.data.speech

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.MainActivity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SherpaAudioPipelineStage3Test {
    @Test
    fun actualSileroVadFeedsSamePublicSpanishSegmentsToOfflineAsrProfiles() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manifest = withContext(Dispatchers.IO) {
            JSONObject(testContext.assets.open("stt-stage2/audio-manifest.json").bufferedReader().use { it.readText() })
        }
        val samples = manifest.getJSONArray("samples")
        val fixtures = (0 until samples.length()).map { index ->
            val item = samples.getJSONObject(index)
            Fixture(item.getString("path"), item.getString("sha256"), item.getInt("numSamples"),
                item.getString("rawText"), item.getString("role"))
        }
        val vadResults = JSONArray()
        val prepared = fixtures.map { fixture ->
            val pcm = withContext(Dispatchers.IO) { readVerifiedPcm16(testContext, fixture) }
            val segmenter = SileroPcmSegmenter.create(appContext)
            val started = System.nanoTime()
            val segments = mutableListOf<PcmSpeechSegment>()
            try {
                // Exercise the production capture block size with deterministic short final reads.
                var cursor = 0
                val blockSizes = intArrayOf(320, 640, 160, 960, 512)
                var blockIndex = 0
                while (cursor < pcm.size) {
                    val size = minOf(blockSizes[blockIndex++ % blockSizes.size], pcm.size - cursor)
                    segments += segmenter.accept(pcm, cursor, size)
                    cursor += size
                }
                segments += segmenter.finish()
            } finally {
                segmenter.close()
            }
            val vadMs = (System.nanoTime() - started) / 1_000_000.0
            val audioMs = pcm.size * 1000.0 / AudioRecordPcmSource.SAMPLE_RATE
            assertTrue("VAD returned no segment for ${fixture.path}", segments.isNotEmpty())
            var previousEnd = 0L
            segments.forEach { segment ->
                assertTrue(segment.startSample >= previousEnd)
                assertTrue(segment.startSample + segment.samples.size <= pcm.size)
                assertTrue(segment.samples.size <= 240_000)
                previousEnd = segment.startSample + segment.samples.size
            }
            vadResults.put(JSONObject()
                .put("fixture", fixture.path).put("fixtureSha256", fixture.sha256)
                .put("sourceSamples", pcm.size).put("audioDurationMs", audioMs)
                .put("vadProcessingMs", vadMs).put("segmentCount", segments.size)
                .put("selectedSpeechMs", segments.sumOf { it.samples.size } * 1000.0 / AudioRecordPcmSource.SAMPLE_RATE)
                .put("segments", JSONArray().apply {
                    segments.forEach { put(JSONObject().put("startSample", it.startSample)
                        .put("sampleCount", it.samples.size).put("endedBy15sLimit", it.endedByLimit)) }
                }))
            fixture to segments
        }

        val allResults = JSONArray()
        val terms = listOf("YEZVI", "Kotlin", "Jetpack Compose", "Nalvero", "Carpanedo", "Plaza de San Pedro", "Luna")
        val profiles = listOf("baseline" to 1.5f, "bias-1" to 1f, "bias-1.5" to 1.5f, "bias-2" to 2f)
        for ((profile, score) in profiles) {
            val adapter = SherpaOfflineRecognizerAdapter.create(appContext, hotwordsScore = score)
            try {
                for ((fixture, segments) in prepared) {
                    val usedTerms = if (profile == "baseline") emptyList() else terms
                    val segmentResults = JSONArray()
                    val textParts = mutableListOf<String>()
                    var inferMs = 0.0
                    withContext(Dispatchers.Default) {
                        segments.forEach { segment ->
                            val result = adapter.transcribe(segment.samples, hotwords = usedTerms)
                            inferMs += result.inferenceMs
                            textParts += result.text
                            segmentResults.put(JSONObject().put("text", result.text)
                                .put("tokens", JSONArray(result.tokens)).put("inferenceMs", result.inferenceMs)
                                .put("audioDurationMs", result.audioDurationMs))
                        }
                    }
                    val finalText = textParts.filter(String::isNotBlank).joinToString(" ").trim()
                    assertTrue("Expected decoded text for ${fixture.path}/$profile", finalText.isNotBlank())
                    allResults.put(JSONObject()
                        .put("fixture", fixture.path).put("role", fixture.role)
                        .put("reference", fixture.reference).put("profile", profile).put("hotwordsScore", score)
                        .put("hotwords", JSONArray(usedTerms)).put("transcript", finalText)
                        .put("inferenceMs", inferMs).put("initializationMs", adapter.initializationMs)
                        .put("segments", segmentResults))
                }
            } finally {
                adapter.close()
            }
        }
        assertEquals(fixtures.size * profiles.size, allResults.length())
        val output = File(appContext.noBackupFilesDir, "stt-stage3-public-results.json")
        withContext(Dispatchers.IO) {
            output.writeText(JSONObject().put("fixtureLicense", "CC-BY-4.0; Google FLEURS es_419; see androidTest assets ATTRIBUTIONS.md")
                .put("vad", vadResults).put("asr", allResults).toString(2))
        }
        // Log only filenames and aggregate timings, never transcripts or PCM.
        fixtures.indices.forEach { index ->
            val f = vadResults.getJSONObject(index)
            println("F3_06_STAGE3 fixture=${f.getString("fixture")} audioMs=${f.getDouble("audioDurationMs")} " +
                "vadMs=${f.getDouble("vadProcessingMs")} segments=${f.getInt("segmentCount")} " +
                "selectedMs=${f.getDouble("selectedSpeechMs")}")
        }
        println("F3_06_STAGE3 corpus=${fixtures.size} profiles=${profiles.size} output=noBackupFiles/stt-stage3-public-results.json")
        assertTrue(output.isFile && output.length() > 0)
    }

    @Test
    fun lifecycleStopClosesCaptureAndAndroidAudioRecordReadsThenDiscardsPcm() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue("RECORD_AUDIO must already be granted; this test does not request permission",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)

        // First verify that ON_STOP/DESTROY closes the source without a session engine or UI binding.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val lifecycleReady = CompletableDeferred<androidx.lifecycle.Lifecycle>()
        scenario.onActivity { lifecycleReady.complete(it.lifecycle) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val fakePlatform = LifecycleFakePlatform()
        val fakeSource = AudioRecordPcmSource(fakePlatform, Dispatchers.IO)
        val owner = LifecyclePcmCapture.attach(lifecycleReady.await(), scope, Dispatchers.Main.immediate) { fakeSource }
        try {
            fakeSource.open()
            fakeSource.start()
            scenario.close()
            withTimeout(3_000) { fakePlatform.released.await() }
            assertEquals(1, fakePlatform.releaseCount.get())
            owner.close()
            assertEquals(1, fakePlatform.releaseCount.get())
        } finally {
            scenario.close()
            owner.close()
            scope.cancel()
        }

        // Then take one 20 ms read through Android AudioRecord. It stays in memory and is zeroed.
        val micScenario = ActivityScenario.launch(MainActivity::class.java)
        val micLifecycle = CompletableDeferred<androidx.lifecycle.Lifecycle>()
        micScenario.onActivity { micLifecycle.complete(it.lifecycle) }
        val micScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var ownerWithMic: LifecyclePcmCapture? = null
        val pcm = ShortArray(320)
        try {
            val capture = LifecyclePcmCapture.attach(context, micLifecycle.await(), micScope)
            ownerWithMic = capture
            capture.source.open()
            capture.source.start()
            val count = capture.source.read(pcm)
            assertTrue("AudioRecord should return a positive partial/full sample count", count in 1..pcm.size)
            val nonZero = pcm.take(count).count { it.toInt() != 0 }
            println("F3_06_STAGE3_AUDIORECORD samples=$count nonZeroSamples=$nonZero rateHz=${AudioRecordPcmSource.SAMPLE_RATE}; PCM discarded")
            pcm.fill(0)
            capture.source.stop()
        } finally {
            pcm.fill(0)
            ownerWithMic?.close()
            micScenario.close()
            micScope.cancel()
        }
    }

    private fun readVerifiedPcm16(context: android.content.Context, fixture: Fixture): ShortArray {
        val bytes = context.assets.open("stt-stage2/${fixture.path}").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(sha == fixture.sha256) { "Public fixture integrity mismatch" }
        val wave = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF")
        check(bytes.copyOfRange(8, 16).toString(Charsets.US_ASCII) == "WAVEfmt ")
        check(wave.getInt(16) == 16 && wave.getShort(20).toInt() == 1 && wave.getShort(22).toInt() == 1)
        check(wave.getInt(24) == 16_000 && wave.getShort(34).toInt() == 16)
        check(bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII) == "data")
        check(wave.getInt(40) == fixture.samples * 2 && bytes.size == 44 + fixture.samples * 2)
        wave.position(44)
        return ShortArray(fixture.samples) { wave.short }
    }

    private data class Fixture(val path: String, val sha256: String, val samples: Int, val reference: String, val role: String)

    private class LifecycleFakePlatform : AndroidRecordPlatform {
        val releaseCount = AtomicInteger()
        val released = CompletableDeferred<Unit>()
        override fun hasPermission() = true
        override fun isCaptureActive() = true
        override fun minimumBufferBytes() = 6_400
        override fun create(bufferBytes: Int) = object : AndroidRecordHandle {
            override var initialized = true
            override var recording = false
            override var clientSilenced = false
            override fun start() { recording = true }
            override fun read(buffer: ShortArray, offset: Int, length: Int) = 0
            override fun stop() { recording = false }
            override fun release() { releaseCount.incrementAndGet(); released.complete(Unit) }
        }
    }
}
