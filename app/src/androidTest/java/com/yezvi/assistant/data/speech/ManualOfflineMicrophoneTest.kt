package com.yezvi.assistant.data.speech

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.MainActivity
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextState
import com.yezvi.assistant.presentation.capture.VoiceCaptureSessionController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Temporary instrumentation controls. Never changes production UI or records audio to disk. */
@RunWith(AndroidJUnit4::class)
class ManualOfflineMicrophoneTest {
    @Test
    fun preparesNativeRuntimeWithoutOpeningMicrophone() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualMicPreflight") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = LocalSpeechModelFiles(context).prepare()
        assertTrue(files.encoder.isFile && files.vad.isFile)
        val vad = SileroPcmSegmenter.create(context)
        try {
            val recognizer = SherpaOfflineRecognizerAdapter.create(context)
            try { assertTrue(recognizer.initializationMs > 0) } finally { recognizer.close() }
        } finally { vad.close() }
    }

    @Test
    fun userControlsRealOfflineMicrophone() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualLiveMic") == "true")
        assumeTrue("Manual controls target the API35 AVD", Build.VERSION.SDK_INT >= 33)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val references = withContext(Dispatchers.IO) {
            InstrumentationRegistry.getArguments().getString("manualReferencePath")?.let { path ->
                val values = JSONArray(File(path).readText(Charsets.UTF_8))
                List(values.length()) { values.getString(it) }
            }.orEmpty()
        }
        val permission = { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
        assertTrue("Grant RECORD_AUDIO contextually before this manual test", permission())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val actions = Channel<Action>(Channel.UNLIMITED)
        val attempts = JSONArray()
        var armed = false
        var current: JSONObject? = null
        var terminalRecorded = CompletableDeferred<Unit>()
        var input: LiveMicrophoneStage5Test.LiveMetricsPcmInput? = null
        lateinit var reference: EditText
        lateinit var status: TextView
        lateinit var start: Button
        lateinit var stop: Button
        lateinit var cancel: Button
        lateinit var lifecycle: androidx.lifecycle.Lifecycle
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == ARM_ACTION) actions.trySend(Action.Arm)
            }
        }
        // Debug test only, API35 AVD. Registered only during this foreground manual session.
        context.registerReceiver(receiver, IntentFilter(ARM_ACTION), Context.RECEIVER_EXPORTED)
        val engine = SherpaOfflineSpeechToTextEngine(
            workerScope = scope, hasRecordAudioPermission = permission,
            captureFactory = {
                val owner = LifecyclePcmCapture.attach(context, lifecycle, scope)
                val metrics = LiveMicrophoneStage5Test.LiveMetricsPcmInput(
                    AudioRecordLocalPcmInput(owner.source), SileroPcmSegmenter.create(context),
                )
                input = metrics
                LocalPcmCaptureLease(metrics, { owner.close() }, { owner.isOwnerActive })
            },
            segmenterFactory = { SileroPcmSegmenter.create(context) },
            recognizerFactory = {
                val before = SystemClock.elapsedRealtime()
                SherpaOfflineRecognizerAdapter.create(context).also {
                    current?.put("recognizerInitMs", it.initializationMs)
                    current?.put("recognizerPreparationAndInitMs", SystemClock.elapsedRealtime() - before)
                }
            },
            workerDispatcher = Dispatchers.IO,
        )
        val controller = VoiceCaptureSessionController(engine, scope, permission, SpeechToTextRequest("es-PE"))
        fun renderReady(message: String) {
            status.text = message
            start.isEnabled = armed
            reference.isEnabled = true
            stop.isEnabled = false
            cancel.isEnabled = false
        }
        val output = File(context.noBackupFilesDir, "manual-offline-microphone.json")
        try {
            scenario.onActivity { activity ->
                lifecycle = activity.lifecycle
                val column = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 64, 24, 24)
                }
                column.addView(TextView(activity).apply { text = "F3.06 · Prueba manual local\nNo guarda audio ni recuerdos."; textSize = 20f })
                reference = EditText(activity).apply { hint = "Referencia exacta de la frase"; minLines = 2 }
                column.addView(reference)
                references.forEachIndexed { index, text ->
                    column.addView(Button(activity).apply {
                        this.text = "Frase ${index + 1}"
                        setOnClickListener { if (current == null) reference.setText(text) }
                    })
                }
                if (references.isNotEmpty()) reference.setText(references.first())
                status = TextView(activity).apply { text = "Esperando tu confirmación en Codex. Micrófono cerrado."; textSize = 18f }
                column.addView(status)
                fun button(label: String, action: () -> Unit) = Button(activity).apply {
                    text = label; minHeight = (48 * resources.displayMetrics.density).toInt()
                    setOnClickListener { action() }; column.addView(this)
                }
                start = button("Iniciar") {
                    val text = reference.text.toString().trim()
                    if (text.isBlank()) { status.text = "Introduce primero la referencia exacta." }
                    else { start.isEnabled = false; reference.isEnabled = false; actions.trySend(Action.Start(text)) }
                }.apply { isEnabled = false }
                stop = button("Detener") { stop.isEnabled = false; actions.trySend(Action.Stop) }.apply { isEnabled = false }
                cancel = button("Cancelar") { cancel.isEnabled = false; actions.trySend(Action.Cancel) }.apply { isEnabled = false }
                button("Finalizar prueba") { actions.trySend(Action.Exit) }
                activity.addContentView(ScrollView(activity).apply {
                    setBackgroundColor(android.graphics.Color.WHITE); addView(column)
                    isClickable = true
                }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val collector = scope.launch {
                controller.state.collect { state ->
                    when (state) {
                        is SpeechToTextState.Preparing -> status.text = "Preparando modelos locales. Todavía no hables."
                        is SpeechToTextState.Listening -> {
                            if (current?.has("listeningAtMs") == false) current?.put("listeningAtMs", SystemClock.elapsedRealtime())
                            status.text = "ESCUCHANDO · Pronuncia la referencia y pulsa Detener."
                            stop.isEnabled = true; cancel.isEnabled = true
                        }
                        is SpeechToTextState.Transcribing -> { status.text = "Transcribiendo localmente…"; stop.isEnabled = false }
                        is SpeechToTextState.Result, is SpeechToTextState.Error, SpeechToTextState.Cancelled -> {
                            val attempt = current ?: return@collect
                            val terminalAt = SystemClock.elapsedRealtime()
                            attempt.put("terminalAtMs", terminalAt)
                            if (attempt.has("stopAtMs")) attempt.put("afterStopMs", terminalAt - attempt.getLong("stopAtMs"))
                            if (attempt.has("listeningAtMs")) attempt.put("startToListeningMs", attempt.getLong("listeningAtMs") - attempt.getLong("startAtMs"))
                            val metrics = input?.finishDiagnostics()
                            if (metrics != null) attempt.put("metrics", JSONObject()
                                .put("samples", metrics.sampleCount).put("nonZeroSamples", metrics.nonZeroSamples)
                                .put("rms", metrics.normalizedRms).put("peakAbs", metrics.peakAbs)
                                .put("sampleDurationMs", metrics.sampleDurationMs).put("wallDurationMs", metrics.wallDurationMs)
                                .put("vadSegments", metrics.segmentCount).put("vadSpeechMs", metrics.vadSpeechMs)
                                .put("bufferedSamplesAfterClose", metrics.bufferedSamplesAfterClose))
                            val text = (state as? SpeechToTextState.Result)?.transcript?.text.orEmpty()
                            val terminal = when (state) {
                                is SpeechToTextState.Result -> "RESULT"
                                is SpeechToTextState.Error -> "ERROR:${state.cause}"
                                else -> "CANCELLED"
                            }
                            attempt.put("terminal", terminal).put("transcript", text)
                            attempts.put(attempt)
                            withContext(Dispatchers.IO) {
                                output.writeText(JSONObject().put("source", "live_AudioRecord_MIC")
                                    .put("audioStored", false).put("attempts", attempts).toString(), Charsets.UTF_8)
                            }
                            current = null; input = null
                            renderReady("$terminal\n${if (text.isBlank()) "Sin transcripción" else text}\n" +
                                "RMS: ${metrics?.normalizedRms}; segmentos: ${metrics?.segmentCount}\n" +
                                "Después de Detener: ${attempt.optLong("afterStopMs", -1)} ms.\nPuedes repetir o finalizar.")
                            terminalRecorded.complete(Unit)
                        }
                        else -> Unit
                    }
                }
            }
            withTimeout(30 * 60_000L) {
                for (action in actions) {
                    withContext(Dispatchers.Main.immediate) {
                        when (action) {
                            Action.Arm -> {
                                if (offlineSettings(context)) {
                                    armed = true
                                    renderReady("Listo y offline. Iniciar abrirá el micrófono; espera ESCUCHANDO para hablar.")
                                } else renderReady("No se ha confirmado modo avión/Wi-Fi/datos apagados. Micrófono cerrado.")
                            }
                            is Action.Start -> {
                                if (current == null && armed && offlineSettings(context)) {
                                    input = null
                                    terminalRecorded = CompletableDeferred()
                                    current = JSONObject().put("reference", action.reference)
                                        .put("startAtMs", SystemClock.elapsedRealtime()).put("offlineSettingsAtStart", true)
                                    controller.start()
                                } else renderReady("Inicio rechazado: sesión activa o conectividad sin desactivar.")
                            }
                            Action.Stop -> { current?.put("stopAtMs", SystemClock.elapsedRealtime()); controller.stop() }
                            Action.Cancel -> { current?.put("cancelAtMs", SystemClock.elapsedRealtime()); controller.cancel() }
                            Action.Exit -> {
                                if (current != null) {
                                    controller.cancel()
                                    withTimeout(15_000) { terminalRecorded.await() }
                                }
                                actions.close()
                            }
                        }
                    }
                    if (action == Action.Exit) break
                }
            }
            collector.cancel()
            assertTrue("An active capture must be closed before exiting", current == null)
        } finally {
            withContext(NonCancellable) {
                controller.close()
                input?.closeDiagnostics()
            }
            context.unregisterReceiver(receiver)
            scope.cancel(); actions.close(); scenario.close()
        }
    }

    private fun offlineSettings(context: Context) =
        Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0) == 1 &&
            Settings.Global.getInt(context.contentResolver, "wifi_on", 1) == 0 &&
            Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 0

    private sealed interface Action {
        data object Arm : Action
        data class Start(val reference: String) : Action
        data object Stop : Action
        data object Cancel : Action
        data object Exit : Action
    }

    private companion object {
        const val ARM_ACTION = "com.yezvi.assistant.TEST_ARM_OFFLINE_MIC"
    }
}
