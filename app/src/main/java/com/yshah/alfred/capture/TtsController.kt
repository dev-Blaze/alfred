package com.yshah.alfred.capture

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID

sealed class TtsState {
    data object Idle : TtsState()
    data class Speaking(val utteranceId: String) : TtsState()
    data class Done(val utteranceId: String) : TtsState()
    data class Error(val utteranceId: String) : TtsState()
}

interface TtsController {
    val state: StateFlow<TtsState>
    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString())

    /** Barge-in primitive — interrupts speech mid-utterance, firing onStop -> Done. */
    fun stop()
}

private const val TAG = "AlfredTts"

// Android exposes no gender API on Voice, so a male voice can only be picked by name. These are
// Google-TTS male English voice-name fragments, British first for Alfred's butler persona.
// en-gb-x-gbb was confirmed male on-device (Galaxy S24 Ultra); the rest are the other
// widely-attested male Google voices, kept as fallbacks. Selection falls back to the locale
// default if none match.
private val MALE_VOICE_NAME_HINTS = listOf(
    // en-GB (British) male
    "en-gb-x-gbb", "en-gb-x-rjs",
    // en-US male
    "en-us-x-iom", "en-us-x-iog", "en-us-x-iob",
)

class AndroidTtsController(context: Context) : TtsController {

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    override val state: StateFlow<TtsState> = _state

    // The init callback fires asynchronously once the engine is ready, by which point `tts` is
    // already assigned — safe despite referencing it inside its own initializer's lambda.
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            configureVoice()
        }
    }

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _state.value = TtsState.Speaking(utteranceId.orEmpty())
                }

                override fun onDone(utteranceId: String?) {
                    _state.value = TtsState.Done(utteranceId.orEmpty())
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    _state.value = TtsState.Done(utteranceId.orEmpty())
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _state.value = TtsState.Error(utteranceId.orEmpty())
                }
            },
        )
    }

    /**
     * Points the engine at a British (then US, then any) English male voice, preferring on-device
     * voices for lower latency. Everything is defensive: a null/empty voice list or an engine that
     * throws just leaves the locale default in place, so TTS still works.
     */
    private fun configureVoice() {
        val preferredLocales = listOf(Locale.UK, Locale.US, Locale.ENGLISH)
        val locale = preferredLocales.firstOrNull {
            runCatching { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        } ?: Locale.UK
        tts.language = locale

        val voices = runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { it.locale.language == Locale.ENGLISH.language }

        val chosen = pickMaleVoice(voices, locale)
        if (chosen != null) {
            val result = tts.setVoice(chosen)
            Log.i(TAG, "Selected voice: ${chosen.name} (${chosen.locale}) result=$result")
        } else {
            Log.i(TAG, "No male English voice matched; using locale default for $locale")
        }
    }

    private fun pickMaleVoice(voices: List<Voice>, preferredLocale: Locale): Voice? {
        if (voices.isEmpty()) return null
        fun hintPriority(voice: Voice): Int =
            MALE_VOICE_NAME_HINTS.indexOfFirst { hint -> voice.name.lowercase().contains(hint) }
        val maleHintMatches = voices.filter { hintPriority(it) >= 0 }
        if (maleHintMatches.isEmpty()) return null
        // Prefer the requested locale (British), then on-device (non-network) for latency, then
        // the hint list's own order (so gbb wins over rjs), then quality, then name — the last two
        // purely to make selection deterministic, since Android's voice set has no stable order.
        return maleHintMatches.minWithOrNull(
            compareBy(
                { if (it.locale.country == preferredLocale.country) 0 else 1 },
                { if (it.isNetworkConnectionRequired) 1 else 0 },
                { hintPriority(it) },
                { -it.quality },
                { it.name },
            ),
        )
    }

    override fun speak(text: String, utteranceId: String) {
        // QUEUE_FLUSH — convo mode should never queue a stale response behind a new one.
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun stop() {
        tts.stop()
    }
}
