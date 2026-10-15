package com.yezvi.assistant.data.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One ordered piece of original PCM. Boundaries can cut a word at the hard limit. */
internal data class PcmSpeechSegment(
    val startSample: Long,
    val samples: FloatArray,
    val endedByLimit: Boolean,
)

/**
 * Isolated, bounded VAD preparation for the future STT engine. Audio remains in memory.
 * The owner must consume each returned batch before feeding more PCM and close in finally.
 * No recording, inference queue, text rewriting or persistence is implemented here.
 */
internal class SileroPcmSegmenter internal constructor(
    private val detector: FrameDetector,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val preRollSamples: Int = 3_200,
    private val silenceSamples: Int = 8_000,
    private val maxSegmentSamples: Int = 240_000,
) {
    internal interface FrameDetector {
        fun probability(frame: FloatArray): Float
        fun release()
    }

    init {
        require(preRollSamples in 0..SAMPLE_RATE)
        require(silenceSamples in WINDOW_SIZE..SAMPLE_RATE)
        require(maxSegmentSamples in WINDOW_SIZE..SAMPLE_RATE * 15)
        require(preRollSamples < maxSegmentSamples)
    }

    private val mutex = Mutex()
    private val window = FloatArray(WINDOW_SIZE)
    private val preRoll = FloatArray(preRollSamples)
    private val segment = FloatArray(maxSegmentSamples)
    private var windowCount = 0
    private var preRollCount = 0
    private var preRollNext = 0
    private var segmentCount = 0
    private var segmentStart = 0L
    private var consumedSamples = 0L
    private var silentSamples = 0
    private var speechActive = false
    private var finished = false
    private var closed = false

    suspend fun accept(
        pcm: ShortArray,
        offset: Int = 0,
        length: Int = pcm.size - offset,
    ): List<PcmSpeechSegment> = withContext(workerDispatcher) {
        mutex.withLock {
            check(!closed && !finished) { "Local VAD session is closed or finished" }
            require(offset >= 0 && length in 1..MAX_INPUT_SAMPLES && offset <= pcm.size - length)
            val output = mutableListOf<PcmSpeechSegment>()
            for (index in offset until offset + length) {
                window[windowCount++] = pcm[index] / 32768f
                if (windowCount == WINDOW_SIZE) {
                    processWindow(WINDOW_SIZE, output)
                    windowCount = 0
                }
            }
            currentCoroutineContext().ensureActive()
            output
        }
    }

    /** Flushes a partial final window; padding is used only for VAD, never returned as audio. */
    suspend fun finish(): List<PcmSpeechSegment> = withContext(workerDispatcher) {
        mutex.withLock {
            check(!closed) { "Local VAD session is closed" }
            if (finished) return@withLock emptyList()
            val output = mutableListOf<PcmSpeechSegment>()
            if (windowCount > 0) {
                window.fill(0f, windowCount, WINDOW_SIZE)
                processWindow(windowCount, output)
                windowCount = 0
            }
            emit(output, endedByLimit = false)
            speechActive = false
            clearPreRoll()
            finished = true
            currentCoroutineContext().ensureActive()
            output
        }
    }

    /** Releases native VAD serially and discards buffered PCM, including on caller cancellation. */
    suspend fun close() = withContext(workerDispatcher + NonCancellable) {
        mutex.withLock {
            if (!closed) {
                closed = true
                window.fill(0f)
                preRoll.fill(0f)
                segment.fill(0f)
                windowCount = 0
                segmentCount = 0
                clearPreRoll()
                speechActive = false
                detector.release()
            }
        }
    }

    internal suspend fun bufferedSampleCount(): Int = withContext(workerDispatcher) {
        mutex.withLock { windowCount + preRollCount + segmentCount }
    }

    private suspend fun processWindow(validSamples: Int, output: MutableList<PcmSpeechSegment>) {
        currentCoroutineContext().ensureActive()
        val probability = detector.probability(window)
        currentCoroutineContext().ensureActive()
        check(probability.isFinite() && probability in 0f..1f) { "Invalid local VAD probability" }
        val voiced = probability >= THRESHOLD
        val windowStart = consumedSamples
        consumedSamples += validSamples

        if (!speechActive && voiced) {
            speechActive = true
            segmentStart = windowStart - preRollCount
            val first = if (preRollCount == preRollSamples) preRollNext else 0
            repeat(preRollCount) { index -> segment[segmentCount++] = preRoll[(first + index) % preRollSamples] }
            clearPreRoll()
        }
        if (!speechActive) {
            for (index in 0 until validSamples) rememberPreRoll(window[index])
            return
        }

        var offset = 0
        while (offset < validSamples) {
            val count = minOf(maxSegmentSamples - segmentCount, validSamples - offset)
            window.copyInto(segment, segmentCount, offset, offset + count)
            segmentCount += count
            offset += count
            if (segmentCount == maxSegmentSamples) {
                emit(output, endedByLimit = true)
                // Continue contiguously, without overlap or dropping the remainder of this frame.
                segmentStart = windowStart + offset
            }
        }
        silentSamples = if (voiced) 0 else silentSamples + validSamples
        if (silentSamples >= silenceSamples) {
            emit(output, endedByLimit = false)
            speechActive = false
            silentSamples = 0
        }
    }

    private fun emit(output: MutableList<PcmSpeechSegment>, endedByLimit: Boolean) {
        if (segmentCount > 0) {
            output += PcmSpeechSegment(segmentStart, segment.copyOf(segmentCount), endedByLimit)
            segment.fill(0f, 0, segmentCount)
            segmentCount = 0
        }
    }

    private fun rememberPreRoll(value: Float) {
        if (preRollSamples == 0) return
        preRoll[preRollNext] = value
        preRollNext = (preRollNext + 1) % preRollSamples
        preRollCount = minOf(preRollCount + 1, preRollSamples)
    }

    private fun clearPreRoll() {
        preRoll.fill(0f)
        preRollCount = 0
        preRollNext = 0
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 512
        private const val MAX_INPUT_SAMPLES = 4_096
        private const val THRESHOLD = 0.5f

        suspend fun create(context: Context): SileroPcmSegmenter {
            val files = LocalSpeechModelFiles(context).prepare()
            var created: Vad? = null
            try {
                return withContext(Dispatchers.Default) {
                    currentCoroutineContext().ensureActive()
                    val vad = Vad(assetManager = null, config = VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = files.vad.absolutePath, windowSize = WINDOW_SIZE,
                        ),
                        sampleRate = SAMPLE_RATE, numThreads = 1, provider = "cpu", debug = false,
                    ))
                    created = vad
                    currentCoroutineContext().ensureActive()
                    SileroPcmSegmenter(object : FrameDetector {
                        override fun probability(frame: FloatArray): Float = vad.compute(frame)
                        override fun release() = vad.release()
                    })
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.Default + NonCancellable) {
                    try { created?.release() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                }
                throw error
            }
        }
    }
}
