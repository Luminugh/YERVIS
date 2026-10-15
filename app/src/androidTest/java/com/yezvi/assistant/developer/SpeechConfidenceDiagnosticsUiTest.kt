package com.yezvi.assistant.developer

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Debug diagnostics tests use fake/reference controls; one lifecycle case briefly opens real local audio. */
class SpeechConfidenceDiagnosticsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<LocalSpeechDiagnosticsActivity>()

    @Test
    fun analysisIsOptInAndConnectivityAndReferenceDoNotBlockLocalStart() {
        val permissionBefore = microphonePermission()
        compose.onNodeWithText("Conectividad", substring = true).assertExists()
        compose.onNodeWithText("procesamiento local", substring = true).assertExists()
        analysisSwitch().assertIsOff()
        startButton().assertIsEnabled()

        analysisSwitch().performClick()
        analysisSwitch().assertIsOn()
        compose.onNodeWithText("Scores: pendientes de una sesión local").assertExists()
        startButton().performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Estado: inactivo").assertExists()

        analysisSwitch().performScrollTo().performClick()
        analysisSwitch().assertIsOff()
        startButton().assertIsEnabled()
        compose.onNodeWithText("Scores: pendientes de una sesión local").assertDoesNotExist()
        assertEquals(permissionBefore, microphonePermission())
    }

    @Test
    fun voskCannotEnableSherpaAnalysisButLocalStartDoesNotRequireReference() {
        val permissionBefore = microphonePermission()
        analysisSwitch().performClick()
        startButton().assertIsEnabled()

        compose.onNodeWithText("Vosk").performScrollTo().performClick()
        compose.onNodeWithText("Motor seleccionado: Vosk").assertExists()
        analysisSwitch().assertDoesNotExist()
        compose.onNodeWithText("Scores: pendientes de una sesión local").assertDoesNotExist()
        startButton().assertIsEnabled()
        compose.onNodeWithText("Estado: inactivo").assertExists()
        assertEquals(permissionBefore, microphonePermission())
    }

    @Test
    fun activityRecreationRestoresOptInDefaultWithoutStartingCapture() {
        val permissionBefore = microphonePermission()
        analysisSwitch().performClick()
        analysisSwitch().assertIsOn()
        compose.activityRule.scenario.recreate()
        analysisSwitch().assertIsOff()
        startButton().assertIsEnabled()
        compose.onNodeWithText("Estado: inactivo").assertExists()
        compose.onNodeWithText("Scores: pendientes de una sesión local").assertDoesNotExist()
        assertEquals(permissionBefore, microphonePermission())
    }

    @Test
    fun leavingDiagnosticsCancelsActiveMicrophoneSession() {
        assertEquals(PackageManager.PERMISSION_GRANTED, microphonePermission())
        startButton().performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 45_000) {
            compose.onAllNodesWithText("Estado: escuchando").fetchSemanticsNodes().isNotEmpty()
        }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Estado: cancelado").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Estado: cancelado").assertExists()
    }

    @Test
    fun cancellingSessionAllowsAnotherOfflineSessionWithoutAStaleResult() {
        assertEquals(PackageManager.PERMISSION_GRANTED, microphonePermission())
        repeat(2) {
            startButton().performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 45_000) {
                compose.onAllNodesWithText("Estado: escuchando").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Cancelar").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("Estado: cancelado").fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.onNodeWithText("Estado: cancelado").assertExists()
        compose.onNodeWithText("Estado: resultado").assertDoesNotExist()
    }

    private fun analysisSwitch() = compose.onNodeWithContentDescription("Analizar palabras, experimental")

    private fun startButton() = compose.onNodeWithText("Iniciar")

    private fun microphonePermission(): Int = compose.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
}
