package com.yshah.aide.capture

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

class AndroidTtsController(context: Context) : TtsController {

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    override val state: StateFlow<TtsState> = _state

    // The init callback fires asynchronously once the engine is ready, by which point `tts` is
    // already assigned — safe despite referencing it inside its own initializer's lambda.
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
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

    override fun speak(text: String, utteranceId: String) {
        // QUEUE_FLUSH — convo mode should never queue a stale response behind a new one.
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun stop() {
        tts.stop()
    }
}
