package com.yezvi.assistant.data.speech

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer

/** One local Vosk stream. No grammar restriction, text rewriting, audio storage or network. */
internal class VoskStreamingRecognizer internal constructor(
    private val decoder: Decoder,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The native seam returns text, leaving JSON/native dependencies outside JVM state tests. */
    internal interface Decoder {
        fun accept(samples: ShortArray, length: Int): Chunk
        fun finish(): String
        fun close()
    }

    internal data class Chunk(val text: String, val isEndpoint: Boolean)

    private val mutex = Mutex()
    private val completedParts = mutableListOf<String>()
    private var finishedText: String? = null
    private var closed = false

    /** [length] counts PCM16 mono samples, not bytes, at exactly [SAMPLE_RATE]. */
    suspend fun accept(samples: ShortArray, length: Int): String = withContext(ioDispatcher) {
        require(length in 1..samples.size) { "Invalid Vosk PCM sample range" }
        mutex.withLock {
            check(!closed && finishedText == null) { "Vosk stream is not accepting PCM" }
            currentCoroutineContext().ensureActive()
            val chunk = decoder.accept(samples, length)
            currentCoroutineContext().ensureActive()
            if (chunk.isEndpoint) {
                appendCompleted(chunk.text)
                completedParts.joinToString(" ")
            } else {
                (completedParts + chunk.text.trim().takeIf { it.isNotEmpty() }).filterNotNull()
                    .joinToString(" ")
            }
        }
    }

    /** Flushes the remaining utterance once; an endpoint is never a final session result. */
    suspend fun finish(): String = withContext(ioDispatcher) {
        mutex.withLock {
            check(!closed) { "Vosk stream is closed" }
            finishedText?.let { return@withLock it }
            currentCoroutineContext().ensureActive()
            val tail = decoder.finish()
            currentCoroutineContext().ensureActive()
            appendCompleted(tail)
            completedParts.joinToString(" ").also { finishedText = it }
        }
    }

    /** Waits for native inference to return before freeing Recognizer, then Model. */
    suspend fun close() = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            if (!closed) {
                closed = true
                try {
                    decoder.close()
                } finally {
                    completedParts.clear()
                    finishedText = null
                }
            }
        }
    }

    private fun appendCompleted(text: String) {
        text.trim().takeIf { it.isNotEmpty() }?.let(completedParts::add)
    }

    private class NativeDecoder(private val model: Model, private val recognizer: Recognizer) : Decoder {
        override fun accept(samples: ShortArray, length: Int): Chunk {
            val endpoint = recognizer.acceptWaveForm(samples, length)
            val json = if (endpoint) recognizer.result else recognizer.partialResult
            return Chunk(JSONObject(json).getString(if (endpoint) "text" else "partial"), endpoint)
        }

        override fun finish(): String = JSONObject(recognizer.finalResult).getString("text")

        override fun close() {
            try {
                recognizer.close()
            } finally {
                model.close()
            }
        }

        companion object {
            fun create(modelDirectory: File): NativeDecoder {
                // Kaldi information/debug logs are disabled; no audio/transcript is logged here.
                LibVosk.vosk_set_log_level(-1)
                val model = Model(modelDirectory.absolutePath)
                try {
                    return NativeDecoder(model, Recognizer(model, SAMPLE_RATE.toFloat()))
                } catch (error: Throwable) {
                    try { model.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                    throw error
                }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        suspend fun create(
            context: Context,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): VoskStreamingRecognizer {
            // The model-file constructor reads its bundled manifest, so include it in IO too.
            val directory = withContext(ioDispatcher) { VoskModelFiles(context.applicationContext).prepare() }
            return create(directory, ioDispatcher)
        }

        /** Files are already prepared privately and verified by [VoskModelFiles]. */
        suspend fun create(
            modelDirectory: File,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): VoskStreamingRecognizer {
            var decoder: NativeDecoder? = null
            try {
                // Assign ownership before crossing the cancellable dispatcher boundary.
                withContext(ioDispatcher) {
                    decoder = NativeDecoder.create(modelDirectory)
                    currentCoroutineContext().ensureActive()
                }
                return VoskStreamingRecognizer(checkNotNull(decoder), ioDispatcher)
            } catch (error: Throwable) {
                withContext(ioDispatcher + NonCancellable) {
                    try { decoder?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                }
                throw error
            }
        }
    }
}
