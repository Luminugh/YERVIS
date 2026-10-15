package com.yezvi.assistant.data.speech

import android.Manifest
import android.util.Log
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.MainActivity
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextState
import com.yezvi.assistant.presentation.capture.VoiceCaptureSessionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/** Live-only microphone acceptance probe. It stores neither PCM nor transcript text. */
@RunWith(AndroidJUnit4::class)
class LiveMicrophoneStage5Test {
    @Test
    fun spokenSpanishPassesThroughAudioRecordSileroAndSherpaWhileOffline() = runBlocking {
        assumeTrue(
            "Manual live-microphone probe requires -e yezviStage5LiveMic true",
            InstrumentationRegistry.getArguments().getString("yezviStage5LiveMic") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue(
            "RECORD_AUDIO must already be granted; this probe does not request permission",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val lifecycleReady = CompletableDeferred<androidx.lifecycle.Lifecycle>()
        scenario.onActivity { lifecycleReady.complete(it.lifecycle) }
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var diagnosticInput: LiveMetricsPcmInput? = null
        val engine = SherpaOfflineSpeechToTextEngine(
            workerScope = ownerScope,
            hasRecordAudioPermission = {
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            },
            captureFactory = {
                val owner = LifecyclePcmCapture.attach(context, lifecycleReady.await(), ownerScope)
                val metrics = LiveMetricsPcmInput(
                    delegate = AudioRecordLocalPcmInput(owner.source),
                    diagnosticVad = SileroPcmSegmenter.create(context),
                )
                diagnosticInput = metrics
                LocalPcmCaptureLease(
                    input = metrics,
                    releaseOwner = { owner.close() },
                    isOwnerActive = { owner.isOwnerActive },
                )
            },
            segmenterFactory = { SileroPcmSegmenter.create(context) },
            recognizerFactory = { SherpaOfflineRecognizerAdapter.create(context) },
            workerDispatcher = Dispatchers.IO,
        )
        val controller = VoiceCaptureSessionController(
            engine = engine,
            scope = ownerScope,
            hasRecordAudioPermission = {
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            },
            request = SpeechToTextRequest("es-PE"),
        )
        try {
            withTimeout(90_000) { controller.start() }
            withTimeout(10_000) { controller.state.first { it is SpeechToTextState.Listening } }
            Log.i(
                LOG_TAG,
                "CAPTURE_OPEN windowMs=$CAPTURE_WINDOW_MS sampleRateHz=${AudioRecordPcmSource.SAMPLE_RATE}; " +
                    "say: Esta es una prueba de voz local, uno dos tres.",
            )
            delay(CAPTURE_WINDOW_MS)
            controller.stop()

            val terminal = withTimeout(90_000) {
                controller.state.first { it is SpeechToTextState.Result || it is SpeechToTextState.Error }
            }
            val metrics = checkNotNull(diagnosticInput).finishDiagnostics()
            val transcript = (terminal as? SpeechToTextState.Result)?.transcript?.text.orEmpty()
            val words = normalize(transcript).split(' ').filter(String::isNotBlank)
            val phraseKeywordsMatched = listOf("prueba", "voz", "local").count(words::contains)
            val terminalSummary = when (terminal) {
                is SpeechToTextState.Result -> "RESULT"
                is SpeechToTextState.Error -> "ERROR:${terminal.cause}"
                else -> terminal::class.simpleName ?: "UNKNOWN"
            }
            Log.i(
                LOG_TAG,
                "SUMMARY offline=true terminal=$terminalSummary durationMs=${metrics.sampleDurationMs} " +
                    "wallMs=${metrics.wallDurationMs} samples=${metrics.sampleCount} " +
                    "nonZero=${metrics.nonZeroSamples} rms=${"%.5f".format(metrics.normalizedRms)} " +
                    "peak=${metrics.peakAbs} vadSegments=${metrics.segmentCount} " +
                    "vadSpeechMs=${metrics.vadSpeechMs} transcriptWords=${words.size} " +
                    "phraseKeywordsMatched=$phraseKeywordsMatched/3 transcriptOmitted=true",
            )
            assertTrue("Expected speech samples from the live microphone", metrics.sampleCount > 0)
            assertTrue("Microphone input was silent", metrics.nonZeroSamples > 0)
            assertTrue("No speech segment was detected by Silero", metrics.segmentCount > 0)
            assertTrue("Sherpa did not return a transcript ($terminalSummary)", terminal is SpeechToTextState.Result)
            assertTrue("Sherpa returned an empty transcript", transcript.isNotBlank())
            assertTrue("Captured audio was shorter than the live capture window", metrics.sampleDurationMs >= 8_000)
            assertEquals("VAD sidecar must release on completion", 0, metrics.bufferedSamplesAfterClose)
        } finally {
            withContext(NonCancellable) { controller.close() }
            diagnosticInput?.closeDiagnostics()
            scenario.close()
            ownerScope.cancel()
        }
    }

    private fun normalize(text: String): String = java.text.Normalizer
        .normalize(text, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    internal data class Metrics(
        val sampleCount: Long,
        val nonZeroSamples: Long,
        val normalizedRms: Double,
        val peakAbs: Int,
        val sampleDurationMs: Long,
        val wallDurationMs: Long,
        val segmentCount: Int,
        val vadSpeechMs: Long,
        val bufferedSamplesAfterClose: Int,
    )

    internal class LiveMetricsPcmInput(
        private val delegate: LocalPcmInput,
        private val diagnosticVad: SileroPcmSegmenter,
    ) : LocalPcmInput {
        private var sampleCount = 0L
        private var nonZeroSamples = 0L
        private var sumSquares = 0.0
        private var peakAbs = 0
        private var startedAtNs = 0L
        private var lastReadAtNs = 0L
        private var segmentCount = 0
        private var vadSpeechSamples = 0L
        private var closedDiagnostics = false

        override suspend fun open() = delegate.open()
        override suspend fun start() = delegate.start()

        override suspend fun read(samples: ShortArray): Int {
            val count = delegate.read(samples)
            if (count > 0) {
                val now = SystemClock.elapsedRealtimeNanos()
                if (startedAtNs == 0L) startedAtNs = now
                lastReadAtNs = now
                sampleCount += count
                for (index in 0 until count) {
                    val value = samples[index].toInt()
                    if (value != 0) nonZeroSamples++
                    val absolute = kotlin.math.abs(value)
                    if (absolute > peakAbs) peakAbs = absolute
                    sumSquares += value.toDouble() * value
                }
                diagnosticVad.accept(samples, length = count).forEach { segment ->
                    segmentCount++
                    vadSpeechSamples += segment.samples.size
                    segment.samples.fill(0f)
                }
            }
            return count
        }

        override suspend fun stop() = delegate.stop()
        override suspend fun close() = delegate.close()

        suspend fun finishDiagnostics(): Metrics {
            if (!closedDiagnostics) {
                diagnosticVad.finish().forEach { segment ->
                    segmentCount++
                    vadSpeechSamples += segment.samples.size
                    segment.samples.fill(0f)
                }
                diagnosticVad.close()
                closedDiagnostics = true
            }
            val rms = if (sampleCount == 0L) 0.0 else sqrt(sumSquares / sampleCount) / Short.MAX_VALUE
            val sampleMs = sampleCount * 1_000L / AudioRecordPcmSource.SAMPLE_RATE
            val wallMs = if (startedAtNs == 0L) 0L else (lastReadAtNs - startedAtNs) / 1_000_000L
            return Metrics(
                sampleCount = sampleCount,
                nonZeroSamples = nonZeroSamples,
                normalizedRms = rms,
                peakAbs = peakAbs,
                sampleDurationMs = sampleMs,
                wallDurationMs = wallMs,
                segmentCount = segmentCount,
                vadSpeechMs = vadSpeechSamples * 1_000L / AudioRecordPcmSource.SAMPLE_RATE,
                bufferedSamplesAfterClose = diagnosticVad.bufferedSampleCount(),
            )
        }

        suspend fun closeDiagnostics() {
            if (!closedDiagnostics) {
                diagnosticVad.close()
                closedDiagnostics = true
            }
        }
    }

    private companion object {
        const val CAPTURE_WINDOW_MS = 12_000L
        const val LOG_TAG = "F3_06_STAGE5"
    }
}
