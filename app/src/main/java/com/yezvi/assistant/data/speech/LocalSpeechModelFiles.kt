package com.yezvi.assistant.data.speech

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PreparedLocalSpeechModelFiles(
    val directory: File,
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
    val bpeVocab: File,
    val vad: File,
)

internal data class LocalSpeechModelFileSpec(
    val assetPath: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
)

/** Prepares the complete pinned model locally; no network or user data is involved. */
internal class LocalSpeechModelFiles internal constructor(
    private val noBackupFilesDir: File,
    private val openAsset: (String) -> InputStream,
    private val specs: List<LocalSpeechModelFileSpec> = MODEL_FILES,
    private val modelDigest: String = MODEL_DIGEST,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        noBackupFilesDir = context.noBackupFilesDir,
        openAsset = { path -> context.assets.open(path) },
    )

    init {
        require(modelDigest.matches(Regex("[0-9a-f]{64}")))
        require(specs.map { it.fileName }.toSet() == FILE_NAMES && specs.size == FILE_NAMES.size)
        require(specs.all { it.bytes > 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }

    suspend fun prepare(): PreparedLocalSpeechModelFiles = withContext(ioDispatcher) {
        // Shared by every instance: no caller can observe another caller's partial replacement.
        preparationMutex.withLock {
            val root = noBackupFilesDir.canonicalFile
            val storage = ownedChild(root, "stt")
            if (!storage.isDirectory && !storage.mkdirs()) {
                throw IOException("Cannot create local speech model storage")
            }
            val target = ownedChild(storage, modelDigest)
            if (isValidDirectory(target)) return@withLock prepared(target)

            val staging = ownedChild(storage, ".$modelDigest-staging-${UUID.randomUUID()}")
            if (!staging.mkdir()) throw IOException("Cannot create speech model staging directory")
            try {
                for (spec in specs) copyAsset(spec, File(staging, spec.fileName))
                if (!isValidDirectory(staging)) {
                    throw IOException("Local speech model integrity check failed")
                }
                currentCoroutineContext().ensureActive()
                publish(staging, target, storage)
                prepared(target)
            } finally {
                deleteOwnedFlatDirectory(staging, storage)
            }
        }
    }

    private suspend fun copyAsset(spec: LocalSpeechModelFileSpec, outputFile: File) {
        currentCoroutineContext().ensureActive()
        openAsset(spec.assetPath).use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    currentCoroutineContext().ensureActive()
                    copied += count
                    if (copied > spec.bytes) throw IOException("Speech model asset size mismatch: ${spec.fileName}")
                    output.write(buffer, 0, count)
                }
                if (copied != spec.bytes) throw IOException("Speech model asset size mismatch: ${spec.fileName}")
                output.fd.sync()
            }
        }
    }

    private suspend fun isValidDirectory(directory: File): Boolean {
        currentCoroutineContext().ensureActive()
        if (!directory.isDirectory) return false
        if (directory.list()?.toSet() != FILE_NAMES) return false
        for (spec in specs) {
            val file = File(directory, spec.fileName)
            // Reject links to files outside this set before opening or deleting anything.
            if (file.canonicalFile != file.absoluteFile || !file.isFile || file.length() != spec.bytes) return false
            val digest = MessageDigest.getInstance("SHA-256")
            var readBytes = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    currentCoroutineContext().ensureActive()
                    readBytes += count
                    digest.update(buffer, 0, count)
                }
            }
            if (readBytes != spec.bytes || digest.digest().toHex() != spec.sha256) return false
        }
        return true
    }

    /** No suspension in commit: cancellation cannot leave a half-published set. */
    private fun publish(staging: File, target: File, storage: File) {
        val previous = ownedChild(storage, ".$modelDigest-replaced-${UUID.randomUUID()}")
        val hadPrevious = target.exists()
        if (hadPrevious && !target.renameTo(previous)) {
            throw IOException("Cannot move invalid speech model cache")
        }
        if (!staging.renameTo(target)) {
            if (hadPrevious && !previous.renameTo(target)) {
                throw IOException("Cannot publish speech model; previous cache retained at ${previous.name}")
            }
            throw IOException("Cannot publish speech model")
        }
        // An unexpected nested directory or link is retained, never traversed during cleanup.
        deleteOwnedFlatDirectory(previous, storage)
    }

    private fun prepared(directory: File) = PreparedLocalSpeechModelFiles(
        directory = directory,
        encoder = File(directory, "encoder.int8.onnx"),
        decoder = File(directory, "decoder.int8.onnx"),
        joiner = File(directory, "joiner.int8.onnx"),
        tokens = File(directory, "tokens.txt"),
        bpeVocab = File(directory, "bpe.vocab"),
        vad = File(directory, "silero_vad.onnx"),
    )

    private fun ownedChild(parent: File, name: String): File {
        val file = File(parent, name).absoluteFile
        if (file.canonicalFile != file || file.parentFile != parent) {
            throw IOException("Speech model path is outside its storage directory")
        }
        return file
    }

    private fun deleteOwnedFlatDirectory(directory: File, storage: File) {
        if (!directory.exists()) return
        if (directory.canonicalFile != directory.absoluteFile || directory.parentFile != storage) return
        if (!directory.isDirectory) {
            directory.delete()
            return
        }
        val files = directory.listFiles() ?: return
        if (files.any { !it.isFile || it.canonicalFile != it.absoluteFile }) return
        files.forEach { it.delete() }
        directory.delete()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private val preparationMutex = Mutex()
        private val FILE_NAMES = setOf(
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx",
            "tokens.txt", "bpe.vocab", "silero_vad.onnx",
        )

        // docs/INVESTIGACION/F3.06-stt-local/recursos-manifest.json (2026-10-12).
        private const val MODEL_DIGEST = "d6d29ffd560c366da1b177b71e79d8733a9a9910dd1ace282d9bfae94e056210"
        private const val ASSET_ROOT = "stt/nemo-es-rnnt-int8"
        private val MODEL_FILES = listOf(
            LocalSpeechModelFileSpec("$ASSET_ROOT/encoder.int8.onnx", "encoder.int8.onnx", 131113475,
                "17bde3c5005f7a9d0614336ea13db7d2c3c1c4efcacfa380e962b36d768b907e"),
            LocalSpeechModelFileSpec("$ASSET_ROOT/decoder.int8.onnx", "decoder.int8.onnx", 3955863,
                "e759e0c04faa31526c34a96927bd8c69574e9b5e83360559b4f924be98f89f0e"),
            LocalSpeechModelFileSpec("$ASSET_ROOT/joiner.int8.onnx", "joiner.int8.onnx", 1408182,
                "78f941bcda8d7e3d43b89a8fd805a8e77e99d06bde4c6ceedc9ec423ee4d04bd"),
            LocalSpeechModelFileSpec("$ASSET_ROOT/tokens.txt", "tokens.txt", 10871,
                "6191b4853e3654f053c42f6fd184ca53b402987aefdd6ff6baa68034d803ee85"),
            LocalSpeechModelFileSpec("$ASSET_ROOT/bpe.vocab", "bpe.vocab", 11981,
                "99fa642e935e8eb5ce835cf1e4fbd238afafb10f6cbcbbe3f1511650124f5597"),
            LocalSpeechModelFileSpec("stt/silero_vad.onnx", "silero_vad.onnx", 643854,
                "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
        )
    }
}
