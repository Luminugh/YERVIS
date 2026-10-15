package com.yezvi.assistant.data.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextRequest
import com.yezvi.assistant.domain.speech.SpeechToTextState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the installed Android on-device service; this test supplies no speech stimulus. */
@RunWith(AndroidJUnit4::class)
class OnDeviceSpeechRecognizerOfflineProbeTest {
    @Test
    fun probesSpanishOnDeviceAvailabilityAndReleasesAnyOpenedSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            System.out.println("F3_06_DEVICE_PROBE=not-run-permission-not-granted")
            return@runBlocking
        }

        val engine = AndroidOnDeviceSpeechToTextEngine(
            factory = AndroidSpeechRecognizerFactory(context),
            mainDispatcher = Dispatchers.Main.immediate,
        )
        var outcome = "not-started"
        try {
            engine.start(SpeechToTextRequest("es-PE"))
            val firstAvailability = withTimeoutOrNull(15_000) {
                engine.state.first { state ->
                    state is SpeechToTextState.Listening || state is SpeechToTextState.Error
                }
            }

            if (firstAvailability is SpeechToTextState.Error) {
                outcome = firstAvailability.cause.toSafeOutcome()
            } else if (firstAvailability is SpeechToTextState.Listening) {
                val terminal = withTimeoutOrNull(8_000) {
                    engine.state.first { state ->
                        state is SpeechToTextState.Result || state is SpeechToTextState.Error
                    }
                }
                outcome = terminal?.toSafeOutcome() ?: "listening-no-speech-result"
                if (terminal is SpeechToTextState.Result || terminal == null) engine.cancel()
            } else {
                outcome = "availability-timeout"
                engine.cancel()
            }
        } finally {
            engine.cancel()
            // Only a coarse status is emitted; no transcript or audio is printed.
            System.out.println("F3_06_DEVICE_PROBE=$outcome")
        }

        assertTrue("The probe must report a device outcome", outcome != "not-started")
    }

    private fun SpeechToTextError.toSafeOutcome(): String = when (this) {
        SpeechToTextError.EngineUnavailable -> "engine-unavailable"
        SpeechToTextError.OfflineModelMissing -> "spanish-model-missing"
        SpeechToTextError.PermissionDenied -> "permission-denied"
        is SpeechToTextError.RecognitionFailed -> "recognition-failed-${reason.name.lowercase()}"
    }

    private fun SpeechToTextState.toSafeOutcome(): String = when (this) {
        is SpeechToTextState.Error -> cause.toSafeOutcome()
        is SpeechToTextState.Result -> "final-result-without-output-logging"
        else -> "unexpected-terminal-state"
    }
}
