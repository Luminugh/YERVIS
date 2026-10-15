package com.yezvi.assistant.data.speech

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SherpaOfflineRecognizerAdapterTest {
    @Test
    fun diagnosticScoresAreCopiedAndNeverChangeTheNativeTranscript() = runTest {
        val scores = floatArrayOf(-0.1f, -2f, -0.3f)
        val decoder = FakeDecoder().apply { tokenScores = scores }
        val adapter = SherpaOfflineRecognizerAdapter(decoder, vocabulary(), StandardTestDispatcher(testScheduler))
        try {
            val result = adapter.transcribe(floatArrayOf(0f))
            assertEquals(" yeSBi. ", result.text)
            assertArrayEquals(scores, result.tokenLogProbabilities, 0f)
            scores.fill(-9f)
            assertEquals(-0.1f, result.tokenLogProbabilities!![0], 0f)
        } finally { adapter.close() }
    }

    @Test
    fun malformedOrBiasedScoresDisableOnlyOptionalMetadata() = runTest {
        for (scores in listOf(floatArrayOf(-1f), floatArrayOf(Float.NaN, -1f, -1f),
            floatArrayOf(0.1f, -1f, -1f), floatArrayOf(Float.NEGATIVE_INFINITY, -1f, -1f))) {
            val decoder = FakeDecoder().apply { tokenScores = scores }
            val adapter = SherpaOfflineRecognizerAdapter(decoder, vocabulary(), StandardTestDispatcher(testScheduler))
            try {
                val result = adapter.transcribe(floatArrayOf(0f))
                assertEquals(" yeSBi. ", result.text)
                assertEquals(null, result.tokenLogProbabilities)
            } finally { adapter.close() }
        }
    }
    @Test
    fun pinnedConfigurationUsesNemoBpeCpuAndSameBeamForUnbiasedComparison() {
        val config = SherpaOfflineRecognizerAdapter.configurationFor(files(), 2f)
        assertEquals(16_000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals(0f, config.featConfig.dither)
        assertEquals("nemo_transducer", config.modelConfig.modelType)
        assertEquals("bpe", config.modelConfig.modelingUnit)
        assertEquals(files().bpeVocab.absolutePath, config.modelConfig.bpeVocab)
        assertEquals(files().tokens.absolutePath, config.modelConfig.tokens)
        assertEquals(files().encoder.absolutePath, config.modelConfig.transducer.encoder)
        assertEquals(files().decoder.absolutePath, config.modelConfig.transducer.decoder)
        assertEquals(files().joiner.absolutePath, config.modelConfig.transducer.joiner)
        assertEquals("cpu", config.modelConfig.provider)
        assertEquals(2, config.modelConfig.numThreads)
        assertFalse(config.modelConfig.debug)
        assertEquals("modified_beam_search", config.decodingMethod)
        assertEquals(4, config.maxActivePaths)
        assertEquals(2f, config.hotwordsScore)
        assertEquals("", config.hotwordsFile)
    }

    @Test
    fun rejectsInvalidScoresBeforeNativeInitialization() = runTest {
        for (score in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            try {
                SherpaOfflineRecognizerAdapter.open(files(), vocabulary(), score,
                    StandardTestDispatcher(testScheduler), decoderFactory = { error("Must not initialize") })
                fail("Expected invalid score")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun snapshotsPcmAndReturnsExactNativeTextWithoutReplacements() = runTest {
        val decoder = FakeDecoder()
        val adapter = SherpaOfflineRecognizerAdapter(decoder, vocabulary(), StandardTestDispatcher(testScheduler))
        val samples = FloatArray(16_000).also { it[0] = 0.25f }
        val result = adapter.transcribe(samples, hotwords = listOf("  Yezvi  ", "Yezvi"))
        assertNotSame(samples, decoder.lastSamples)
        assertArrayEquals(FloatArray(samples.size), decoder.lastSamples!!, 0f)
        assertEquals(0.25f, samples[0], 0f)
        assertEquals("Yezvi", decoder.lastHotwords)
        assertEquals(" yeSBi. ", result.text)
        assertEquals(1000.0, result.audioDurationMs, 0.0)
        assertTrue(result.inferenceMs >= 0.0)
        assertEquals(listOf("▁ye", "S", "Bi"), result.tokens)
        adapter.close()
        adapter.close()
        assertEquals(1, decoder.releases)
    }

    @Test
    fun rejectsInvalidPcmSampleRateAndUnsafeHotwordsBeforeDecode() = runTest {
        val decoder = FakeDecoder()
        val adapter = SherpaOfflineRecognizerAdapter(decoder, vocabulary(), StandardTestDispatcher(testScheduler))
        for (samples in listOf(FloatArray(0), floatArrayOf(Float.NaN), floatArrayOf(1.01f))) {
            expectIllegalArgument { adapter.transcribe(samples) }
        }
        expectIllegalArgument { adapter.transcribe(floatArrayOf(0f), sampleRate = 48_000) }
        expectIllegalArgument { adapter.transcribe(floatArrayOf(0f), hotwords = listOf("Yezvi/other")) }
        assertEquals(0, decoder.calls)
        adapter.close()
    }

    @Test
    fun realDecoderErrorPropagatesAndCallerCanRelease() = runTest {
        val expected = IOException("Synthetic native failure")
        val decoder = FakeDecoder().apply { failure = expected }
        val adapter = SherpaOfflineRecognizerAdapter(decoder, vocabulary(), StandardTestDispatcher(testScheduler))
        try {
            adapter.transcribe(floatArrayOf(0f))
            fail("Expected decoder error")
        } catch (error: IOException) {
            // Coroutine stack-trace recovery may copy IOException and retain its cause.
            assertEquals(expected.message, error.message)
            assertTrue(error === expected || error.cause === expected)
        } finally {
            adapter.close()
        }
        assertEquals(1, decoder.releases)
    }

    @Test
    fun cancellationDuringSynchronousDecodeDiscardsResultAndClosesSafely() = runTest {
        val decoder = FakeDecoder()
        val adapter = SherpaOfflineRecognizerAdapter(decoder, vocabulary(), StandardTestDispatcher(testScheduler))
        var returned = false
        lateinit var job: Job
        decoder.onDecode = { job.cancel() }
        job = launch {
            try {
                adapter.transcribe(floatArrayOf(0f))
                returned = true
            } finally {
                adapter.close()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(1, decoder.releases)
    }

    @Test
    fun cancellationDuringInitializationReleasesCreatedHandle() = runTest {
        val decoder = FakeDecoder()
        var returned = false
        lateinit var job: Job
        job = launch {
            SherpaOfflineRecognizerAdapter.open(files(), vocabulary(), workerDispatcher = StandardTestDispatcher(testScheduler),
                decoderFactory = { job.cancel(); decoder })
            returned = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(1, decoder.releases)
    }

    @Test
    fun closedAdapterRejectsInferenceWithoutReenteringNativeHandle() = runTest {
        val decoder = FakeDecoder()
        val adapter = SherpaOfflineRecognizerAdapter(decoder, vocabulary(), StandardTestDispatcher(testScheduler))
        adapter.close()
        try {
            adapter.transcribe(floatArrayOf(0f))
            fail("Expected closed recognizer")
        } catch (_: IllegalStateException) { }
        assertEquals(0, decoder.calls)
        assertEquals(1, decoder.releases)
    }

    private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
        try { block(); fail("Expected invalid input") } catch (_: IllegalArgumentException) { }
    }

    private fun vocabulary(): LocalSpeechVocabulary {
        val pieces = listOf("<unk>", "▁", "Y", "e", "z", "v", "i", "<blk>")
        return LocalSpeechVocabulary.fromTexts(
            pieces.mapIndexed { index, piece -> "$piece $index\n" }.joinToString(""),
            pieces.joinToString("") { "$it\t-1.0\n" }, pieces.size,
        )
    }

    private fun files(): PreparedLocalSpeechModelFiles {
        val root = File("synthetic-model")
        return PreparedLocalSpeechModelFiles(root, File(root, "encoder.int8.onnx"), File(root, "decoder.int8.onnx"),
            File(root, "joiner.int8.onnx"), File(root, "tokens.txt"), File(root, "bpe.vocab"), File(root, "silero_vad.onnx"))
    }

    private class FakeDecoder : SherpaOfflineRecognizerAdapter.Decoder {
        var calls = 0
        var releases = 0
        var lastSamples: FloatArray? = null
        var lastHotwords = ""
        var failure: IOException? = null
        var onDecode: () -> Unit = { }
        var tokenScores: FloatArray? = null
        override fun decode(samples: FloatArray, sampleRate: Int, hotwords: String): SherpaOfflineRecognizerAdapter.DecodedText {
            calls++
            lastSamples = samples
            lastHotwords = hotwords
            onDecode()
            failure?.let { throw it }
            return SherpaOfflineRecognizerAdapter.DecodedText(" yeSBi. ", listOf("▁ye", "S", "Bi"), listOf(0.1f, 0.2f, 0.3f), tokenScores)
        }
        override fun release() { releases++ }
    }
}
