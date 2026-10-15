package com.yezvi.assistant.data.speech

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-session microphone ownership for the future capture engine. Attaching never records.
 * Start remains explicit; STOP/DESTROY close the resource, even if no read is in progress.
 * The owner calls close in finally to await cleanup and receive any real cleanup error.
 */
internal class LifecyclePcmCapture private constructor(
    private val lifecycle: Lifecycle,
    private val ownerScope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    sourceFactory: (() -> Boolean) -> AudioRecordPcmSource,
) {
    private val allowed = AtomicBoolean(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    /** False after the owner leaves STARTED; callers must discard, not finalize, the session. */
    val isOwnerActive: Boolean get() = allowed.get()
    val source: AudioRecordPcmSource = sourceFactory { allowed.get() }
    private var closeResult: CompletableDeferred<Unit>? = null
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> if (closeResult == null) allowed.set(true)
            Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> requestClose()
            else -> Unit
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        val cleanup = withContext(mainDispatcher) { requestClose() }
        cleanup.await()
    }

    /** Only accessed on the main dispatcher; the worker sees the AtomicBoolean predicate. */
    private fun requestClose(): CompletableDeferred<Unit> {
        allowed.set(false)
        lifecycle.removeObserver(observer)
        closeResult?.let { return it }
        val completion = CompletableDeferred<Unit>()
        closeResult = completion
        // Owned cleanup continues if the supplied presentation scope is already cancelled.
        // Its result remains observable by close(); no exception or audio is logged here.
        ownerScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                source.close()
                completion.complete(Unit)
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
            }
        }
        return completion
    }

    companion object {
        suspend fun attach(context: Context, lifecycle: Lifecycle, ownerScope: CoroutineScope): LifecyclePcmCapture =
            attach(lifecycle, ownerScope, Dispatchers.Main.immediate) { active ->
                AudioRecordPcmSource.create(context.applicationContext, active)
            }

        internal suspend fun attach(
            lifecycle: Lifecycle,
            ownerScope: CoroutineScope,
            mainDispatcher: CoroutineDispatcher,
            sourceFactory: (() -> Boolean) -> AudioRecordPcmSource,
        ): LifecyclePcmCapture = withContext(mainDispatcher) {
            check(lifecycle.currentState != Lifecycle.State.DESTROYED) { "Capture owner is destroyed" }
            LifecyclePcmCapture(lifecycle, ownerScope, mainDispatcher, sourceFactory).also {
                lifecycle.addObserver(it.observer)
            }
        }
    }
}
