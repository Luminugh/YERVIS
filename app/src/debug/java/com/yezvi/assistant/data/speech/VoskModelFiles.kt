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
import org.json.JSONObject

internal data class VoskModelFileSpec(val path: String, val bytes: Long, val sha256: String)

/** Copies only the bundled, pinned Spanish model into private no-backup storage, without network. */
internal class VoskModelFiles internal constructor(
    private val noBackupDirectory: File,
    private val openAsset: (String) -> InputStream,
    specs: List<VoskModelFileSpec>,
    private val modelDigest: String = MODEL_DIGEST,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val files = specs.toList()

    constructor(context: Context) : this(
        context.noBackupFilesDir,
        { path -> context.assets.open(path) },
        readSpecs(context),
    )

    init {
        require(modelDigest.matches(Regex("[0-9a-f]{64}")))
        require(files.isNotEmpty() && files.map { it.path }.distinct().size == files.size)
        require(files.all { spec ->
            spec.path.split('/').all { it.matches(Regex("[a-zA-Z0-9_.-]+")) && it != "." && it != ".." } &&
                spec.bytes > 0 && spec.sha256.matches(Regex("[0-9a-f]{64}"))
        })
    }

    suspend fun prepare(): File = withContext(ioDispatcher) {
        preparationMutex.withLock {
            val root = child(noBackupDirectory.canonicalFile, "stt-vosk")
            if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create Vosk storage")
            val target = child(root, modelDigest)
            if (valid(target)) return@withLock target
            val staging = child(root, ".$modelDigest-staging-${UUID.randomUUID()}")
            if (!staging.mkdir()) throw IOException("Cannot create Vosk staging directory")
            try {
                for (spec in files) copy(spec, staging)
                if (!valid(staging)) throw IOException("Vosk model integrity failure")
                currentCoroutineContext().ensureActive()
                val previous = child(root, ".$modelDigest-old-${UUID.randomUUID()}")
                val hadPrevious = target.exists()
                if (hadPrevious && !target.renameTo(previous)) throw IOException("Cannot retain old Vosk model")
                if (!staging.renameTo(target)) {
                    if (hadPrevious && !previous.renameTo(target)) throw IOException("Vosk model rollback failed")
                    throw IOException("Cannot publish Vosk model")
                }
                deleteOwned(previous, root)
                target
            } finally {
                deleteOwned(staging, root)
            }
        }
    }

    private suspend fun copy(spec: VoskModelFileSpec, staging: File) {
        val outputFile = child(staging, spec.path)
        val outputParent = checkNotNull(outputFile.parentFile)
        if (!outputParent.isDirectory && !outputParent.mkdirs()) {
            throw IOException("Cannot create Vosk model directory")
        }
        openAsset("$ASSET_ROOT/${spec.path}").use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > spec.bytes) throw IOException("Vosk model size mismatch")
                    output.write(buffer, 0, count)
                }
                if (copied != spec.bytes) throw IOException("Vosk model size mismatch")
                output.fd.sync()
            }
        }
    }

    private suspend fun valid(directory: File): Boolean {
        if (!directory.isDirectory || directory.canonicalFile != directory.absoluteFile) return false
        val actual = mutableSetOf<String>()
        fun collect(current: File): Boolean {
            val children = current.listFiles() ?: return false
            for (file in children) {
                if (file.canonicalFile != file.absoluteFile) return false
                if (file.isDirectory) {
                    if (!collect(file)) return false
                } else if (file.isFile) {
                    actual += file.relativeTo(directory).invariantSeparatorsPath
                } else return false
            }
            return true
        }
        if (!collect(directory) || actual != files.map { it.path }.toSet()) return false
        for (spec in files) {
            currentCoroutineContext().ensureActive()
            val file = child(directory, spec.path)
            if (file.length() != spec.bytes) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } != spec.sha256) return false
        }
        return true
    }

    private fun child(parent: File, path: String): File {
        val file = File(parent, path).absoluteFile
        if (file.canonicalFile != file || !file.toPath().startsWith(parent.toPath()) || file == parent) {
            throw IOException("Vosk model path outside private storage")
        }
        return file
    }

    private fun deleteOwned(directory: File, parent: File) {
        if (!directory.exists() || directory.parentFile != parent || directory.canonicalFile != directory.absoluteFile) return
        fun delete(current: File) {
            if (current.canonicalFile != current.absoluteFile) return
            if (current.isDirectory) current.listFiles()?.forEach(::delete)
            current.delete()
        }
        delete(directory)
    }

    companion object {
        private const val MODEL_DIGEST = "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"
        private const val ASSET_ROOT = "stt/vosk-es-0.42"
        private val preparationMutex = Mutex()

        private fun readSpecs(context: Context): List<VoskModelFileSpec> {
            val json = context.assets.open("stt/vosk-resources.json").bufferedReader().use { JSONObject(it.readText()) }
            require(json.getString("modelArchiveSha256") == MODEL_DIGEST)
            val resources = json.getJSONArray("resources")
            return (0 until resources.length()).map { resources.getJSONObject(it) }
                .filter { it.has("modelPath") }
                .map { VoskModelFileSpec(it.getString("modelPath"), it.getLong("bytes"), it.getString("sha256")) }
                .also { require(it.size == 14) }
        }
    }
}
