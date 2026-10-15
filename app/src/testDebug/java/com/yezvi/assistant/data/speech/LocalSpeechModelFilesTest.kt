package com.yezvi.assistant.data.speech

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSpeechModelFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun preparesAllSixVerifiedFilesAndReusesCacheWithoutOpeningAssets() = runTest {
        val fixture = Fixture(temporaryFolder.newFolder())
        val opens = AtomicInteger()
        val files = fixture.loader(open = { path ->
            opens.incrementAndGet()
            fixture.stream(path)
        }, dispatcher = StandardTestDispatcher(testScheduler))

        val prepared = files.prepare()
        assertEquals(File(fixture.root, "stt/$DIGEST").canonicalFile, prepared.directory)
        assertEquals(fixture.contents.keys, prepared.directory.list()!!.toSet())
        fixture.assertContents(prepared.directory)
        assertEquals(File(prepared.directory, "encoder.int8.onnx"), prepared.encoder)
        assertEquals(File(prepared.directory, "decoder.int8.onnx"), prepared.decoder)
        assertEquals(File(prepared.directory, "joiner.int8.onnx"), prepared.joiner)
        assertEquals(File(prepared.directory, "tokens.txt"), prepared.tokens)
        assertEquals(File(prepared.directory, "bpe.vocab"), prepared.bpeVocab)
        assertEquals(File(prepared.directory, "silero_vad.onnx"), prepared.vad)

        assertEquals(prepared, files.prepare())
        assertEquals(6, opens.get())
        assertEquals(setOf(DIGEST), File(fixture.root, "stt").list()!!.toSet())
    }

    @Test
    fun sameSizeIncorrectSourceHashNeverPublishesDirectory() = runTest {
        val fixture = Fixture(temporaryFolder.newFolder())
        val loader = fixture.loader(open = { path ->
            val content = fixture.contentFor(path).copyOf()
            content[0] = (content[0].toInt() xor 1).toByte()
            ByteArrayInputStream(content)
        }, dispatcher = StandardTestDispatcher(testScheduler))

        expectIOException { loader.prepare() }

        assertFalse(fixture.target.exists())
        assertTrue(File(fixture.root, "stt").list()!!.isEmpty())
    }

    @Test
    fun truncatedAndOversizedSourcesFailWithoutPublishing() = runTest {
        for (sizeChange in listOf(-1, 1)) {
            val fixture = Fixture(temporaryFolder.newFolder())
            val loader = fixture.loader(open = { path ->
                val content = fixture.contentFor(path)
                ByteArrayInputStream(content.copyOf(content.size + sizeChange))
            }, dispatcher = StandardTestDispatcher(testScheduler))

            expectIOException { loader.prepare() }

            assertFalse(fixture.target.exists())
            assertTrue(File(fixture.root, "stt").list()!!.isEmpty())
        }
    }

    @Test
    fun missingAssetNeverPublishesDirectory() = runTest {
        val fixture = Fixture(temporaryFolder.newFolder())

        expectIOException {
            fixture.loader(open = { throw IOException("Synthetic missing asset") },
                dispatcher = StandardTestDispatcher(testScheduler)).prepare()
        }

        assertFalse(fixture.target.exists())
        assertTrue(File(fixture.root, "stt").list()!!.isEmpty())
    }

    @Test
    fun interruptedCopyClosesSourceAndPreservesOtherValidVersionAndUserFiles() = runTest {
        val fixture = Fixture(temporaryFolder.newFolder())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = fixture.loader(digest = OLD_DIGEST, dispatcher = dispatcher).prepare()
        val userFile = File(fixture.root, "memory.db").apply { writeText("synthetic unrelated data") }
        var closed = false
        val loader = fixture.loader(open = { path ->
            object : ByteArrayInputStream(fixture.contentFor(path)) {
                var reads = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (++reads == 2) throw IOException("Synthetic interrupted copy")
                    return super.read(buffer, offset, minOf(length, 2))
                }
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }, dispatcher = dispatcher)

        expectIOException { loader.prepare() }

        assertTrue(closed)
        fixture.assertContents(old.directory)
        assertEquals("synthetic unrelated data", userFile.readText())
        assertFalse(fixture.target.exists())
        assertEquals(setOf(OLD_DIGEST), File(fixture.root, "stt").list()!!.toSet())
    }

    @Test
    fun cancellationBetweenReadsCleansStagingAndPreservesPreviousVersion() = runTest {
        val fixture = Fixture(temporaryFolder.newFolder())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = fixture.loader(digest = OLD_DIGEST, dispatcher = dispatcher).prepare()
        lateinit var preparation: Job
        var closed = false
        val loader = fixture.loader(open = { path ->
            object : ByteArrayInputStream(fixture.contentFor(path)) {
                var reads = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (++reads == 2) {
                        assertFalse(fixture.target.exists())
                        preparation.cancel()
                    }
                    return super.read(buffer, offset, minOf(length, 2))
                }
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }, dispatcher = dispatcher)
        preparation = launch { loader.prepare() }
        preparation.join()

        assertTrue(preparation.isCancelled)
        assertTrue(closed)
        fixture.assertContents(old.directory)
        assertFalse(fixture.target.exists())
        assertEquals(setOf(OLD_DIGEST), File(fixture.root, "stt").list()!!.toSet())
        // Cancellation also released the shared lock; a subsequent prepare can succeed.
        fixture.assertContents(fixture.loader(dispatcher = dispatcher).prepare().directory)
    }

    @Test
    fun corruptCachedHashIsReplacedOnlyAfterCompleteVerifiedCopy() = runTest {
        val fixture = Fixture(temporaryFolder.newFolder())
        val dispatcher = StandardTestDispatcher(testScheduler)
        fixture.loader(dispatcher = dispatcher).prepare()
        val cachedEncoder = File(fixture.target, "encoder.int8.onnx")
        val corrupted = cachedEncoder.readBytes().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        cachedEncoder.writeBytes(corrupted)
        val opens = AtomicInteger()

        val prepared = fixture.loader(open = { path ->
            opens.incrementAndGet()
            // Every source is opened while the existing cache still has its old contents.
            assertArrayEquals(corrupted, cachedEncoder.readBytes())
            fixture.stream(path)
        }, dispatcher = dispatcher).prepare()

        fixture.assertContents(prepared.directory)
        assertEquals(6, opens.get())
        assertEquals(setOf(DIGEST), File(fixture.root, "stt").list()!!.toSet())
    }

    @Test
    fun failedReplacementRetainsExistingCacheUntilAValidSetIsReady() = runTest {
        val fixture = Fixture(temporaryFolder.newFolder())
        val dispatcher = StandardTestDispatcher(testScheduler)
        fixture.loader(dispatcher = dispatcher).prepare()
        File(fixture.target, "tokens.txt").writeText("synthetic corruption")
        val before = fixture.target.listFiles()!!.associate { it.name to it.readBytes().toList() }

        expectIOException {
            fixture.loader(open = { throw IOException("Synthetic failure") }, dispatcher = dispatcher).prepare()
        }

        assertEquals(before, fixture.target.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertEquals(setOf(DIGEST), File(fixture.root, "stt").list()!!.toSet())
    }

    @Test
    fun missingCachedFileAndUnexpectedExtraFileAreNotReused() = runTest {
        val fixture = Fixture(temporaryFolder.newFolder())
        val dispatcher = StandardTestDispatcher(testScheduler)
        fixture.loader(dispatcher = dispatcher).prepare()
        assertTrue(File(fixture.target, "joiner.int8.onnx").delete())
        File(fixture.target, "unexpected.txt").writeText("synthetic extra")
        val opens = AtomicInteger()

        val prepared = fixture.loader(open = { path ->
            opens.incrementAndGet()
            fixture.stream(path)
        }, dispatcher = dispatcher).prepare()

        fixture.assertContents(prepared.directory)
        assertEquals(6, opens.get())
        assertEquals(fixture.contents.keys, prepared.directory.list()!!.toSet())
    }

    @Test
    fun simultaneousInstancesShareTheLockAndOnlyOneCopiesTheModel() = runTest {
        val fixture = Fixture(temporaryFolder.newFolder())
        val copying = CompletableDeferred<Unit>()
        val releaseCopy = Semaphore(0)
        val firstOpens = AtomicInteger()
        val secondOpens = AtomicInteger()
        val first = fixture.loader(open = { path ->
            if (firstOpens.incrementAndGet() == 1) {
                copying.complete(Unit)
                releaseCopy.acquire()
            }
            fixture.stream(path)
        })
        val second = fixture.loader(open = { path ->
            secondOpens.incrementAndGet()
            fixture.stream(path)
        })
        val firstPreparation = async { first.prepare() }
        copying.await()
        val secondStarted = CompletableDeferred<Unit>()
        val secondPreparation = async {
            secondStarted.complete(Unit)
            second.prepare()
        }
        try {
            secondStarted.await()
            assertFalse(fixture.target.exists())
            assertEquals(0, secondOpens.get())
        } finally {
            releaseCopy.release()
        }

        assertEquals(firstPreparation.await(), secondPreparation.await())
        assertEquals(6, firstOpens.get())
        assertEquals(0, secondOpens.get())
        fixture.assertContents(fixture.target)
        assertEquals(setOf(DIGEST), File(fixture.root, "stt").list()!!.toSet())
    }

    @Test
    fun cancellingAWaitingCallerDoesNotReleaseTheActiveCallersLock() = runTest {
        val fixture = Fixture(temporaryFolder.newFolder())
        val copying = CompletableDeferred<Unit>()
        val releaseCopy = Semaphore(0)
        val opens = AtomicInteger()
        val first = fixture.loader(open = { path ->
            if (opens.incrementAndGet() == 1) {
                copying.complete(Unit)
                releaseCopy.acquire()
            }
            fixture.stream(path)
        })
        val preparing = async { first.prepare() }
        copying.await()
        val waiterStarted = CompletableDeferred<Unit>()
        val waiting = async {
            waiterStarted.complete(Unit)
            fixture.loader(open = { error("Cancelled waiter must not open assets") }).prepare()
        }
        try {
            waiterStarted.await()
            waiting.cancelAndJoin()
            assertTrue(waiting.isCancelled)
            assertFalse(fixture.target.exists())
        } finally {
            releaseCopy.release()
        }

        val prepared = preparing.await()
        fixture.assertContents(prepared.directory)
        assertEquals(6, opens.get())
        assertEquals(prepared, fixture.loader(dispatcher = StandardTestDispatcher(testScheduler)).prepare())
    }

    private suspend fun expectIOException(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected an integrity or IO failure")
        } catch (_: IOException) {
            // Expected; cancellation must remain a distinct exception.
        }
    }

    private class Fixture(val root: File) {
        val contents = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx",
            "tokens.txt", "bpe.vocab", "silero_vad.onnx").associateWith { name ->
            "synthetic fixture: $name áñ".toByteArray(Charsets.UTF_8)
        }
        val target = File(root, "stt/$DIGEST")
        private val specs = contents.map { (name, bytes) ->
            LocalSpeechModelFileSpec("synthetic/$name", name, bytes.size.toLong(), sha256(bytes))
        }

        fun contentFor(path: String): ByteArray = contents.getValue(path.substringAfterLast('/'))
        fun stream(path: String): InputStream = ByteArrayInputStream(contentFor(path))
        fun loader(
            open: (String) -> InputStream = ::stream,
            digest: String = DIGEST,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ) = LocalSpeechModelFiles(root, open, specs, digest, dispatcher)

        fun assertContents(directory: File) {
            contents.forEach { (name, bytes) -> assertArrayEquals(bytes, File(directory, name).readBytes()) }
        }
    }

    companion object {
        private val DIGEST = "a".repeat(64)
        private val OLD_DIGEST = "b".repeat(64)
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
