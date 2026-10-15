package com.yezvi.assistant.data.speech

import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.MainActivity
import com.yezvi.assistant.data.local.memory.YezviDatabaseFactory
import com.yezvi.assistant.data.repository.RoomMemoryRepository
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.speech.LocalSpeechAvailability
import com.yezvi.assistant.presentation.capture.CaptureSaveState
import com.yezvi.assistant.presentation.capture.CaptureViewModel
import com.yezvi.assistant.ui.theme.YERVISTheme
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Human acceptance only: opt-in argument + user's checkbox + explicit save + audible verdict.
 * Creates/deletes only its own UUID database. Never runs in the automatic F3.12 group. */
class ManualLocalTtsAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun humanVerifiesOfflineConfirmationAfterTemporarySqliteCommit() = runBlocking {
        assumeTrue("Manual test requires human readiness and USB", InstrumentationRegistry.getArguments()
            .getString("manualOfflineTts") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        assertEquals(1, Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0))
        assertEquals(0, Settings.Global.getInt(resolver, Settings.Global.WIFI_ON, 1))
        assertEquals(0, Settings.Global.getInt(resolver, "mobile_data", 1))
        val databaseName = "f312-manual-${UUID.randomUUID()}.db"
        val database = YezviDatabaseFactory.create(context, databaseName)
        val repository = RoomMemoryRepository(database.memoryEntryDao())
        val vmStore = ViewModelStore()
        val answer = AtomicReference<Boolean?>(null)
        val syntheticId = "f312-manual-synthetic"
        val input = CaptureInput("Prueba sintética F3.12", CaptureSourceType.TEXT, 1_791_234_567_890L, "America/Lima")
        lateinit var vm: CaptureViewModel
        try {
            compose.activity.runOnUiThread {
                vm = CaptureViewModel(repository, confirmationSpeechFactory = { AndroidLocalConfirmationSpeech(context) },
                    idGenerator = { syntheticId })
                vmStore.put("manual", vm)
                vm.spokenConfirmation.setForeground(true)
                vm.spokenConfirmation.setSettingsVisible(true) // Discovery is silent while opt-in is OFF.
                compose.activity.setContent {
                    val confirmation by vm.spokenConfirmation.state.collectAsStateWithLifecycle()
                    val saved by vm.uiState.collectAsStateWithLifecycle()
                    YERVISTheme {
                        Surface {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("F3.12 · prueba manual · base temporal", style = MaterialTheme.typography.titleLarge)
                                Text("No modifica tus recuerdos. La única frase será: Recuerdo guardado.")
                                Text("Disponibilidad: ${confirmation.availability ?: "inicializando"}")
                                Row {
                                    Checkbox(confirmation.enabled, vm.spokenConfirmation::setEnabled)
                                    Text("Autorizo escuchar la confirmación local")
                                }
                                Button(onClick = { vm.save(input) }, enabled = confirmation.enabled &&
                                    confirmation.availability == LocalSpeechAvailability.READY && saved is CaptureSaveState.Idle) {
                                    Text("Guardar nota sintética en base temporal")
                                }
                                Text(if (saved is CaptureSaveState.Saved) "SQLite confirmó el guardado. Escucha y responde." else "Aún no se ha confirmado un guardado.")
                                Button(onClick = { answer.set(true) }, enabled = saved is CaptureSaveState.Saved) {
                                    Text("Sí, oí «Recuerdo guardado»")
                                }
                                OutlinedButton(onClick = { answer.set(false) }, enabled = saved is CaptureSaveState.Saved) {
                                    Text("No se escuchó correctamente")
                                }
                            }
                        }
                    }
                }
            }
            compose.waitUntil(180_000) { answer.get() != null }
            assertEquals(input.rawText, repository.findById(syntheticId)?.rawText)
            assertEquals("Human did not verify audible offline speech", true, answer.get())
            InstrumentationRegistry.getInstrumentation().addResults(android.os.Bundle().apply {
                putString("audibleVerifiedBy", "human-user-button")
            })
        } finally {
            compose.activity.runOnUiThread { vmStore.clear() }
            database.close()
            context.deleteDatabase(databaseName) // Only this test's temporary UUID database.
        }
    }
}
