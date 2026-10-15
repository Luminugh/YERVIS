package com.yezvi.assistant.data.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal enum class PcmCaptureFailure {
    PERMISSION_DENIED, CAPTURE_INACTIVE, CLIENT_SILENCED, INVALID_BUFFER,
    INITIALIZATION_FAILED, START_FAILED, READ_FAILED,
}

internal class PcmCaptureException(
    val failure: PcmCaptureFailure,
    val nativeCode: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException("Local PCM capture failed: $failure", cause)

/** Narrow Android seam. All quantities are PCM16 samples except buffer sizes in bytes. */
internal interface AndroidRecordPlatform {
    fun hasPermission(): Boolean
    fun isCaptureActive(): Boolean
    fun minimumBufferBytes(): Int
    fun create(bufferBytes: Int): AndroidRecordHandle
}

internal interface AndroidRecordHandle {
    val initialized: Boolean
    val recording: Boolean
    val clientSilenced: Boolean
    fun start()
    fun read(buffer: ShortArray, offset: Int, length: Int): Int
    fun stop()
    fun release()
}

/**
 * Explicit, bounded MIC capture: mono PCM16/16 kHz, without audio persistence or networking.
 * The owner must close in finally and on leaving its STARTED lifecycle. [create] requires its
 * foreground predicate; reads additionally monitor it while the native call is blocking.
 * One read is allowed at a time. Stop may overlap read; release never does.
 */
internal class AudioRecordPcmSource(
    private val platform: AndroidRecordPlatform,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private enum class State { NEW, OPEN, RECORDING, PAUSED, STOPPED, CLOSED }
    private val guard = Any()
    private var state = State.NEW
    private var handle: AndroidRecordHandle? = null
    private var readFinished: CompletableDeferred<Unit>? = null
    private var interruptedBy: PcmCaptureException? = null
    private var stopFailure: Throwable? = null

    suspend fun open() {
        var createdHere = false
        try {
            withContext(ioDispatcher) {
                synchronized(guard) {
                    check(state == State.NEW) { "PCM source has already been opened or closed" }
                    checkCaptureAllowed()
                    val minimum = platform.minimumBufferBytes()
                    if (minimum <= 0 || minimum > MAX_BUFFER_BYTES / 2) {
                        throw PcmCaptureException(PcmCaptureFailure.INVALID_BUFFER, minimum)
                    }
                    val bytes = maxOf(minimum * 2, 6_400).let { it + (it and 1) }
                    // Establish ownership immediately, including for an uninitialized handle.
                    val created = platform.create(bytes)
                    handle = created
                    createdHere = true
                    if (!created.initialized) {
                        throw PcmCaptureException(PcmCaptureFailure.INITIALIZATION_FAILED)
                    }
                    checkCaptureAllowed()
                    state = State.OPEN
                }
                currentCoroutineContext().ensureActive()
            }
        } catch (error: Throwable) {
            // Prompt cancellation when returning from IO must not orphan the native handle.
            if (createdHere || error is PcmCaptureException || error is CancellationException) closeAfterFailure(error)
            throw error
        }
    }

    suspend fun start() {
        try {
            withContext(ioDispatcher) {
                synchronized(guard) {
                    check(state == State.OPEN || state == State.STOPPED) { "PCM source is not ready to start" }
                    check(readFinished == null) { "Previous PCM read has not finished" }
                    checkCaptureAllowed()
                    val record = checkNotNull(handle)
                    try {
                        record.start()
                    } catch (error: SecurityException) {
                        throw PcmCaptureException(PcmCaptureFailure.PERMISSION_DENIED, cause = error)
                    } catch (error: IllegalStateException) {
                        throw PcmCaptureException(PcmCaptureFailure.START_FAILED, cause = error)
                    }
                    // Track a successful native start before any further checks can fail.
                    state = State.RECORDING
                    interruptedBy = null
                    if (!record.recording) throw PcmCaptureException(PcmCaptureFailure.START_FAILED)
                    checkCaptureAllowed(record)
                }
                currentCoroutineContext().ensureActive()
            }
        } catch (error: Throwable) {
            // A duplicate command is a caller error, so it must not end the existing session.
            if (error is PcmCaptureException || error is CancellationException) closeAfterFailure(error)
            throw error
        }
    }

    /** Returns the exact partial sample count; zero denotes explicit stop/close, never silence. */
    suspend fun read(buffer: ShortArray, offset: Int = 0, length: Int = buffer.size - offset): Int {
        require(offset >= 0 && length in 1..MAX_READ_SAMPLES && offset <= buffer.size - length) {
            "Invalid PCM sample range"
        }
        try {
            return withContext(ioDispatcher) {
                coroutineScope {
                    val finished = CompletableDeferred<Unit>()
                    val record = synchronized(guard) {
                        // A pause can win between the engine's state check and this read.
                        if (state == State.PAUSED) return@coroutineScope 0
                        check(state == State.RECORDING) { "PCM source is not recording" }
                        check(readFinished == null) { "Only one PCM read is allowed" }
                        val current = checkNotNull(handle)
                        try {
                            checkCaptureAllowed(current)
                        } catch (error: PcmCaptureException) {
                            requestStop(error)
                            throw error
                        }
                        readFinished = finished
                        current
                    }
                    val reading = AtomicBoolean(true)
                    // This structured child registers for cancellation before entering native read.
                    // Its finally runs on IO even when the owner cancels from the main thread.
                    val cancellationStop = launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(ioDispatcher + NonCancellable) {
                                if (reading.get()) requestStop()
                            }
                        }
                    }
                    val monitor = launch(ioDispatcher) {
                        while (reading.get()) {
                            delay(MONITOR_INTERVAL_MS)
                            synchronized(guard) {
                                if (state == State.RECORDING) {
                                    try {
                                        checkCaptureAllowed(record)
                                    } catch (error: PcmCaptureException) {
                                        requestStop(error)
                                    }
                                }
                            }
                        }
                    }
                    try {
                        suspendCancellableCoroutine { continuation ->
                            try {
                                if (!continuation.isActive) return@suspendCancellableCoroutine
                                val count = record.read(buffer, offset, length)
                                val result = synchronized(guard) {
                                    interruptedBy?.let { throw it }
                                    if (state != State.RECORDING) 0 else {
                                        checkCaptureAllowed(record)
                                        if (count <= 0 || count > length) {
                                            throw PcmCaptureException(PcmCaptureFailure.READ_FAILED, count)
                                        }
                                        count
                                    }
                                }
                                continuation.resume(result)
                            } catch (error: Throwable) {
                                val mapped = when (error) {
                                    is PcmCaptureException -> error
                                    is SecurityException -> PcmCaptureException(PcmCaptureFailure.PERMISSION_DENIED, cause = error)
                                    is IllegalStateException -> PcmCaptureException(PcmCaptureFailure.READ_FAILED, cause = error)
                                    else -> error
                                }
                                requestStop(mapped as? PcmCaptureException)
                                continuation.resumeWithException(mapped)
                            } finally {
                                reading.set(false)
                                synchronized(guard) { readFinished = null }
                                finished.complete(Unit)
                            }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            monitor.cancelAndJoin()
                            cancellationStop.cancelAndJoin()
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            // Also covers prompt cancellation when a completed read returns from IO.
            closeAfterFailure(error)
            throw error
        }
    }

    /** Stops native capture immediately, including a read that is currently blocked. */
    suspend fun stop(): Unit = withContext(ioDispatcher + NonCancellable) {
        requestStop()?.let { throw it }
        Unit
    }

    /** Pauses the native stream and waits until its in-flight read has returned. */
    suspend fun pause(): Unit = withContext(ioDispatcher + NonCancellable) {
        val (pending, failure) = synchronized(guard) {
            check(state == State.RECORDING) { "PCM source is not recording" }
            state = State.PAUSED
            try {
                handle?.stop()
            } catch (error: Throwable) {
                state = State.STOPPED
                stopFailure = error
            }
            readFinished to stopFailure
        }
        pending?.await()
        failure?.let { throw it }
    }

    /** Restarts the same AudioRecord after a pause without replacing the recognition session. */
    suspend fun resume(): Unit = withContext(ioDispatcher) {
        synchronized(guard) {
            check(state == State.PAUSED) { "PCM source is not paused" }
            checkCaptureAllowed()
            val record = checkNotNull(handle)
            try {
                record.start()
            } catch (error: SecurityException) {
                throw PcmCaptureException(PcmCaptureFailure.PERMISSION_DENIED, cause = error)
            } catch (error: IllegalStateException) {
                throw PcmCaptureException(PcmCaptureFailure.START_FAILED, cause = error)
            }
            if (!record.recording) throw PcmCaptureException(PcmCaptureFailure.START_FAILED)
            state = State.RECORDING
            checkCaptureAllowed(record)
        }
    }

    /** Idempotent, cancellation safe. No release is allowed while read or stop uses the handle. */
    suspend fun close(): Unit {
        var stopError: Throwable? = null
        try {
            withContext(ioDispatcher + NonCancellable) {
                val (pending, stopFailure) = synchronized(guard) {
                    val error = requestStop()
                    state = State.CLOSED
                    readFinished to error
                }
                stopError = stopFailure
                pending?.await()
                synchronized(guard) {
                    val owned = handle
                    handle = null
                    try {
                        owned?.release()
                    } catch (error: Throwable) {
                        stopFailure?.let { if (it !== error) error.addSuppressed(it) }
                        throw error
                    }
                    stopFailure?.let { throw it }
                }
                Unit
            }
        } catch (error: Throwable) {
            // Coroutine stack-trace recovery may copy the primary exception and omit suppressed errors.
            stopError?.let { failure ->
                if (failure !== error && error.suppressed.none { it === failure }) error.addSuppressed(failure)
            }
            throw error
        }
    }

    private suspend fun closeAfterFailure(error: Throwable) {
        try { close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
    }

    private fun checkCaptureAllowed(record: AndroidRecordHandle? = null) {
        try {
            if (!platform.hasPermission()) throw PcmCaptureException(PcmCaptureFailure.PERMISSION_DENIED)
            if (!platform.isCaptureActive()) throw PcmCaptureException(PcmCaptureFailure.CAPTURE_INACTIVE)
            if (record?.clientSilenced == true) throw PcmCaptureException(PcmCaptureFailure.CLIENT_SILENCED)
        } catch (error: SecurityException) {
            throw PcmCaptureException(PcmCaptureFailure.PERMISSION_DENIED, cause = error)
        }
    }

    private fun requestStop(reason: PcmCaptureException? = null): Throwable? = synchronized(guard) {
            if (reason != null && interruptedBy == null) interruptedBy = reason
            if (state == State.RECORDING) {
                state = State.STOPPED
                try { handle?.stop() } catch (error: Throwable) { stopFailure = error }
            } else if (state == State.PAUSED) {
                // The native handle was already stopped by pause(); do not stop it twice.
                state = State.STOPPED
            }
            stopFailure
        }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_READ_SAMPLES = SAMPLE_RATE
        const val MAX_BUFFER_BYTES = 128_000
        private const val MONITOR_INTERVAL_MS = 50L

        /** Create unopened source. Predicate must reflect the owner's current STARTED state. */
        fun create(context: Context, captureAllowed: () -> Boolean): AudioRecordPcmSource =
            AudioRecordPcmSource(FrameworkRecordPlatform(context.applicationContext, captureAllowed))
    }
}

private class FrameworkRecordPlatform(
    private val context: Context,
    private val captureAllowed: () -> Boolean,
) : AndroidRecordPlatform {
    override fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    override fun isCaptureActive() = captureAllowed()
    override fun minimumBufferBytes() = AudioRecord.getMinBufferSize(
        AudioRecordPcmSource.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    )
    override fun create(bufferBytes: Int): AndroidRecordHandle {
        try {
            return FrameworkRecordHandle(AudioRecord(
                MediaRecorder.AudioSource.MIC, AudioRecordPcmSource.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes,
            ))
        } catch (error: SecurityException) {
            throw PcmCaptureException(PcmCaptureFailure.PERMISSION_DENIED, cause = error)
        } catch (error: IllegalArgumentException) {
            throw PcmCaptureException(PcmCaptureFailure.INITIALIZATION_FAILED, cause = error)
        }
    }
}

private class FrameworkRecordHandle(private val record: AudioRecord) : AndroidRecordHandle {
    override val initialized get() = record.state == AudioRecord.STATE_INITIALIZED
    override val recording get() = record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    override val clientSilenced get() = Build.VERSION.SDK_INT >= 29 &&
        record.activeRecordingConfiguration?.isClientSilenced == true
    override fun start() = record.startRecording()
    override fun read(buffer: ShortArray, offset: Int, length: Int) =
        record.read(buffer, offset, length, AudioRecord.READ_BLOCKING)
    override fun stop() = record.stop()
    override fun release() = record.release()
}
