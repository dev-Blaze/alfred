package com.yshah.alfred.capture

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.os.Handler
import android.os.Looper
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

    /** Barge-in primitive — interrupts speech and invalidates pending callbacks. */
    fun stop()
}

private const val TAG = "AlfredTts"

internal fun speechChunks(text: String, maxLength: Int): List<String> {
    require(maxLength >= 2)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + maxLength, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}

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
    private val utteranceLock = Any()
    private var activeUtteranceId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var initializationFailed = false
    private var pendingText: String? = null
    private var chunkIds = emptyList<String>()
    private val startupTimeout = Runnable {
        synchronized(utteranceLock) {
            if (!ready) {
                initializationFailed = true
                activeUtteranceId?.let { fail(it) }
            }
        }
    }

    private fun fail(id: String) {
        activeUtteranceId = null
        pendingText = null
        chunkIds = emptyList()
        _state.value = TtsState.Error(id)
    }

    private fun complete(utteranceId: String?, failed: Boolean = false) {
        synchronized(utteranceLock) {
            val id = activeUtteranceId ?: return
            if (utteranceId == null || utteranceId !in chunkIds) return
            if (failed) {
                fail(id)
                handler.post { tts.stop() }
                return
            }
            if (utteranceId != chunkIds.last()) return
            activeUtteranceId = null
            chunkIds = emptyList()
            _state.value = TtsState.Done(id)
        }
    }

    // The init callback fires asynchronously once the engine is ready, by which point `tts` is
    // already assigned — safe despite referencing it inside its own initializer's lambda.
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        handler.post {
            synchronized(utteranceLock) {
                handler.removeCallbacks(startupTimeout)
                ready = status == TextToSpeech.SUCCESS
                initializationFailed = !ready
                if (ready) runCatching { configureVoice() }
                val id = activeUtteranceId
                if (id != null) {
                    if (ready) submit(pendingText.orEmpty(), id) else fail(id)
                }
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // speak() publishes Speaking before submitting to the engine.
                }

                override fun onDone(utteranceId: String?) {
                    complete(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    complete(utteranceId, failed = true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    complete(utteranceId, failed = true)
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
        synchronized(utteranceLock) {
            chunkIds = emptyList()
            tts.stop()
            activeUtteranceId = utteranceId
            _state.value = TtsState.Speaking(utteranceId)
            when {
                initializationFailed -> fail(utteranceId)
                ready -> submit(text, utteranceId)
                else -> {
                    pendingText = text
                    handler.removeCallbacks(startupTimeout)
                    handler.postDelayed(startupTimeout, 15_000L)
                }
            }
        }
    }

    private fun submit(text: String, utteranceId: String) {
        pendingText = null
        val chunks = speechChunks(text, TextToSpeech.getMaxSpeechInputLength())
        if (chunks.isEmpty()) {
            fail(utteranceId)
            return
        }
        val token = UUID.randomUUID().toString()
        chunkIds = chunks.indices.map { "$token:$it" }
        for ((index, chunk) in chunks.withIndex()) {
            val result = runCatching {
                tts.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, chunkIds[index])
            }.getOrDefault(TextToSpeech.ERROR)
            if (result == TextToSpeech.ERROR) {
                fail(utteranceId)
                tts.stop()
                return
            }
        }
    }

    override fun stop() {
        synchronized(utteranceLock) {
            activeUtteranceId = null
            pendingText = null
            chunkIds = emptyList()
            handler.removeCallbacks(startupTimeout)
            _state.value = TtsState.Idle
        }
        tts.stop()
    }
}
