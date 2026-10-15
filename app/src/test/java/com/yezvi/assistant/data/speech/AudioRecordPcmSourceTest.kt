package com.yezvi.assistant.data.speech

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AudioRecordPcmSourceTest {
    @Test
    fun requiresPermissionAndStartedOwnerBeforeAllocatingMicrophone(): Unit = runTest {
        for (reason in listOf(PcmCaptureFailure.PERMISSION_DENIED, PcmCaptureFailure.CAPTURE_INACTIVE)) {
            val platform = FakePlatform().apply {
                permission = reason != PcmCaptureFailure.PERMISSION_DENIED
                active = reason != PcmCaptureFailure.CAPTURE_INACTIVE
            }
            val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
            expectFailure(reason) { source.open() }
            assertEquals(0, platform.creations)
            source.close()
        }
    }

    @Test
    fun rejectsNativeMinimumErrorsAndExcessiveBuffersWithoutAllocation(): Unit = runTest {
        for (minimum in listOf(-2, -1, 0, 64_001, Int.MAX_VALUE)) {
            val platform = FakePlatform().apply { minimumBytes = minimum }
            val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
            expectFailure(PcmCaptureFailure.INVALID_BUFFER) { source.open() }
            assertEquals(0, platform.creations)
        }
    }

    @Test
    fun bufferIsInBytesTwiceMinimumWithBoundedFloorAndEvenAlignment(): Unit = runTest {
        for ((minimum, expected) in listOf(321 to 6_400, 4_001 to 8_002, 64_000 to 128_000)) {
            val platform = FakePlatform().apply { minimumBytes = minimum }
            val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
            source.open()
            assertEquals(expected, platform.bufferBytes)
            source.close()
            source.close()
            assertEquals(1, platform.handle.releases)
        }
    }

    @Test
    fun uninitializedHandleAndPermissionLostDuringOpenAreReleased(): Unit = runTest {
        for (reason in listOf(PcmCaptureFailure.INITIALIZATION_FAILED, PcmCaptureFailure.PERMISSION_DENIED)) {
            val platform = FakePlatform().apply {
                handle.initialized = reason != PcmCaptureFailure.INITIALIZATION_FAILED
                if (reason == PcmCaptureFailure.PERMISSION_DENIED) onCreate = { permission = false }
            }
            val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
            expectFailure(reason) { source.open() }
            source.close()
            assertEquals(1, platform.handle.releases)
        }
    }

    @Test
    fun permissionRevokedBeforeStartReleasesWithoutStarting(): Unit = runTest {
        val platform = FakePlatform()
        val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
        source.open()
        platform.permission = false
        expectFailure(PcmCaptureFailure.PERMISSION_DENIED) { source.start() }
        assertEquals(0, platform.handle.starts)
        assertEquals(1, platform.handle.releases)
    }

    @Test
    fun startSecurityFailureAndMissingRecordingStateReleaseTheHandle(): Unit = runTest {
        for (reason in listOf(PcmCaptureFailure.PERMISSION_DENIED, PcmCaptureFailure.START_FAILED)) {
            val platform = FakePlatform().apply {
                if (reason == PcmCaptureFailure.PERMISSION_DENIED) handle.startError = SecurityException("synthetic")
                else handle.startsRecording = false
            }
            val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
            source.open()
            expectFailure(reason) { source.start() }
            assertEquals(1, platform.handle.releases)
        }
    }

    @Test
    fun duplicateOpenAndStartDoNotStopExistingSession(): Unit = runTest {
        val platform = FakePlatform()
        val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
        source.open()
        expectStateError { source.open() }
        source.start()
        expectStateError { source.start() }
        assertEquals(1, platform.creations)
        assertEquals(1, platform.handle.starts)
        assertEquals(0, platform.handle.stops)
        assertEquals(0, platform.handle.releases)
        assertEquals(3, source.read(ShortArray(320)))
        source.close()
    }

    @Test
    fun partialReadReturnsSampleCountAndPreservesRequestedOffset(): Unit = runTest {
        val platform = FakePlatform()
        val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
        source.open()
        source.start()
        val samples = shortArrayOf(9, 9, 9, 9, 9, 9)
        assertEquals(3, source.read(samples, offset = 1, length = 4))
        assertArrayEquals(shortArrayOf(9, -32768, 0, 32767, 9, 9), samples)
        assertEquals(4, platform.handle.lastLength)
        source.close()
    }

    @Test
    fun rejectsInvalidSampleRangesBeforeNativeRead(): Unit = runTest {
        val platform = FakePlatform()
        val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
        source.open()
        source.start()
        for ((offset, length) in listOf(-1 to 1, 0 to 0, 1 to 320, 0 to 16_001, Int.MAX_VALUE to 1)) {
            try { source.read(ShortArray(320), offset, length); fail("Expected invalid range") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, platform.handle.reads)
        source.close()
    }

    @Test
    fun nativeReadErrorsZeroOrOversizedCountStopCapture(): Unit = runTest {
        for (count in listOf(-6, -3, -2, -1, 0, 321)) {
            val platform = FakePlatform().apply { handle.readCount = count }
            val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
            source.open()
            source.start()
            val failure = expectFailure(PcmCaptureFailure.READ_FAILED) { source.read(ShortArray(320)) }
            assertEquals(count, failure.nativeCode)
            assertEquals(1, platform.handle.stops)
            source.close()
            assertEquals(1, platform.handle.releases)
        }
    }

    @Test
    fun nativeSecurityFailureIsTypedAndStopsCapture(): Unit = runTest {
        val platform = FakePlatform().apply { handle.readError = SecurityException("synthetic") }
        val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
        source.open()
        source.start()
        expectFailure(PcmCaptureFailure.PERMISSION_DENIED) { source.read(ShortArray(320)) }
        assertEquals(1, platform.handle.stops)
        source.close()
    }

    @Test
    fun permissionForegroundAndClientSilenceCheckedBeforeAndAfterRead(): Unit = runTest {
        for (duringRead in listOf(false, true)) {
            for (reason in listOf(PcmCaptureFailure.PERMISSION_DENIED, PcmCaptureFailure.CAPTURE_INACTIVE,
                PcmCaptureFailure.CLIENT_SILENCED)) {
                val platform = FakePlatform()
                val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
                source.open()
                source.start()
                val revoke = {
                    when (reason) {
                        PcmCaptureFailure.PERMISSION_DENIED -> platform.permission = false
                        PcmCaptureFailure.CAPTURE_INACTIVE -> platform.active = false
                        else -> platform.handle.clientSilenced = true
                    }
                }
                if (duringRead) platform.handle.onRead = revoke else revoke()
                expectFailure(reason) { source.read(ShortArray(320)) }
                assertEquals(if (duringRead) 1 else 0, platform.handle.reads)
                assertEquals(1, platform.handle.stops)
                source.close()
            }
        }
    }

    @Test
    fun stoppedSourceCanStartAgainAndClosedSourceCannot(): Unit = runTest {
        val platform = FakePlatform()
        val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
        expectStateError { source.start() }
        source.open()
        source.start()
        source.stop()
        source.stop()
        expectStateError { source.read(ShortArray(320)) }
        source.start()
        assertEquals(3, source.read(ShortArray(320)))
        source.close()
        expectStateError { source.start() }
        expectStateError { source.open() }
        assertEquals(2, platform.handle.starts)
        assertEquals(2, platform.handle.stops)
        assertEquals(1, platform.handle.releases)
    }

    @Test
    fun pauseUnblocksReadAndResumesSameHandleWithoutReleasingIt(): Unit = runBlocking {
        withTimeout(5_000) {
            val platform = FakePlatform().apply { handle.blocking = true }
            val source = AudioRecordPcmSource(platform)
            source.open()
            source.start()
            val reader = async(Dispatchers.IO) { source.read(ShortArray(320)) }

            assertTrue(platform.handle.entered.await(2, TimeUnit.SECONDS))
            source.pause()
            assertEquals(0, reader.await())
            assertEquals(1, platform.handle.stops)
            assertEquals(0, platform.handle.releases)

            platform.handle.blocking = false
            source.resume()
            assertEquals(2, platform.handle.starts)
            assertEquals(3, source.read(ShortArray(320)))

            source.close()
            assertEquals(2, platform.handle.stops)
            assertEquals(1, platform.handle.releases)
            assertFalse(platform.handle.releasedDuringRead)
        }
    }

    @Test
    fun closeUnblocksReadAndReleasesOnlyAfterNativeReadHasExited(): Unit = runBlocking {
        withTimeout(5_000) {
            val platform = FakePlatform().apply { handle.blocking = true }
            val source = AudioRecordPcmSource(platform)
            source.open()
            source.start()
            val reader = async(Dispatchers.IO) { source.read(ShortArray(320)) }
            assertTrue(platform.handle.entered.await(2, TimeUnit.SECONDS))
            source.close()
            assertEquals(0, reader.await())
            assertEquals(1, platform.handle.stops)
            assertEquals(1, platform.handle.releases)
            assertFalse(platform.handle.releasedDuringRead)
        }
    }

    @Test
    fun cancellingBlockedReadUnblocksStopAndFinallyReleases(): Unit = runBlocking {
        withTimeout(5_000) {
            val cancellingThread = Thread.currentThread()
            val platform = FakePlatform().apply { handle.blocking = true }
            val source = AudioRecordPcmSource(platform)
            source.open()
            source.start()
            val reader = launch(Dispatchers.IO) {
                try { source.read(ShortArray(320)) } finally { source.close() }
            }
            assertTrue(platform.handle.entered.await(2, TimeUnit.SECONDS))
            reader.cancel()
            reader.join()
            assertTrue(reader.isCancelled)
            assertEquals(1, platform.handle.stops)
            assertEquals(1, platform.handle.releases)
            assertFalse(platform.handle.releasedDuringRead)
            assertNotSame(cancellingThread, platform.handle.readThread)
            assertNotSame(cancellingThread, platform.handle.stopThread)
            assertNotSame(cancellingThread, platform.handle.releaseThread)
        }
    }

    @Test
    fun monitorStopsBlockedReadWhenPermissionOwnerOrSilenceChanges(): Unit = runBlocking {
        withTimeout(8_000) {
            for (reason in listOf(PcmCaptureFailure.PERMISSION_DENIED, PcmCaptureFailure.CAPTURE_INACTIVE,
                PcmCaptureFailure.CLIENT_SILENCED)) {
                val platform = FakePlatform().apply { handle.blocking = true }
                val source = AudioRecordPcmSource(platform)
                source.open()
                source.start()
                val reader = async(Dispatchers.IO) {
                    expectFailure(reason) { source.read(ShortArray(320)) }
                }
                assertTrue(platform.handle.entered.await(2, TimeUnit.SECONDS))
                when (reason) {
                    PcmCaptureFailure.PERMISSION_DENIED -> platform.permission = false
                    PcmCaptureFailure.CAPTURE_INACTIVE -> platform.active = false
                    else -> platform.handle.clientSilenced = true
                }
                reader.await()
                source.close()
                assertEquals(1, platform.handle.stops)
                assertFalse(platform.handle.releasedDuringRead)
            }
        }
    }

    @Test
    fun competingReadIsRejectedWithoutStoppingFirstRead(): Unit = runBlocking {
        withTimeout(5_000) {
            val platform = FakePlatform().apply { handle.blocking = true }
            val source = AudioRecordPcmSource(platform)
            source.open()
            source.start()
            val first = async(Dispatchers.IO) { source.read(ShortArray(320)) }
            assertTrue(platform.handle.entered.await(2, TimeUnit.SECONDS))
            expectStateError { source.read(ShortArray(320)) }
            assertEquals(0, platform.handle.stops)
            source.stop()
            assertEquals(0, first.await())
            source.close()
        }
    }

    @Test
    fun cancellationDuringOpenAndStartCannotOrphanHandle(): Unit = runTest {
        for (cancelDuringOpen in listOf(true, false)) {
            val platform = FakePlatform()
            val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
            lateinit var job: Job
            if (cancelDuringOpen) platform.onCreate = { job.cancel() }
            else { source.open(); platform.handle.onStart = { job.cancel() } }
            job = launch { if (cancelDuringOpen) source.open() else source.start() }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(1, platform.handle.releases)
            assertEquals(if (cancelDuringOpen) 0 else 1, platform.handle.stops)
        }
    }

    @Test
    fun failedReleaseIsNeverRetriedOnAlreadyReleasedHandle(): Unit = runTest {
        val platform = FakePlatform().apply { handle.releaseError = IllegalStateException("synthetic cleanup") }
        val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
        source.open()
        expectStateError { source.close() }
        source.close()
        assertEquals(1, platform.handle.releases)
    }

    @Test
    fun stopFailureStillReleasesAfterReadExitsAndCleanupFailuresAreSuppressed(): Unit = runTest {
        val stopError = IllegalStateException("synthetic stop")
        val releaseError = IllegalStateException("synthetic release")
        val platform = FakePlatform().apply {
            handle.stopError = stopError
            handle.releaseError = releaseError
        }
        val source = AudioRecordPcmSource(platform, StandardTestDispatcher(testScheduler))
        source.open()
        source.start()
        assertEquals(3, source.read(ShortArray(320)))
        try { source.stop(); fail("Expected stop failure") } catch (error: IllegalStateException) {
            assertEquals(stopError.message, error.message)
        }
        try { source.close(); fail("Expected cleanup failure") } catch (error: IllegalStateException) {
            assertEquals(releaseError.message, error.message)
            assertTrue("Suppressed cleanup failures: ${error.suppressed.map { it.message }}; stop calls=${platform.handle.stops}",
                error.suppressed.any { it.message == stopError.message })
        }
        assertEquals(1, platform.handle.releases)
        assertFalse(platform.handle.releasedDuringRead)
    }

    private suspend fun expectFailure(reason: PcmCaptureFailure, block: suspend () -> Unit): PcmCaptureException {
        try { block(); fail("Expected $reason") }
        catch (error: PcmCaptureException) { assertEquals(reason, error.failure); return error }
        error("Unreachable")
    }

    private suspend fun expectStateError(block: suspend () -> Unit) {
        try { block(); fail("Expected state error") } catch (error: IllegalStateException) {
            assertFalse(error is PcmCaptureException)
        }
    }

    private class FakePlatform : AndroidRecordPlatform {
        @Volatile var permission = true
        @Volatile var active = true
        var minimumBytes = 1_024
        var creations = 0
        var bufferBytes = 0
        val handle = FakeHandle()
        var onCreate: () -> Unit = { }
        override fun hasPermission() = permission
        override fun isCaptureActive() = active
        override fun minimumBufferBytes() = minimumBytes
        override fun create(bufferBytes: Int): AndroidRecordHandle {
            creations++
            this.bufferBytes = bufferBytes
            onCreate()
            return handle
        }
    }

    private class FakeHandle : AndroidRecordHandle {
        override var initialized = true
        @Volatile override var recording = false
        @Volatile override var clientSilenced = false
        var startsRecording = true
        var starts = 0
        var reads = 0
        var stops = 0
        var releases = 0
        var lastLength = 0
        var readCount = 3
        var startError: Throwable? = null
        var readError: Throwable? = null
        var stopError: Throwable? = null
        var releaseError: Throwable? = null
        var onStart: () -> Unit = { }
        var onRead: () -> Unit = { }
        var blocking = false
        val entered = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        private val inRead = AtomicBoolean(false)
        var releasedDuringRead = false
        @Volatile var readThread: Thread? = null
        @Volatile var stopThread: Thread? = null
        @Volatile var releaseThread: Thread? = null
        override fun start() {
            starts++
            startError?.let { throw it }
            recording = startsRecording
            onStart()
        }
        override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
            readThread = Thread.currentThread()
            reads++
            lastLength = length
            inRead.set(true)
            entered.countDown()
            try {
                if (blocking) check(stopped.await(3, TimeUnit.SECONDS)) { "Synthetic read not stopped" }
                onRead()
                readError?.let { throw it }
                if (readCount in 1..length) {
                    val values = shortArrayOf(-32768, 0, 32767)
                    repeat(readCount) { buffer[offset + it] = values[it % values.size] }
                }
                return readCount
            } finally { inRead.set(false) }
        }
        override fun stop() {
            stopThread = Thread.currentThread()
            stops++
            recording = false
            stopped.countDown()
            stopError?.let { throw it }
        }
        override fun release() {
            releaseThread = Thread.currentThread()
            releases++
            releasedDuringRead = inRead.get()
            releaseError?.let { throw it }
        }
    }
}
