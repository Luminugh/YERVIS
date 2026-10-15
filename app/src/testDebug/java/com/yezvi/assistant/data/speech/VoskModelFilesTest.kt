package com.yezvi.assistant.data.speech

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class VoskModelFilesTest {
    @Test
    fun nestedModelIsVerifiedAndTamperedCacheReplaced() = runTest {
        val root = Files.createTempDirectory("vosk-model-test").toFile()
        try {
            val bytes = "synthetic model".toByteArray()
            val spec = VoskModelFileSpec("am/final.mdl", bytes.size.toLong(), hash(bytes))
            var opens = 0
            val files = VoskModelFiles(root, { opens++; ByteArrayInputStream(bytes) }, listOf(spec))
            val prepared = files.prepare()
            assertArrayEquals(bytes, prepared.resolve(spec.path).readBytes())
            assertEquals(prepared, files.prepare())
            assertEquals(1, opens)
            prepared.resolve(spec.path).writeText("tampered")
            assertArrayEquals(bytes, files.prepare().resolve(spec.path).readBytes())
            assertEquals(2, opens)
            assertFalse(checkNotNull(prepared.parentFile).listFiles()!!.any { it.name.contains("staging") || it.name.contains("-old-") })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun badBundledHashNeverPublishesPartialModel() = runTest {
        val root = Files.createTempDirectory("vosk-model-bad").toFile()
        try {
            val spec = VoskModelFileSpec("am/final.mdl", 3, hash("abc".toByteArray()))
            val files = VoskModelFiles(root, { ByteArrayInputStream("bad".toByteArray()) }, listOf(spec))
            try { files.prepare(); fail("Expected integrity failure") } catch (_: IOException) { }
            assertEquals(0, root.resolve("stt-vosk").listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun rejectsParentTraversalBeforeAnyAssetAccess() {
        val root = Files.createTempDirectory("vosk-path").toFile()
        try {
            try {
                VoskModelFiles(root, { error("Must not open") }, listOf(VoskModelFileSpec("../outside", 1, "0".repeat(64))))
                fail("Expected path rejection")
            } catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
