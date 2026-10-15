package com.yezvi.assistant.data.speech

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.yezvi.assistant.domain.speech.LocalConfirmationSpeech
import com.yezvi.assistant.domain.speech.LocalSpeechAvailability
import com.yezvi.assistant.domain.speech.LocalSpeechVoiceOption
import com.yezvi.assistant.domain.speech.isInstalledLocalSpanishVoice
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Uses only the configured system engine and explicitly local, installed Spanish voices. */
class AndroidLocalConfirmationSpeech(context: Context) : LocalConfirmationSpeech {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutableAvailability = MutableStateFlow(LocalSpeechAvailability.PREPARING)
    override val availability = mutableAvailability.asStateFlow()
    private val mutableVoices = MutableStateFlow<List<LocalSpeechVoiceOption>>(emptyList())
    override val voices = mutableVoices.asStateFlow()
    private val mutableSelectedVoiceId = MutableStateFlow<String?>(null)
    override val selectedVoiceId = mutableSelectedVoiceId.asStateFlow()

    private var engine: TextToSpeech? = null
    private var installedSpanishVoices: List<Voice> = emptyList()
    private var preferredVoiceId: String? = null
    private var closed = false
    private var initialized = false
    private var engineReady = false
    private var utterance = 0L
    private var activeUtteranceId: String? = null
    private val timeout = Runnable { fail(LocalSpeechAvailability.ENGINE_UNAVAILABLE) }

    override fun initialize() = initialize(null)

    override fun initialize(preferredVoiceId: String?) {
        checkMainThread()
        if (initialized || closed) return
        initialized = true
        this.preferredVoiceId = preferredVoiceId
        main.postDelayed(timeout, 8_000L)
        try {
            // Posting also handles a synchronous failed callback before construction returns.
            engine = TextToSpeech(appContext) { result ->
                main.post { onInitialized(result) }
            }
        } catch (_: Exception) {
            fail(LocalSpeechAvailability.ENGINE_UNAVAILABLE)
        }
    }

    override fun selectVoice(voiceId: String?): Boolean {
        checkMainThread()
        if (closed) return false
        preferredVoiceId = voiceId?.takeIf(String::isNotBlank)
        if (!engineReady) return mutableAvailability.value == LocalSpeechAvailability.PREPARING
        return applyPreferredVoice()
    }

    private fun onInitialized(result: Int) {
        if (closed || mutableAvailability.value != LocalSpeechAvailability.PREPARING) return
        main.removeCallbacks(timeout)
        if (result != TextToSpeech.SUCCESS) {
            fail(LocalSpeechAvailability.ENGINE_UNAVAILABLE)
            return
        }
        try {
            val tts = engine ?: return fail(LocalSpeechAvailability.ENGINE_UNAVAILABLE)
            installedSpanishVoices = tts.voices.orEmpty().filter(::isLocalSpanish)
                .sortedWith(compareByDescending<Voice> { it.locale.country == "PE" }
                    .thenByDescending { it.quality }.thenBy { it.latency }.thenBy { it.name })
            mutableVoices.value = buildOptions(installedSpanishVoices)
            val listenerResult = tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    main.post { if (activeUtteranceId == utteranceId) activeUtteranceId = null }
                }
                @Deprecated("Framework callback")
                override fun onError(utteranceId: String?) = reportError(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = reportError(utteranceId)
                private fun reportError(id: String?) {
                    main.post {
                        if (!closed && id != null && activeUtteranceId == id) fail(LocalSpeechAvailability.FAILED)
                    }
                }
            })
            if (listenerResult != TextToSpeech.SUCCESS) return fail(LocalSpeechAvailability.FAILED)
            engineReady = true
            applyPreferredVoice()
        } catch (_: Exception) {
            fail(LocalSpeechAvailability.FAILED)
        }
    }

    /** Applies a user-selected installed local voice, or the best local Spanish option. */
    private fun applyPreferredVoice(): Boolean {
        val tts = engine ?: return false
        val candidate = if (preferredVoiceId == null) {
            installedSpanishVoices.firstOrNull()
        } else {
            installedSpanishVoices.firstOrNull { it.name == preferredVoiceId }
        }
        if (candidate == null) {
            mutableSelectedVoiceId.value = null
            mutableAvailability.value = LocalSpeechAvailability.LOCAL_VOICE_UNAVAILABLE
            return false
        }
        return try {
            activeUtteranceId = null
            tts.stop()
            if (tts.setVoice(candidate) != TextToSpeech.SUCCESS ||
                tts.voice?.let { it.name == candidate.name && isLocalSpanish(it) } != true
            ) {
                mutableSelectedVoiceId.value = null
                mutableAvailability.value = LocalSpeechAvailability.LOCAL_VOICE_UNAVAILABLE
                false
            } else {
                mutableSelectedVoiceId.value = candidate.name
                mutableAvailability.value = LocalSpeechAvailability.READY
                true
            }
        } catch (_: Exception) {
            mutableSelectedVoiceId.value = null
            mutableAvailability.value = LocalSpeechAvailability.FAILED
            false
        }
    }

    override fun announceSaved(): Boolean = announceSaved(1f)

    override fun announceSaved(volume: Float): Boolean {
        checkMainThread()
        if (closed || availability.value != LocalSpeechAvailability.READY) return false
        val normalizedVolume = volume.coerceIn(0f, 1f)
        if (normalizedVolume == 0f) {
            stop()
            return true
        }
        return try {
            val tts = engine ?: return false
            val selectedId = selectedVoiceId.value ?: return false
            val selected = tts.voices.orEmpty().firstOrNull {
                it.name == selectedId && isLocalSpanish(it)
            }
            if (selected == null || tts.setVoice(selected) != TextToSpeech.SUCCESS ||
                tts.voice?.let { it.name == selected.name && isLocalSpanish(it) } != true
            ) {
                mutableSelectedVoiceId.value = null
                mutableAvailability.value = LocalSpeechAvailability.LOCAL_VOICE_UNAVAILABLE
                false
            } else {
                val params = Bundle().apply {
                    // Per-utterance gain only; never changes Android's media/system volume.
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, normalizedVolume)
                }
                val utteranceId = "yezvi-confirm-${++utterance}"
                activeUtteranceId = utteranceId
                val queued = tts.speak("Recuerdo guardado", TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                if (queued != TextToSpeech.SUCCESS) fail(LocalSpeechAvailability.FAILED)
                queued == TextToSpeech.SUCCESS
            }
        } catch (_: Exception) {
            fail(LocalSpeechAvailability.FAILED)
            false
        }
    }

    override fun stop() {
        checkMainThread()
        activeUtteranceId = null
        runCatching { engine?.stop() }
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        releaseEngine()
        installedSpanishVoices = emptyList()
        mutableVoices.value = emptyList()
        mutableSelectedVoiceId.value = null
    }

    private fun fail(reason: LocalSpeechAvailability) {
        main.removeCallbacks(timeout)
        mutableAvailability.value = reason
        engineReady = false
        releaseEngine()
    }

    private fun releaseEngine() {
        val owner = engine
        engine = null
        activeUtteranceId = null
        runCatching { owner?.stop() }
        runCatching { owner?.shutdown() }
    }

    private fun buildOptions(voices: List<Voice>): List<LocalSpeechVoiceOption> {
        val locale = Locale.forLanguageTag("es")
        val baseLabels = voices.map { voice ->
            val localeName = voice.locale.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) }
            val quality = when (voice.quality) {
                Voice.QUALITY_HIGH -> "alta"
                Voice.QUALITY_NORMAL -> "estándar"
                Voice.QUALITY_LOW -> "básica"
                else -> "disponible"
            }
            "$localeName · calidad $quality"
        }
        val duplicates = baseLabels.groupingBy { it }.eachCount()
        val occurrences = mutableMapOf<String, Int>()
        return voices.mapIndexed { index, voice ->
            val base = baseLabels[index]
            val label = if (duplicates.getValue(base) > 1) {
                val occurrence = (occurrences[base] ?: 0) + 1
                occurrences[base] = occurrence
                "$base · voz $occurrence"
            } else base
            LocalSpeechVoiceOption(voice.name, label, voice.locale.toLanguageTag())
        }
    }

    @Suppress("DEPRECATION") // Reject old provider flags too if a vendor still supplies them.
    private fun isLocalSpanish(voice: Voice): Boolean = isInstalledLocalSpanishVoice(
        voice.locale.language,
        voice.isNetworkConnectionRequired,
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features.orEmpty(),
        TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS in voice.features.orEmpty(),
    )

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())
}
