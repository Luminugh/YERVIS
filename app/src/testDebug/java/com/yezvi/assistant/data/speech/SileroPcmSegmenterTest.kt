package com.yezvi.assistant.data.speech

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SileroPcmSegmenterTest {
    @Test fun partialReadsKeepEverySampleAndConvertSignedPcmCorrectly() = runTest {
        val detector = Detector { 1f }
        val vad = SileroPcmSegmenter(detector, StandardTestDispatcher(testScheduler))
        val input = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, -1, 0, 1) + ShortArray(695) { it.toShort() }
        val result = mutableListOf<PcmSpeechSegment>()
        var offset = 0
        for (size in listOf(17, 320, 13, 320, 30)) {
            result += vad.accept(input, offset, size)
            offset += size
        }
        result += vad.finish()
        assertEquals(1, result.size)
        assertEquals(0L, result.single().startSample)
        assertArrayEquals(FloatArray(input.size) { input[it] / 32768f }, result.single().samples, 0f)
        assertEquals(2, detector.frames.size)
        assertEquals(-1f, detector.frames.first().first(), 0f)
        assertEquals(32767f / 32768f, detector.frames.first()[1], 0f)
        assertEquals(0f, detector.frames.last().last(), 0f)
        vad.close()
    }

    @Test fun preRollAndSilenceTailHaveOrderedOriginalOffsets() = runTest {
        var frame = 0
        val detector = Detector { if (frame++ == 1) 1f else 0f }
        val vad = SileroPcmSegmenter(detector, StandardTestDispatcher(testScheduler),
            preRollSamples = 512, silenceSamples = 1024, maxSegmentSamples = 4096)
        val input = ShortArray(2048) { it.toShort() }
        val result = vad.accept(input) + vad.finish()
        assertEquals(1, result.size)
        assertEquals(0L, result.single().startSample)
        assertEquals(2048, result.single().samples.size)
        assertArrayEquals(FloatArray(2048) { input[it] / 32768f }, result.single().samples, 0f)
        vad.close()
    }

    @Test fun finalPartialWindowIsFlushedWithoutSyntheticPaddingInOutput() = runTest {
        val detector = Detector { 1f }
        val vad = SileroPcmSegmenter(detector, StandardTestDispatcher(testScheduler))
        assertTrue(vad.accept(ShortArray(150) { 55 }).isEmpty())
        val result = vad.finish()
        assertEquals(150, result.single().samples.size)
        assertTrue(vad.finish().isEmpty())
        expectFailure<IllegalStateException> { vad.accept(ShortArray(1)) }
        vad.close()
    }

    @Test fun hardLimitSplitsContiguouslyWithoutOverlapOrMissingSamples() = runTest {
        val vad = SileroPcmSegmenter(Detector { 1f }, StandardTestDispatcher(testScheduler),
            preRollSamples = 0, maxSegmentSamples = 1024)
        val input = ShortArray(3200) { it.toShort() }
        val result = vad.accept(input) + vad.finish()
        assertEquals(listOf(0L, 1024L, 2048L, 3072L), result.map { it.startSample })
        assertEquals(listOf(1024, 1024, 1024, 128), result.map { it.samples.size })
        assertEquals(listOf(true, true, true, false), result.map { it.endedByLimit })
        assertArrayEquals(FloatArray(3200) { input[it] / 32768f }, result.flatMap { it.samples.asIterable() }.toFloatArray(), 0f)
        vad.close()
    }

    @Test fun longSilenceKeepsOnlyBoundedPreRollAndIncompleteWindow() = runTest {
        val vad = SileroPcmSegmenter(Detector { 0f }, StandardTestDispatcher(testScheduler))
        repeat(1000) {
            assertTrue(vad.accept(ShortArray(319)).isEmpty())
            assertTrue(vad.bufferedSampleCount() <= 3200 + 511)
        }
        assertTrue(vad.finish().isEmpty())
        assertEquals(0, vad.bufferedSampleCount())
        vad.close()
    }

    @Test fun wrappedPreRollKeepsLatestSamplesInChronologicalOrder() = runTest {
        var frames = 0
        val vad = SileroPcmSegmenter(Detector { if (frames++ < 10) 0f else 1f }, StandardTestDispatcher(testScheduler))
        val input = ShortArray(5632) { it.toShort() }
        assertTrue(vad.accept(input, 0, 4096).isEmpty())
        val result = vad.accept(input, 4096, 1536) + vad.finish()
        assertEquals(1920L, result.single().startSample)
        assertArrayEquals(FloatArray(3712) { input[it + 1920] / 32768f }, result.single().samples, 0f)
        vad.close()
    }

    @Test fun realFifteenSecondLimitAndLongSpeechKeepNonAlignedRemainders() = runTest {
        val vad = SileroPcmSegmenter(Detector { 1f }, StandardTestDispatcher(testScheduler), preRollSamples = 0)
        val result = mutableListOf<PcmSpeechSegment>()
        val input = ShortArray(512_013) { (it % 32768).toShort() }
        var offset = 0
        while (offset < input.size) {
            val size = minOf(319, input.size - offset)
            result += vad.accept(input, offset, size)
            offset += size
        }
        result += vad.finish()
        assertEquals(listOf(0L, 240_000L, 480_000L), result.map { it.startSample })
        assertEquals(listOf(240_000, 240_000, 32_013), result.map { it.samples.size })
        assertArrayEquals(FloatArray(input.size) { input[it] / 32768f }, result.flatMap { it.samples.asIterable() }.toFloatArray(), 0f)
        vad.close()
    }

    @Test fun closeDiscardsPcmAndReleasesOnce() = runTest {
        val detector = Detector { 1f }
        val vad = SileroPcmSegmenter(detector, StandardTestDispatcher(testScheduler))
        vad.accept(ShortArray(1024))
        vad.close(); vad.close()
        assertEquals(0, vad.bufferedSampleCount())
        assertEquals(1, detector.releases)
        expectFailure<IllegalStateException> { vad.finish() }
    }

    @Test fun invalidInputAndProbabilitiesFailExplicitly() = runTest {
        val detector = Detector { Float.NaN }
        val vad = SileroPcmSegmenter(detector, StandardTestDispatcher(testScheduler))
        expectFailure<IllegalArgumentException> { vad.accept(ShortArray(0)) }
        expectFailure<IllegalArgumentException> { vad.accept(ShortArray(5), -1, 2) }
        expectFailure<IllegalArgumentException> { vad.accept(ShortArray(5000)) }
        expectFailure<IllegalStateException> { vad.accept(ShortArray(512)) }
        vad.close()
        assertEquals(1, detector.releases)
    }

    @Test fun cancellationDuringNativeComputeDiscardsBatchAndReleases() = runTest {
        lateinit var job: Job
        val detector = Detector { job.cancel(); 1f }
        val vad = SileroPcmSegmenter(detector, StandardTestDispatcher(testScheduler))
        var returned = false
        job = launch {
            try { vad.accept(ShortArray(512)); returned = true } finally { vad.close() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(1, detector.releases)
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.simpleName}") } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }

    private class Detector(private val value: () -> Float) : SileroPcmSegmenter.FrameDetector {
        val frames = mutableListOf<FloatArray>()
        var releases = 0
        override fun probability(frame: FloatArray): Float { frames += frame.copyOf(); return value() }
        override fun release() { releases++ }
    }
}
