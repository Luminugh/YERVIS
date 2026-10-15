package com.yezvi.assistant.developer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.yezvi.assistant.data.speech.SherpaOfflineSpeechToTextEngine
import com.yezvi.assistant.data.speech.SherpaTokenScores
import com.yezvi.assistant.data.speech.VoskSpeechToTextEngine
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextState
import com.yezvi.assistant.presentation.capture.VoiceCaptureSessionController
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.ui.theme.YERVISTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Debug-only manual test surface. It never persists audio or sends data over the network. */
class LocalSpeechDiagnosticsActivity : ComponentActivity() {
    private val sessionScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ ->
            // Keep unexpected command failures inside this debug tool; never log audio/transcripts.
            if (!isDestroyed) {
                clearScores()
                notice = "Error inesperado en la sesión local. Cancela y vuelve a intentarlo."
            }
        },
    )
    private lateinit var controller: VoiceCaptureSessionController
    // The measured Vosk alternative has not passed the speed/quality acceptance gate.
    private var engineChoice by mutableStateOf(LocalEngineChoice.SHERPA)
    private var changingEngine by mutableStateOf(false)
    private var offline by mutableStateOf(false)
    private var hasMicPermission by mutableStateOf(false)
    private var startRequestedAt by mutableLongStateOf(0L)
    private var listeningAt by mutableLongStateOf(0L)
    private var stopRequestedAt by mutableLongStateOf(0L)
    private var finalAt by mutableLongStateOf(0L)
    private var notice by mutableStateOf<String?>(null)
    private var analysisEnabled by mutableStateOf(false)
    private var scoreAnalysis by mutableStateOf<WordUncertaintyAnalysis?>(null)
    private var startPending by mutableStateOf(false)
    private var controllerGeneration = 0L
    private val scoreSession = AtomicReference<WordUncertaintySession?>(null)

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
        if (granted) beginSession() else {
            clearScores()
            notice = "Permiso de micrófono denegado; no se inició la captura."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPreflight()
        controller = createController(engineChoice)
        setContent {
            YERVISTheme {
                DiagnosticsContent(
                    state = controller.state,
                    engineChoice = engineChoice,
                    changingEngine = changingEngine || startPending,
                    onSelectEngine = { choice ->
                        if (choice != engineChoice) reconfigure(choice, analysisEnabled)
                    },
                    analysisEnabled = analysisEnabled,
                    scoreAnalysis = scoreAnalysis,
                    onAnalysisEnabled = { enabled -> reconfigure(engineChoice, enabled) },
                    offline = offline,
                    hasMicPermission = hasMicPermission,
                    notice = notice,
                    startToListeningMs = (listeningAt - startRequestedAt).takeIf { listeningAt > 0 && startRequestedAt > 0 },
                    listeningDurationMs = (stopRequestedAt - listeningAt).takeIf { listeningAt > 0 && stopRequestedAt > 0 },
                    afterStopMs = (finalAt - stopRequestedAt).takeIf { finalAt > 0 && stopRequestedAt > 0 },
                    onListening = {
                        if (listeningAt == 0L) listeningAt = SystemClock.elapsedRealtime()
                    },
                    onTerminal = { terminal ->
                        finalAt = SystemClock.elapsedRealtime()
                        if (terminal is SpeechToTextState.Error || terminal is SpeechToTextState.Cancelled) clearScores()
                    },
                    onStart = {
                        clearScores()
                        refreshPreflight()
                        notice = null
                        if (!hasMicPermission) {
                            permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            beginSession()
                        }
                    },
                    onStop = {
                        stopRequestedAt = SystemClock.elapsedRealtime()
                        sessionScope.launch { controller.stop() }
                    },
                    onCancel = {
                        clearScores()
                        sessionScope.launch { controller.cancel() }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPreflight()
    }

    override fun onDestroy() {
        clearScores()
        controllerGeneration++
        if (this::controller.isInitialized) {
            sessionScope.launch {
                try {
                    controller.close()
                } finally {
                    sessionScope.cancel()
                }
            }
        } else {
            sessionScope.cancel()
        }
        super.onDestroy()
    }

    override fun onStop() {
        clearScores()
        if (this::controller.isInitialized) {
            sessionScope.launch { controller.cancel() }
        }
        super.onStop()
    }

    private fun refreshPreflight() {
        offline = isOffline()
        hasMicPermission = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun isOffline(): Boolean =
        Settings.Global.getInt(contentResolver, "airplane_mode_on", 0) == 1 &&
            Settings.Global.getInt(contentResolver, "wifi_on", 1) == 0 &&
            Settings.Global.getInt(contentResolver, "mobile_data", 1) == 0

    private fun beginSession() {
        if (startPending || changingEngine || controller.state.value.isActiveSession()) return
        // Keep network status visible, but never gate the strictly local debug engines on it.
        offline = isOffline()
        startRequestedAt = SystemClock.elapsedRealtime()
        listeningAt = 0L
        stopRequestedAt = 0L
        finalAt = 0L
        notice = null
        clearScores()
        if (analysisEnabled && engineChoice == LocalEngineChoice.SHERPA) {
            scoreSession.set(WordUncertaintySession(SCORE_PROFILE))
        }
        startPending = true
        sessionScope.launch {
            try { controller.start() } finally { startPending = false }
        }
    }

    private fun clearScores() {
        scoreSession.set(null)
        scoreAnalysis = null
    }

    private fun reconfigure(choice: LocalEngineChoice, enabled: Boolean) {
        if (changingEngine || startPending || controller.state.value.isActiveSession()) return
        clearScores()
        sessionScope.launch {
            changingEngine = true
            try {
                controller.close()
                if (!isDestroyed) {
                    analysisEnabled = enabled
                    controller = createController(choice)
                    engineChoice = choice
                    startRequestedAt = 0L
                    listeningAt = 0L
                    stopRequestedAt = 0L
                    finalAt = 0L
                    notice = null
                }
            } finally { changingEngine = false }
        }
    }

    private fun createController(choice: LocalEngineChoice): VoiceCaptureSessionController {
        val generation = ++controllerGeneration
        val permission = { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
        val engine = when (choice) {
            LocalEngineChoice.VOSK -> VoskSpeechToTextEngine.create(this, lifecycle, sessionScope, permission)
            LocalEngineChoice.SHERPA -> SherpaOfflineSpeechToTextEngine.create(
                this, lifecycle, sessionScope, permission,
                tokenScoreReader = if (!analysisEnabled) null else { pointer ->
                    // Keep object/JNI library initialization on the decode worker, never on toggle.
                    SherpaTokenScores.readTokenLogProbabilities(pointer)
                },
                onSegmentRecognition = if (!analysisEnabled) null else { segment ->
                    val current = scoreSession.get()
                    if (current != null) {
                        // Called on the recognition worker, with no extra inference or PCM copy.
                        val result = current.append(
                            segment.sessionId, segment.textBefore, segment.recognition.text,
                            segment.recognition.tokens, segment.recognition.timestamps,
                            segment.recognition.tokenLogProbabilities, segment.recognition.audioDurationMs,
                        )
                        sessionScope.launch {
                            if (!isDestroyed && generation == controllerGeneration &&
                                scoreSession.get() === current && permission() &&
                                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                controller.state.value !is SpeechToTextState.Cancelled &&
                                controller.state.value !is SpeechToTextState.Error) {
                                scoreAnalysis = result
                            }
                        }
                    }
                },
            )
        }
        return VoiceCaptureSessionController(engine, sessionScope, permission, SpeechToTextRequest("es-PE"))
    }

    private companion object {
        // No calibrated threshold is installed. Any future artifact must pin every identity/hash.
        const val SCORE_PROFILE = "sherpa-1.13.8-pre-context-v1/fastconformer-es-int8/beam4/cpu2/no-hotwords/min-bpe-v1"
    }
}

private enum class LocalEngineChoice(val label: String) { VOSK("Vosk"), SHERPA("Sherpa") }

@Composable
private fun DiagnosticsContent(
    state: kotlinx.coroutines.flow.StateFlow<SpeechToTextState>,
    engineChoice: LocalEngineChoice,
    changingEngine: Boolean,
    onSelectEngine: (LocalEngineChoice) -> Unit,
    analysisEnabled: Boolean,
    scoreAnalysis: WordUncertaintyAnalysis?,
    onAnalysisEnabled: (Boolean) -> Unit,
    offline: Boolean,
    hasMicPermission: Boolean,
    notice: String?,
    startToListeningMs: Long?,
    listeningDurationMs: Long?,
    afterStopMs: Long?,
    onListening: () -> Unit,
    onTerminal: (SpeechToTextState) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val currentState by state.collectAsState()
    var reference by remember { mutableStateOf("") }
    val finalTranscript = (currentState as? SpeechToTextState.Result)?.transcript?.text
    val wordErrorStats = remember(reference, finalTranscript) {
        finalTranscript?.takeIf { reference.isNotBlank() }?.let { WordErrorScorer.score(reference, it) }
    }
    val active = currentState is SpeechToTextState.Preparing ||
        currentState is SpeechToTextState.Listening || currentState is SpeechToTextState.Transcribing
    LaunchedEffect(currentState) {
        when (currentState) {
            is SpeechToTextState.Listening -> onListening()
            is SpeechToTextState.Result, is SpeechToTextState.Error, SpeechToTextState.Cancelled -> onTerminal(currentState)
            else -> Unit
        }
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("F3.06 · STT local", style = MaterialTheme.typography.headlineSmall)
            Text(if (engineChoice == LocalEngineChoice.VOSK) "AudioRecord → Vosk · streaming local. No guarda audio ni recuerdos."
                else "AudioRecord → Silero → Sherpa · solo investigación local. No guarda audio ni recuerdos.")
            Text("Motor seleccionado: ${engineChoice.label}", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LocalEngineChoice.entries.forEach { choice ->
                    TextButton(onClick = { onSelectEngine(choice) }, enabled = !active && !changingEngine && choice != engineChoice) {
                        Text(choice.label)
                    }
                }
            }
            if (engineChoice == LocalEngineChoice.SHERPA) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Analizar palabras — experimental", modifier = Modifier.weight(1f))
                    Switch(
                        modifier = Modifier.semantics { contentDescription = "Analizar palabras, experimental" },
                        checked = analysisEnabled,
                        onCheckedChange = onAnalysisEnabled,
                        enabled = !active && !changingEngine,
                    )
                }
                if (analysisEnabled) Text(
                    "Scores locales sin referencia. No representan el porcentaje de acierto. " +
                        "Sin umbral validado no se marcan palabras automáticamente.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = if (offline) "Conectividad desactivada · procesamiento local"
                    else "Conectividad activa · procesamiento local",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            if (reference.isNotBlank()) Text("Referencia: $reference", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (hasMicPermission) "Permiso de micrófono concedido" else "Se solicitará permiso al tocar Iniciar",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = reference,
                onValueChange = { reference = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Referencia exacta (solo para comparar)") },
                minLines = 2,
                enabled = !active,
            )
            Text("Estado: ${stateLabel(currentState)}", style = MaterialTheme.typography.titleMedium)
            (currentState as? SpeechToTextState.Listening)?.partialTranscript?.text?.let { partial ->
                SelectionContainer { Text("Parcial: $partial") }
            }
            when (currentState) {
                is SpeechToTextState.Result -> SelectionContainer {
                    Text("Transcripción: ${(currentState as SpeechToTextState.Result).transcript.text}")
                }
                is SpeechToTextState.Error -> Text("Error: ${errorLabel((currentState as SpeechToTextState.Error).cause)}")
                else -> Unit
            }
            wordErrorStats?.let { stats ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Error de palabras (WER): ${stats.werPercent?.let(::formatPercent) ?: "No calculable"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (stats.referenceWords >= 0) {
                        Text("Referencia: ${stats.referenceWords} palabras · transcripción: ${stats.hypothesisWords}")
                    } else {
                        Text("Conteo de palabras no disponible para entradas extensas")
                    }
                    if (stats.alignmentAvailable) {
                        Text("Correctas en orden: ${stats.correctWords}")
                        Text("Sustituciones: ${stats.substitutions} · omisiones: ${stats.deletions} · inserciones: ${stats.insertions}")
                    } else {
                        Text("Conteos de alineación no disponibles")
                    }
                    stats.unavailableReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(
                        "WER = (sustituciones + omisiones + inserciones) / palabras de referencia. " +
                            "Ignora mayúsculas, signos y tildes; conserva palabras y orden. No convierte cifras a palabras.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (analysisEnabled && engineChoice == LocalEngineChoice.SHERPA) {
                WordScoreContent(scoreAnalysis, finalTranscript, reference)
            }
            startToListeningMs?.let { Text("Iniciar → escucha: $it ms") }
            listeningDurationMs?.let { Text("Duración escuchada: $it ms") }
            afterStopMs?.let { Text("Detener → resultado: $it ms") }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStart, enabled = !active && !changingEngine) { Text("Iniciar") }
                Button(onClick = onStop, enabled = currentState is SpeechToTextState.Listening) { Text("Detener") }
                Button(onClick = onCancel, enabled = active) { Text("Cancelar") }
            }
            Text("La referencia es opcional y solo sirve para comparar. Audio y reconocimiento permanecen locales; " +
                "no se escriben recuerdos ni se envían datos por red.")
        }
    }
}

@Composable
private fun WordScoreContent(analysis: WordUncertaintyAnalysis?, finalTranscript: String?, reference: String) {
    if (analysis == null) {
        Text("Scores: pendientes de una sesión local", style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (analysis.unavailableReason != null || finalTranscript != null && analysis.text != finalTranscript) {
        Text("Análisis no disponible: ${analysis.unavailableReason ?: "el texto final no coincide con los segmentos"}",
            color = MaterialTheme.colorScheme.error)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Scores descriptivos por palabra", style = MaterialTheme.typography.titleMedium)
        Text("${analysis.tokenCount}/2.048 tokens · ${analysis.words.size}/512 palabras · " +
            if (analysis.hasValidatedThreshold) "umbral evaluado para este perfil" else "sin umbral calibrado")
        Text("Mínimo log-score BPE: los valores más negativos indican menor apoyo del decoder; " +
            "incluso una palabra con score alto puede ser errónea. Omisiones y palabras no emitidas no se detectan.",
            style = MaterialTheme.typography.bodySmall)
        if (analysis.words.isNotEmpty()) {
            val sorted = remember(analysis) { analysis.words.map { it.minimumTokenLogProbability }.sorted() }
            Text("Mínimo observado: ${formatScore(sorted.first())} · mediana: ${formatScore(sorted[sorted.size / 2])}")
            SelectionContainer {
                Text(analysis.words.take(DISPLAY_WORDS).mapIndexed { index, word ->
                    val review = if (index in analysis.reviewWordIndices) "Revisar · " else ""
                    "$review${word.text}: ${formatScore(word.minimumTokenLogProbability)}"
                }.joinToString("\n"))
            }
            if (analysis.words.size > DISPLAY_WORDS) {
                Text("Se muestran las primeras $DISPLAY_WORDS palabras para limitar el trabajo de la interfaz.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (analysis.hasValidatedThreshold && reference.isNotBlank() && finalTranscript != null) {
            val stats = remember(reference, analysis) { WordAlertEvaluation.evaluate(reference, analysis) }
            if (stats.unavailableReason != null) {
                Text(stats.unavailableReason, color = MaterialTheme.colorScheme.error)
            } else {
                Text("Alertas: ${stats.alerts} · útiles: ${stats.trueAlerts} · falsas: ${stats.falseAlerts}")
                Text("Precisión de alertas: ${stats.precision?.let { formatPercent(it * 100) } ?: "sin alertas"} · " +
                    "errores emitidos detectados: ${stats.detectedErrorFraction?.let { formatPercent(it * 100) } ?: "sin errores emitidos"}")
                Text("Palabras marcadas: ${stats.markedWordFraction?.let { formatPercent(it * 100) } ?: "no disponible"} · " +
                    "omisiones de referencia: ${stats.omissions} (sin palabra para resaltar)")
            }
        }
    }
}

private const val DISPLAY_WORDS = 40

private fun formatScore(value: Float): String = String.format(java.util.Locale.ROOT, "%.3f", value)

private fun SpeechToTextState.isActiveSession(): Boolean = this is SpeechToTextState.Preparing ||
    this is SpeechToTextState.Listening || this is SpeechToTextState.Paused ||
    this is SpeechToTextState.Transcribing

private fun formatPercent(value: Double): String =
    java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(value) + " %"

private fun stateLabel(state: SpeechToTextState): String = when (state) {
    SpeechToTextState.Idle -> "inactivo"
    SpeechToTextState.Preparing -> "preparando modelo local"
    is SpeechToTextState.Listening -> "escuchando"
    is SpeechToTextState.Paused -> "pausado"
    is SpeechToTextState.Transcribing -> "transcribiendo localmente"
    is SpeechToTextState.Result -> "resultado"
    SpeechToTextState.Cancelled -> "cancelado"
    is SpeechToTextState.Error -> "error"
}

private fun errorLabel(error: SpeechToTextError): String = when (error) {
    SpeechToTextError.EngineUnavailable -> "motor local no disponible"
    SpeechToTextError.OfflineModelMissing -> "faltan archivos del modelo local"
    SpeechToTextError.PermissionDenied -> "permiso de micrófono denegado o revocado"
    is SpeechToTextError.RecognitionFailed -> "fallo de reconocimiento (${error.reason})"
}
