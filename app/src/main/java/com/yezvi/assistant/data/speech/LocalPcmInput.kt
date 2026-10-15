package com.yezvi.assistant.data.speech

import java.util.concurrent.atomic.AtomicBoolean

/** Local PCM input shared by recognizers and deterministic tests; never stores audio. */
internal interface LocalPcmInput {
    suspend fun open()
    suspend fun start()
    suspend fun read(samples: ShortArray): Int
    suspend fun stop()
    suspend fun pause() = stop()
    suspend fun resume() = start()
    suspend fun close()
}

internal class AudioRecordLocalPcmInput(private val source: AudioRecordPcmSource) : LocalPcmInput {
    override suspend fun open() = source.open()
    override suspend fun start() = source.start()
    override suspend fun read(samples: ShortArray) = source.read(samples)
    override suspend fun stop() = source.stop()
    override suspend fun pause() = source.pause()
    override suspend fun resume() = source.resume()
    override suspend fun close() = source.close()
}

/** One lifecycle-owned lease, closed at most once even when cancellation races cleanup. */
internal class LocalPcmCaptureLease(
    val input: LocalPcmInput,
    private val releaseOwner: suspend () -> Unit = { input.close() },
    val isOwnerActive: () -> Boolean = { true },
) {
    private val released = AtomicBoolean(false)

    suspend fun close() {
        if (released.compareAndSet(false, true)) releaseOwner()
    }
}
