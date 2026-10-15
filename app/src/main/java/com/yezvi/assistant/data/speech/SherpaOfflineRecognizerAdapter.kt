package com.yezvi.assistant.data.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class LocalSpeechRecognition(
    val text: String,
    val tokens: List<String>,
    val timestamps: List<Float>,
    val audioDurationMs: Double,
    val inferenceMs: Double,
    /** Experimental acoustic log-scores; absent unless an explicit diagnostic reader is supplied. */
    val tokenLogProbabilities: FloatArray? = null,
    val tokenScoreReadMs: Double = 0.0,
)

/** Final, local file/PCM inference only. No microphone, network or text replacement. */
internal class SherpaOfflineRecognizerAdapter internal constructor(
    private val decoder: Decoder,
    private val vocabulary: LocalSpeechVocabulary,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val initializationMs: Double = 0.0,
) {
    // Narrow test seam; it does not replace the shared SpeechToTextEngine contract.
    internal interface Decoder {
        fun decode(samples: FloatArray, sampleRate: Int, hotwords: String): DecodedText
        fun release()
    }

    internal data class DecodedText(
        val text: String, val tokens: List<String>, val timestamps: List<Float>,
        val tokenLogProbabilities: FloatArray? = null,
        val tokenScoreReadMs: Double = 0.0,
    )

    private val mutex = Mutex()
    private var closed = false

    suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int = SAMPLE_RATE,
        hotwords: List<String> = emptyList(),
    ): LocalSpeechRecognition = withContext(workerDispatcher) {
        mutex.withLock {
            check(!closed) { "Local recognizer is closed" }
            require(sampleRate == SAMPLE_RATE) { "Local recognizer requires mono PCM at 16000 Hz" }
            require(samples.isNotEmpty() && samples.all { it.isFinite() && it in -1f..1f }) {
                "Local recognizer requires finite, normalized PCM samples"
            }
            val validated = vocabulary.validateTerms(hotwords)
            require(validated.rejectedTerms.isEmpty()) { "Hotwords contain unsafe or unsupported characters" }
            currentCoroutineContext().ensureActive()
            // Do not give JNI a buffer that it can change or retain on the caller's behalf.
            val snapshot = samples.copyOf()
            val started = System.nanoTime()
            val decoded = try {
                decoder.decode(snapshot, sampleRate, validated.hotwords)
            } finally {
                // Native decode is synchronous; promptly discard this private working copy too.
                snapshot.fill(0f)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            // Native decode is synchronous: cancellation discards its result, then resources
            // are released by the caller's finally/close, never concurrently with native work.
            currentCoroutineContext().ensureActive()
            LocalSpeechRecognition(
                text = decoded.text,
                tokens = decoded.tokens.toList(),
                timestamps = decoded.timestamps.toList(),
                audioDurationMs = samples.size * 1000.0 / sampleRate,
                inferenceMs = elapsedMs,
                tokenLogProbabilities = decoded.tokenLogProbabilities?.takeIf { scores ->
                    scores.size == decoded.tokens.size && scores.size <= 2048 &&
                        scores.all { it.isFinite() && it <= 0f }
                }?.copyOf(),
                tokenScoreReadMs = decoded.tokenScoreReadMs,
            )
        }
    }

    /** Idempotent, serialized release, including when the calling coroutine is cancelled. */
    suspend fun close() = withContext(workerDispatcher + NonCancellable) {
        mutex.withLock {
            if (!closed) {
                closed = true
                decoder.release()
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        suspend fun create(
            context: Context,
            hotwordsScore: Float = 1.5f,
            workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
            tokenScoreReader: ((Long) -> FloatArray?)? = null,
        ): SherpaOfflineRecognizerAdapter {
            val files = LocalSpeechModelFiles(context).prepare()
            val vocabulary = withContext(Dispatchers.IO) {
                LocalSpeechVocabulary.fromFiles(files.tokens, files.bpeVocab)
            }
            return open(files, vocabulary, hotwordsScore, workerDispatcher, tokenScoreReader = tokenScoreReader)
        }

        internal fun configurationFor(
            files: PreparedLocalSpeechModelFiles,
            hotwordsScore: Float,
        ): OfflineRecognizerConfig {
            require(hotwordsScore.isFinite() && hotwordsScore >= 0f) { "Invalid local hotword score" }
            return OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = files.encoder.absolutePath,
                        decoder = files.decoder.absolutePath,
                        joiner = files.joiner.absolutePath,
                    ),
                    tokens = files.tokens.absolutePath,
                    modelType = "nemo_transducer",
                    modelingUnit = "bpe",
                    bpeVocab = files.bpeVocab.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    debug = false,
                ),
                decodingMethod = "modified_beam_search",
                maxActivePaths = 4,
                hotwordsScore = hotwordsScore,
            )
        }

        internal suspend fun open(
            files: PreparedLocalSpeechModelFiles,
            vocabulary: LocalSpeechVocabulary,
            hotwordsScore: Float = 1.5f,
            workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
            tokenScoreReader: ((Long) -> FloatArray?)? = null,
            decoderFactory: (OfflineRecognizerConfig) -> Decoder = { config ->
                JniDecoder(OfflineRecognizer(assetManager = null, config = config), tokenScoreReader)
            },
        ): SherpaOfflineRecognizerAdapter {
            val config = configurationFor(files, hotwordsScore)
            var created: Decoder? = null
            try {
                return withContext(workerDispatcher) {
                    currentCoroutineContext().ensureActive()
                    val started = System.nanoTime()
                    val decoder = decoderFactory(config)
                    created = decoder
                    currentCoroutineContext().ensureActive()
                    SherpaOfflineRecognizerAdapter(
                        decoder, vocabulary, workerDispatcher,
                        initializationMs = (System.nanoTime() - started) / 1_000_000.0,
                    )
                }
            } catch (error: Throwable) {
                // Includes prompt cancellation when withContext hands the created handle back.
                val handle = created
                if (handle != null) {
                    withContext(workerDispatcher + NonCancellable) {
                        try {
                            handle.release()
                        } catch (cleanupError: Throwable) {
                            error.addSuppressed(cleanupError)
                        }
                    }
                }
                throw error
            }
        }
    }

    private class JniDecoder(
        private val recognizer: OfflineRecognizer,
        private val tokenScoreReader: ((Long) -> FloatArray?)?,
    ) : Decoder {
        override fun decode(samples: FloatArray, sampleRate: Int, hotwords: String): DecodedText {
            val stream = if (hotwords.isEmpty()) recognizer.createStream() else recognizer.createStream(hotwords)
            try {
                stream.acceptWaveform(samples, sampleRate)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                var readMs = 0.0
                val scores = tokenScoreReader?.let { reader ->
                    val started = System.nanoTime()
                    try {
                        reader(stream.ptr)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null // Optional diagnostics must never discard a successful transcript.
                    } catch (_: LinkageError) {
                        null // Release intentionally has no experimental JNI entry point.
                    } finally {
                        readMs = (System.nanoTime() - started) / 1_000_000.0
                    }
                }
                return DecodedText(result.text, result.tokens.toList(), result.timestamps.toList(), scores, readMs)
            } finally {
                stream.release()
            }
        }

        override fun release() = recognizer.release()
    }
}
