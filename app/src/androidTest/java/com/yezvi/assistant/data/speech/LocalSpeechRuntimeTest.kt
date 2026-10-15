package com.yezvi.assistant.data.speech

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real JNI/ONNX smoke test. Controlled silence is not a Spanish accuracy evaluation. */
@RunWith(AndroidJUnit4::class)
class LocalSpeechRuntimeTest {
    @Test
    fun loadsPinnedRnntAndDecodesControlledSilenceWithoutMicrophone() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = LocalSpeechModelFiles(context).prepare()
        assertTrue(files.directory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + "/stt/"))
        assertEquals(files, LocalSpeechModelFiles(context).prepare())

        withContext(Dispatchers.Default) {
            val recognizer = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0f),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = files.encoder.absolutePath,
                            decoder = files.decoder.absolutePath,
                            joiner = files.joiner.absolutePath,
                        ),
                        tokens = files.tokens.absolutePath,
                        modelType = "nemo_transducer",
                        numThreads = 2,
                        provider = "cpu",
                        debug = false,
                    ),
                    decodingMethod = "greedy_search",
                ),
            )
            try {
                val stream = recognizer.createStream()
                try {
                    // Two seconds, mono PCM normalized to Float32, generated in memory.
                    // Bypass VAD so the real decoder executes even without speech.
                    stream.acceptWaveform(FloatArray(32_000), sampleRate = 16_000)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    assertFalse(result.text.contains('\u0000'))
                    assertTrue(result.timestamps.all { it.isFinite() && it >= 0f })
                    println("F3_06_STAGE1=rnnt-loaded;decode=controlled-silence;" +
                        "textLength=${result.text.length};tokens=${result.tokens.size};abi=${Build.SUPPORTED_ABIS.first()}")
                } finally {
                    stream.release()
                }
            } finally {
                recognizer.release()
            }
        }
    }

    @Test
    fun loadsPinnedSileroAndProcessesControlledSilence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = LocalSpeechModelFiles(context).prepare()
        withContext(Dispatchers.Default) {
            val vad = Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(model = files.vad.absolutePath),
                    sampleRate = 16_000,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                ),
            )
            try {
                repeat(32) { vad.acceptWaveform(FloatArray(512)) }
                vad.flush()
                assertTrue(vad.empty())
                assertFalse(vad.isSpeechDetected())
                println("F3_06_STAGE1=vad-loaded;input=controlled-silence;segments=0")
            } finally {
                vad.release()
            }
        }
    }
}
