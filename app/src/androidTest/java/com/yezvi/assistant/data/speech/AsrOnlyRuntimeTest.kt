package com.yezvi.assistant.data.speech

import android.content.Context
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineStream
import com.yezvi.assistant.developer.WordUncertaintyAnalysis
import com.yezvi.assistant.developer.WordUncertaintySession
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextState
import com.yezvi.assistant.presentation.capture.VoiceCaptureSessionController
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real ASR-only JNI/ORT and VAD checks over licensed PCM. No microphone or cloud is used. */
@RunWith(AndroidJUnit4::class)
class AsrOnlyRuntimeTest {
    @Test
    fun packagedNativeExportsContainAsrVadAndNoTtsOrDiarization() = runBlocking(Dispatchers.IO) {
        val context = appContext()
        assertOffline(context)
        // Audit the actual APK ELF dynamic exports; unused Java API declarations prove nothing.
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            val entries = apk.entries().asSequence().map { it.name }.toSet()
            val abi = Build.SUPPORTED_ABIS.firstOrNull { "lib/$it/libsherpa-onnx-jni.so" in entries }
            checkNotNull(abi) { "ASR-only JNI is absent for this device ABI" }
            val nativeEntries = entries.filter { it.startsWith("lib/$abi/") && it.endsWith(".so") }
            assertTrue("Separate ONNX Runtime is absent", "lib/$abi/libonnxruntime.so" in entries)
            assertTrue("An excluded native component is still packaged", nativeEntries.none { FORBIDDEN.containsMatchIn(it) })
            val jniBytes = apk.getInputStream(apk.getEntry("lib/$abi/libsherpa-onnx-jni.so")).use { it.readBytes() }
            val ortBytes = apk.getInputStream(apk.getEntry("lib/$abi/libonnxruntime.so")).use { it.readBytes() }
            try {
                val jni = Elf64Audit(jniBytes)
                val exports = jni.exports()
                assertTrue("Offline recognizer JNI export is absent", "Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_newFromFile" in exports)
                assertTrue("Silero VAD JNI export is absent", "Java_com_k2fsa_sherpa_onnx_Vad_compute" in exports)
                assertTrue("Excluded TTS/diarization exports remain", exports.none { FORBIDDEN.containsMatchIn(it) })
                assertTrue("JNI must use the separate ORT shared library", "libonnxruntime.so" in jni.neededLibraries())
                assertTrue("ORT C API export is absent", "OrtGetApiBase" in Elf64Audit(ortBytes).exports())
            } finally {
                jniBytes.fill(0)
                ortBytes.fill(0)
            }
        }
        // This checks exports and packaging, not a complete source/license or static-link audit.
    }

    @Test
    fun realFastConformerModelDecodesPublicSpanishWithAsrOnlyJniOffline() = runBlocking(Dispatchers.Default) {
        val context = appContext()
        assertOffline(context)
        assertFalse("JNI inference must not run on main", Looper.getMainLooper().isCurrentThread)
        val pcm = readPublicFixture("fleurs-es_419-san_pedro.wav")
        try {
            val files = LocalSpeechModelFiles(context).prepare()
            val run = greedyRun(files, pcm, "fleurs-es_419-san_pedro.wav", 0)
            assertTrue("Real JNI returned an empty local transcript", run.getString("text").isNotBlank())
        } finally { pcm.fill(0) }
        assertOffline(context)
    }

    @Test
    fun publicPcmRunsThroughExistingControllerSileroAndSherpaOffline() = runBlocking(Dispatchers.Default) {
        val context = appContext()
        assertOffline(context)
        val pcm = readPublicFixture("fleurs-es_419-san_pedro.wav")
        val input = MemoryPcmInput(pcm)
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var realVad: SileroPcmSegmenter? = null
        var realAsr: SherpaOfflineRecognizerAdapter? = null
        val inspectScores = InstrumentationRegistry.getArguments().getString("confidenceObserver") == "true"
        val wordSession = if (inspectScores) WordUncertaintySession("native-pipeline-test/no-threshold") else null
        val analysis = AtomicReference<WordUncertaintyAnalysis?>()
        val engine = SherpaOfflineSpeechToTextEngine(
            workerScope = ownerScope,
            hasRecordAudioPermission = { true }, // Memory input never acquires the microphone.
            captureFactory = { LocalPcmCaptureLease(input) },
            segmenterFactory = { SileroPcmSegmenter.create(context).also { realVad = it } },
            recognizerFactory = {
                SherpaOfflineRecognizerAdapter.create(context,
                    tokenScoreReader = if (!inspectScores) null else { ptr -> SherpaTokenScores.readTokenLogProbabilities(ptr) },
                ).also { realAsr = it }
            },
            workerDispatcher = Dispatchers.IO,
            onSegmentRecognition = if (wordSession == null) null else { segment ->
                assertFalse("Word grouping must run off main", Looper.getMainLooper().isCurrentThread)
                analysis.set(wordSession.append(segment.sessionId, segment.textBefore, segment.recognition.text,
                    segment.recognition.tokens, segment.recognition.timestamps,
                    segment.recognition.tokenLogProbabilities, segment.recognition.audioDurationMs))
            },
        )
        val controller = VoiceCaptureSessionController(engine, ownerScope, { true }, SpeechToTextRequest("es-PE"))
        try {
            withTimeout(90_000) { controller.start() }
            assertFalse("Unexpected terminal setup failure", controller.state.value is SpeechToTextState.Error)
            withTimeout(90_000) { input.allSamplesRead.await() }
            controller.stop()
            val state = withTimeout(90_000) {
                controller.state.first { it is SpeechToTextState.Result || it is SpeechToTextState.Error || it == SpeechToTextState.Cancelled }
            }
            assertTrue("Expected final local pipeline result", state is SpeechToTextState.Result)
            assertTrue("Pipeline returned empty text", (state as SpeechToTextState.Result).transcript.text.isNotBlank())
            if (inspectScores) {
                val metadata = checkNotNull(analysis.get()) { "The real pipeline did not publish token scores" }
                assertEquals(null, metadata.unavailableReason)
                assertEquals(state.transcript.text, metadata.text)
                assertTrue(metadata.words.isNotEmpty())
                assertTrue(metadata.reviewWordIndices.isEmpty())
                assertFalse(metadata.hasValidatedThreshold)
            }
            assertEquals("Capture must close before terminal publication", 1, input.closes)
            assertTrue("Consumed PCM must be cleared", pcm.all { it == 0.toShort() })
            assertTrue("VAD must close before terminal publication", rejectsClosed { checkNotNull(realVad).finish() })
            val probe = floatArrayOf(0f)
            try {
                assertTrue("ASR must close before terminal publication", rejectsClosed { checkNotNull(realAsr).transcribe(probe) })
            } finally { probe.fill(0f) }
            assertOffline(context)
        } finally {
            try {
                withContext(NonCancellable) {
                    try { controller.close() } finally {
                        try { realVad?.close() } finally {
                            try { realAsr?.close() } finally { input.closeIfNeeded(); pcm.fill(0) }
                        }
                    }
                }
            } finally { ownerScope.cancel() }
        }
    }

    @Test
    fun realSileroVadDoesNotInventSpeechFromControlledSilenceOffline() = runBlocking(Dispatchers.Default) {
        val context = appContext()
        assertOffline(context)
        val segmenter = SileroPcmSegmenter.create(context)
        val silence = ShortArray(320)
        val segments = mutableListOf<PcmSpeechSegment>()
        try {
            repeat(100) { segments += segmenter.accept(silence) }
            segments += segmenter.finish()
            assertTrue("Controlled silence must not produce VAD speech segments", segments.isEmpty())
            assertOffline(context)
        } finally {
            segments.forEach { it.samples.fill(0f) }
            silence.fill(0)
            segmenter.close()
        }
        assertEquals(0, segmenter.bufferedSampleCount())
    }

    /** Opt in with asrOnlyComparison=true; never prints private hypotheses. */
    @Test
    fun benchmarkSamePublicAndAuthorizedPrivatePcmOnAsrOnlyRuntime() = runBlocking(Dispatchers.Default) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("asrOnlyComparison") == "true")
        val context = appContext()
        assertOffline(context)
        val preparationStart = System.nanoTime()
        val files = LocalSpeechModelFiles(context).prepare()
        val report = JSONObject().put("sampleRate", SAMPLE_RATE).put("runtime", "sherpa-onnx-1.13.8-asr-only-ort-1.28.2")
            .put("voskPreparationMs", 0.0).put("voskExecuted", false)
            .put("sherpaPreparationMs", elapsed(preparationStart)).put("runs", JSONArray())
        val output = File(context.noBackupFilesDir, "asr-only-comparison.json")
        val fixtures = fixtureManifest().getJSONArray("samples")
        assertEquals("Expected the existing four licensed fixtures", 4, fixtures.length())
        suspend fun compare(id: String, pcm: ShortArray) {
            try {
                repeat(3) { round ->
                    assertOffline(context)
                    val run = greedyRun(files, pcm, id, round)
                    report.getJSONArray("runs").put(run)
                    output.writeText(report.toString()) // Private no_backup; no stdout/logcat.
                    assertTrue("ASR-only benchmark returned an empty transcript", run.getString("text").isNotBlank())
                }
            } finally { pcm.fill(0) }
        }
        for (index in 0 until fixtures.length()) {
            val id = fixtures.getJSONObject(index).getString("path")
            compare(id, readPublicFixture(id))
        }
        args.getString("comparisonPcmPath")?.let { path -> compare("authorized-private-recording", readPrivatePcm(File(path))) }
        assertEquals(if (args.containsKey("comparisonPcmPath")) 15 else 12, report.getJSONArray("runs").length())
        assertOffline(context)
    }

    private fun greedyRun(files: PreparedLocalSpeechModelFiles, pcm: ShortArray, fixture: String, round: Int): JSONObject {
        assertFalse("Native ASR must not execute on main", Looper.getMainLooper().isCurrentThread)
        val config = SherpaOfflineRecognizerAdapter.configurationFor(files, 0f).also { it.decodingMethod = "greedy_search" }
        val loadStart = System.nanoTime()
        val recognizer = OfflineRecognizer(config = config)
        val loadMs = elapsed(loadStart)
        var stream: OfflineStream? = null
        var floats: FloatArray? = null
        try {
            val activeStream = recognizer.createStream()
            stream = activeStream
            val input = FloatArray(pcm.size) { pcm[it] / 32768f }
            floats = input
            val started = System.nanoTime()
            activeStream.acceptWaveform(input, SAMPLE_RATE)
            recognizer.decode(activeStream)
            val inferenceMs = elapsed(started) // Matches the original A/B timing boundary.
            val text = recognizer.getResult(activeStream).text
            return JSONObject().put("fixture", fixture).put("round", round).put("engine", "rnnt-greedy")
                .put("samples", pcm.size).put("durationMs", pcm.size / 16.0).put("loadMs", loadMs)
                .put("inferenceMs", inferenceMs).put("text", text)
        } finally {
            floats?.fill(0f)
            try { stream?.release() } finally { recognizer.release() }
        }
    }

    private fun fixtureManifest(): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().context
        return JSONObject(context.assets.open("stt-stage2/audio-manifest.json").bufferedReader().use { it.readText() }).also {
            assertEquals("CC-BY-4.0", it.getString("license"))
            assertEquals("human_read_speech", it.getString("speechType"))
        }
    }

    private fun readPublicFixture(name: String): ShortArray {
        val manifest = fixtureManifest().getJSONArray("samples")
        val spec = (0 until manifest.length()).map(manifest::getJSONObject).single { it.getString("path") == name }
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open("stt-stage2/$name").use { it.readBytes() }
        try {
            assertEquals("Fixture integrity mismatch", spec.getString("sha256"), sha256(bytes))
            val wave = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WAVEfmt ", bytes.copyOfRange(8, 16).toString(Charsets.US_ASCII))
            assertEquals(16, wave.getInt(16)); assertEquals(1, wave.getShort(20).toInt())
            assertEquals(1, wave.getShort(22).toInt()); assertEquals(SAMPLE_RATE, wave.getInt(24))
            assertEquals(16, wave.getShort(34).toInt())
            assertEquals("data", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
            val count = spec.getInt("numSamples")
            assertEquals(count * 2, wave.getInt(40)); assertEquals(44 + count * 2, bytes.size)
            wave.position(44)
            return ShortArray(count) { wave.short }
        } finally { bytes.fill(0) }
    }

    private fun readPrivatePcm(file: File): ShortArray {
        check(file.isFile && file.length() in 2L..32_000_000L && file.length() % 2 == 0L) { "Invalid authorized PCM file" }
        val bytes = file.readBytes()
        try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return ShortArray(bytes.size / 2) { buffer.short }
        } finally { bytes.fill(0) }
    }

    private suspend fun rejectsClosed(block: suspend () -> Unit): Boolean = try { block(); false } catch (_: IllegalStateException) { true }
    private fun appContext(): Context = ApplicationProvider.getApplicationContext()
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000.0
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun assertOffline(context: Context) {
        assertEquals("Airplane mode must be enabled", 1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0))
        assertEquals("Wi-Fi must be disabled", 0, Settings.Global.getInt(context.contentResolver, "wifi_on", 1))
        assertEquals("Mobile data must be disabled", 0, Settings.Global.getInt(context.contentResolver, "mobile_data", 1))
    }

    private class MemoryPcmInput(private val pcm: ShortArray) : LocalPcmInput {
        val allSamplesRead = CompletableDeferred<Unit>()
        private val stopped = CompletableDeferred<Unit>()
        private var cursor = 0
        @Volatile var closes = 0
        override suspend fun open() = Unit
        override suspend fun start() = Unit
        override suspend fun read(samples: ShortArray): Int {
            assertFalse("PCM worker must not execute on main", Looper.getMainLooper().isCurrentThread)
            if (cursor < pcm.size) {
                val count = minOf(samples.size, pcm.size - cursor)
                pcm.copyInto(samples, 0, cursor, cursor + count)
                cursor += count
                if (cursor == pcm.size) allSamplesRead.complete(Unit)
                return count
            }
            stopped.await()
            return 0
        }
        override suspend fun stop() { stopped.complete(Unit) }
        override suspend fun close() { closes++; stopped.complete(Unit); pcm.fill(0) }
        fun closeIfNeeded() { if (closes == 0) { closes++; stopped.complete(Unit); pcm.fill(0) } }
    }

    /** ELF64 little-endian .dynsym/DT_NEEDED reader for the tested x86_64/ARM64 libraries. */
    private class Elf64Audit(private val bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val sections: List<Section>
        init {
            check(bytes.size >= 64 && bytes[0] == 0x7f.toByte() && bytes.copyOfRange(1, 4).toString(Charsets.US_ASCII) == "ELF") { "Expected ELF native library" }
            check(bytes[4] == 2.toByte() && bytes[5] == 1.toByte()) { "Audit supports ELF64 little endian only" }
            val offset = checkedOffset(buffer.getLong(40))
            val entryBytes = buffer.getShort(58).toInt() and 0xffff
            val count = buffer.getShort(60).toInt() and 0xffff
            check(entryBytes >= 64 && count > 0 && offset.toLong() + count.toLong() * entryBytes <= bytes.size)
            sections = List(count) { index ->
                val base = offset + index * entryBytes
                Section(buffer.getInt(base + 4), checkedOffset(buffer.getLong(base + 24)), checkedOffset(buffer.getLong(base + 32)), buffer.getInt(base + 40), checkedOffset(buffer.getLong(base + 56)))
            }
        }

        fun exports(): Set<String> {
            val symbols = mutableSetOf<String>()
            for (section in sections.filter { it.type == 11 }) { // SHT_DYNSYM.
                check(section.entryBytes >= 24 && section.link in sections.indices)
                val strings = sections[section.link]
                for (index in 0 until section.size / section.entryBytes) {
                    val base = section.offset + index * section.entryBytes
                    check(base.toLong() + 24 <= bytes.size)
                    val binding = (bytes[base + 4].toInt() and 0xff) ushr 4
                    val visibility = bytes[base + 5].toInt() and 3
                    val defined = (buffer.getShort(base + 6).toInt() and 0xffff) != 0
                    if (defined && binding in 1..2 && visibility in listOf(0, 3)) symbols += string(strings, buffer.getInt(base))
                }
            }
            check(symbols.isNotEmpty()) { "No exported dynamic symbols to audit" }
            return symbols
        }

        fun neededLibraries(): Set<String> {
            val needed = mutableSetOf<String>()
            for (section in sections.filter { it.type == 6 }) { // SHT_DYNAMIC.
                check(section.entryBytes >= 16 && section.link in sections.indices)
                for (index in 0 until section.size / section.entryBytes) {
                    val base = section.offset + index * section.entryBytes
                    check(base.toLong() + 16 <= bytes.size)
                    if (buffer.getLong(base) == 1L) needed += string(sections[section.link], checkedOffset(buffer.getLong(base + 8)))
                }
            }
            return needed
        }

        private fun string(table: Section, relative: Int): String {
            check(relative in 0 until table.size && table.offset.toLong() + table.size <= bytes.size)
            var end = table.offset + relative
            while (end < table.offset + table.size && bytes[end] != 0.toByte()) end++
            check(end < table.offset + table.size)
            return String(bytes, table.offset + relative, end - table.offset - relative, Charsets.UTF_8)
        }

        private fun checkedOffset(value: Long): Int { check(value in 0..Int.MAX_VALUE.toLong()); return value.toInt() }
        private data class Section(val type: Int, val offset: Int, val size: Int, val link: Int, val entryBytes: Int)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private val FORBIDDEN = Regex("tts|diarization|espeak|ucd[-_]|piper[-_]phon", RegexOption.IGNORE_CASE)
    }
}
