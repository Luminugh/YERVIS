package com.yezvi.assistant.data.speech

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Opt-in causal timing probe, not a speed/quality acceptance test. The exact same authorized
 * PCM is decoded with different beam limits/polling. Only a temporary model copy is changed.
 * Hypotheses are written privately, never to instrumentation stdout, logcat or Git.
 */
@RunWith(AndroidJUnit4::class)
class VoskPerformanceDiagnosticTest {
    @Test
    fun separatesNativeDecodingFromJsonPollingAcrossFourProfilesOffline() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("voskPerformance") == "true")
        val pcmPath = requireNotNull(args.getString("performancePcmPath")) { "Explicit authorized PCM path required" }
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse("Performance diagnosis must execute outside main", Looper.getMainLooper().isCurrentThread)
        assertOffline(context)
        val preparationStart = System.nanoTime()
        val verifiedSource = VoskModelFiles(context).prepare()
        val preparationNs = System.nanoTime() - preparationStart
        val originalConfigurationFile = File(verifiedSource, "conf/model.conf")
        val originalConfiguration = originalConfigurationFile.readText()
        val originalConfigurationHash = sha256(originalConfigurationFile.readBytes())
        val originalParameters = parameters(originalConfiguration)
        // The pinned es-0.42 file actually ships beam 11, not an assumed beam 13.
        assertEquals("11.0", originalParameters["beam"])
        assertEquals("4000", originalParameters["max-active"])
        assertEquals("4.0", originalParameters["lattice-beam"])
        val profiles = listOf(
            Profile("original-partial100ms", emptyMap(), 1_600),
            Profile("original-no-partial", emptyMap(), null),
            Profile("beam10-max3000-lattice3-partial100ms", mapOf("beam" to "10.0", "max-active" to "3000", "lattice-beam" to "3.0"), 1_600),
            Profile("beam8-max2000-lattice2-partial100ms", mapOf("beam" to "8.0", "max-active" to "2000", "lattice-beam" to "2.0"), 1_600),
        )
        val privateOutput = File(context.noBackupFilesDir, "vosk-performance.json")
        val report = JSONObject()
            .put("runtime", "vosk-android-0.3.75")
            .put("fixture", "authorized-private-recording")
            .put("pcmFormat", "signed16-le-mono")
            .put("sampleRate", SAMPLE_RATE)
            .put("blockSamples", BLOCK_SAMPLES)
            .put("modelPreparationMs", milliseconds(preparationNs))
            .put("sourceConfigurationSha256", originalConfigurationHash)
            .put("originalConfiguration", originalConfiguration)
            .put("metricScope", "One sequential run/profile; native accept vs result/partial JSON; excludes copy/load/cleanup from inference")
            .put("runs", JSONArray())
        val cacheParent = context.cacheDir.canonicalFile
        val temporary = File(cacheParent, "vosk-performance-${UUID.randomUUID()}").absoluteFile
        check(temporary.parentFile == cacheParent && temporary.canonicalFile == temporary)
        check(temporary.mkdir()) { "Cannot create owned diagnostic model directory" }
        var pcm: ShortArray? = null
        try {
            val copyStart = System.nanoTime()
            copyVerifiedModel(verifiedSource, temporary)
            report.put("temporaryCopyMs", milliseconds(System.nanoTime() - copyStart))
            val inputPcm = readAuthorizedPcm(File(pcmPath))
            pcm = inputPcm
            report.put("samples", inputPcm.size).put("durationMs", inputPcm.size / 16.0)
            LibVosk.vosk_set_log_level(-1)
            for (profile in profiles) {
                assertOffline(context)
                val configFile = File(temporary, "conf/model.conf")
                // Restore exact baseline before applying each independent profile.
                configFile.writeText(originalConfiguration)
                val configuration = applyChanges(originalConfiguration, profile.changes)
                configFile.writeText(configuration)
                val run = decodeProfile(temporary, inputPcm, profile)
                    .put("configuration", JSONObject(parameters(configuration)))
                    .put("configurationChanges", JSONObject(profile.changes))
                report.getJSONArray("runs").put(run)
                privateOutput.writeText(report.toString())
                assertTrue("A diagnostic profile returned no recognized words", run.getString("text").isNotBlank())
                assertOffline(context)
            }
            assertEquals("The verified original model config must remain unchanged", originalConfigurationHash,
                sha256(originalConfigurationFile.readBytes()))
            assertEquals(4, report.getJSONArray("runs").length())
        } finally {
            pcm?.fill(0)
            // Delete only this test's freshly created, direct cache child; never touch no_backup.
            check(temporary.parentFile == cacheParent && temporary.canonicalFile == temporary &&
                temporary.name.startsWith("vosk-performance-")) { "Unsafe temporary cleanup path" }
            check(temporary.deleteRecursively()) { "Temporary diagnostic model cleanup failed" }
        }
    }

    private fun decodeProfile(directory: File, pcm: ShortArray, profile: Profile): JSONObject {
        val report = JSONObject().put("profile", profile.name)
            .put("partialPollingSamples", profile.partialPollingSamples ?: JSONObject.NULL)
        var model: Model? = null
        var recognizer: Recognizer? = null
        val buffer = ShortArray(BLOCK_SAMPLES)
        try {
            val loadStart = System.nanoTime()
            val activeModel = Model(directory.absolutePath)
            model = activeModel
            val activeRecognizer = Recognizer(activeModel, SAMPLE_RATE.toFloat())
            recognizer = activeRecognizer
            report.put("loadMs", milliseconds(System.nanoTime() - loadStart))
            var acceptedNativeNs = 0L
            var partialResultJsonNs = 0L
            var endpointResultJsonNs = 0L
            var partialPolls = 0
            var endpoints = 0
            var offset = 0
            var nextPartialAt = profile.partialPollingSamples ?: Int.MAX_VALUE
            val completedParts = mutableListOf<String>()
            val inferenceStart = System.nanoTime()
            while (offset < pcm.size) {
                val count = minOf(buffer.size, pcm.size - offset)
                pcm.copyInto(buffer, 0, offset, offset + count)
                val nativeStart = System.nanoTime()
                val endpoint = activeRecognizer.acceptWaveForm(buffer, count)
                acceptedNativeNs += System.nanoTime() - nativeStart
                offset += count
                if (endpoint) {
                    val jsonStart = System.nanoTime()
                    val text = JSONObject(activeRecognizer.result).getString("text").trim()
                    endpointResultJsonNs += System.nanoTime() - jsonStart
                    endpoints++
                    if (text.isNotEmpty()) completedParts += text
                } else if (offset >= nextPartialAt) {
                    val jsonStart = System.nanoTime()
                    JSONObject(activeRecognizer.partialResult).getString("partial")
                    partialResultJsonNs += System.nanoTime() - jsonStart
                    partialPolls++
                }
                profile.partialPollingSamples?.let { interval ->
                    while (nextPartialAt <= offset) nextPartialAt += interval
                }
            }
            val finalizeStart = System.nanoTime()
            val finalJson = activeRecognizer.finalResult
            val finalizeNativeNs = System.nanoTime() - finalizeStart
            val finalJsonStart = System.nanoTime()
            val tail = JSONObject(finalJson).getString("text").trim()
            val finalJsonParseNs = System.nanoTime() - finalJsonStart
            if (tail.isNotEmpty()) completedParts += tail
            val text = completedParts.joinToString(" ")
            val inferenceNs = System.nanoTime() - inferenceStart
            return report.put("text", text)
                .put("acceptWaveFormNativeMs", milliseconds(acceptedNativeNs))
                .put("partialJsonMs", milliseconds(partialResultJsonNs))
                .put("endpointJsonMs", milliseconds(endpointResultJsonNs))
                .put("partialResultJsonMs", milliseconds(partialResultJsonNs + endpointResultJsonNs + finalJsonParseNs))
                .put("finalizeNativeMs", milliseconds(finalizeNativeNs))
                .put("finalJsonParseMs", milliseconds(finalJsonParseNs))
                .put("totalInferenceMs", milliseconds(inferenceNs))
                .put("realTimeFactor", inferenceNs / 1_000_000_000.0 / (pcm.size.toDouble() / SAMPLE_RATE))
                .put("partialPolls", partialPolls).put("endpoints", endpoints)
        } finally {
            buffer.fill(0)
            try { recognizer?.close() } finally { model?.close() }
        }
    }

    private fun copyVerifiedModel(source: File, destination: File) {
        val root = source.canonicalFile
        for (entry in source.walkTopDown()) {
            check(entry.canonicalFile == entry.absoluteFile && entry.toPath().startsWith(root.toPath())) {
                "Unexpected model path while making diagnostic copy"
            }
            if (entry == source) continue
            val target = File(destination, entry.relativeTo(source).path)
            if (entry.isDirectory) check(target.mkdir()) { "Cannot create diagnostic model subdirectory" }
            else entry.copyTo(target, overwrite = false)
        }
    }

    private fun applyChanges(original: String, changes: Map<String, String>): String {
        var changed = original
        for ((key, value) in changes) {
            val line = Regex("(?m)^--${Regex.escape(key)}=[^\\r\\n]+")
            check(line.findAll(changed).count() == 1) { "Configuration option is absent or duplicated" }
            changed = line.replace(changed, "--$key=$value")
        }
        return changed
    }

    private fun parameters(configuration: String): Map<String, String> = configuration.lineSequence()
        .map(String::trim).filter { it.startsWith("--") && '=' in it }
        .associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=') }

    private fun readAuthorizedPcm(file: File): ShortArray {
        check(file.isFile && file.length() in 2L..32_000_000L && file.length() % 2 == 0L) { "Invalid authorized PCM input" }
        val bytes = file.readBytes()
        try {
            val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return ShortArray(bytes.size / 2) { view.short }
        } finally { bytes.fill(0) }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun milliseconds(nanoseconds: Long) = nanoseconds / 1_000_000.0

    private fun assertOffline(context: Context) {
        assertEquals(1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on", 1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "mobile_data", 1))
    }

    private data class Profile(val name: String, val changes: Map<String, String>, val partialPollingSamples: Int?)

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BLOCK_SAMPLES = 320
    }
}
