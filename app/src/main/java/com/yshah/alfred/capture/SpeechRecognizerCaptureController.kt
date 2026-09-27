package com.yshah.alfred.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val AUTO_STOP_SILENCE_DEBOUNCE_MS = 1400L

internal fun recoveredNoteAtCutoff(
    mode: CaptureMode,
    stoppedByUser: Boolean,
    error: Int,
    accumulatedText: String,
): CaptureState.Finished? = if (
    mode == CaptureMode.MANUAL_STOP && stoppedByUser && accumulatedText.isNotBlank() &&
    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
) CaptureState.Finished(accumulatedText) else null

/**
 * Auto-stop is driven by our own debounce on partial results, not the
 * EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS intent extras — those are inconsistently
 * honored across OEM recognizer services (Samsung's included), so relying on them would make
 * task-mode auto-stop unreliable on exactly the device this app targets.
 *
 * Manual-stop (note) sessions transparently restart and concatenate transcripts if the OEM
 * recognizer service cuts the session short (a real per-session duration cap on some
 * implementations) before the user taps stop, so a long note isn't silently truncated.
 */
class SpeechRecognizerCaptureController(private val context: Context) : SpeechCaptureController {

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    override val state: StateFlow<CaptureState> = _state

    private val mainHandler = Handler(Looper.getMainLooper())
    private val debounceRunnable = Runnable { finishListening() }
    private val deadlineRunnable = Runnable {
        val text = joinAccumulated(latestPartial).trim()
        destroyRecognizer()
        _state.value = if (text.isNotBlank()) CaptureState.Finished(text, requiresReview = true)
        else CaptureState.Error(-1, "Recognition timed out. Please retry.")
    }

    private var recognizer: SpeechRecognizer? = null
    private var mode: CaptureMode = CaptureMode.AUTO_STOP
    private var accumulatedText: String = ""
    private var stoppedByUser = false
    private var latestPartial = ""
    private var generation = 0

    override fun start(mode: CaptureMode) {
        this.mode = mode
        accumulatedText = ""
        stoppedByUser = false
        startNewRecognizerSession()
    }

    override fun stop() {
        finishListening()
    }

    override fun cancel() {
        stoppedByUser = true
        mainHandler.removeCallbacks(debounceRunnable)
        destroyRecognizer()
        _state.value = CaptureState.Idle
    }

    private fun startNewRecognizerSession() {
        destroyRecognizer()
        latestPartial = ""
        val session = generation
        // The on-device ("Soda") recognizer was confirmed on-device to process a full ~5s window
        // and report an empty result even with clear speech present — the standard recognizer
        // (which can use the on-device model internally when appropriate, or fall back to cloud)
        // does not have this problem. Don't switch back to createOnDeviceSpeechRecognizer()
        // without re-verifying against a real device first.
        try {
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(listener(session))

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        _state.value = CaptureState.Listening
        recognizer?.startListening(intent)
        mainHandler.postDelayed(deadlineRunnable, 120_000L)
        } catch (_: Exception) {
            destroyRecognizer()
            _state.value = CaptureState.Error(-1, "Could not start microphone. Check permission and retry.")
        }
    }

    private fun destroyRecognizer() {
        generation++
        mainHandler.removeCallbacks(debounceRunnable)
        mainHandler.removeCallbacks(deadlineRunnable)
        recognizer?.setRecognitionListener(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun finishListening() {
        if (recognizer == null || stoppedByUser) return
        mainHandler.removeCallbacks(debounceRunnable)
        stoppedByUser = true
        recognizer?.stopListening()
        mainHandler.removeCallbacks(deadlineRunnable)
        mainHandler.postDelayed(deadlineRunnable, 5_000L)
    }

    private fun joinAccumulated(latest: String): String =
        if (accumulatedText.isEmpty()) latest else "$accumulatedText $latest"

    private fun listener(session: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (session != generation) return
            mainHandler.removeCallbacks(deadlineRunnable)
            mainHandler.postDelayed(deadlineRunnable, 5_000L)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (session != generation || stoppedByUser) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) {
                latestPartial = text
                _state.value = CaptureState.PartialTranscript(joinAccumulated(text))
            }
            if (mode == CaptureMode.AUTO_STOP) {
                mainHandler.removeCallbacks(debounceRunnable)
                mainHandler.postDelayed(debounceRunnable, AUTO_STOP_SILENCE_DEBOUNCE_MS)
            }
        }

        override fun onResults(results: Bundle?) {
            if (session != generation) return
            mainHandler.removeCallbacks(debounceRunnable)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            val combined = joinAccumulated(text.ifBlank { latestPartial }).trim()
            if (text.isBlank() && latestPartial.isNotBlank()) {
                destroyRecognizer()
                _state.value = CaptureState.Finished(combined, requiresReview = true)
                return
            }

            if (mode == CaptureMode.MANUAL_STOP && !stoppedByUser) {
                accumulatedText = combined
                startNewRecognizerSession()
                return
            }

            destroyRecognizer()
            _state.value = CaptureState.Finished(combined)
        }

        override fun onError(error: Int) {
            if (session != generation) return
            mainHandler.removeCallbacks(debounceRunnable)
            val isRecoverableCutoff = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_NO_MATCH
            if (latestPartial.isNotBlank()) {
                val text = joinAccumulated(latestPartial).trim()
                destroyRecognizer()
                _state.value = CaptureState.Finished(text, requiresReview = true)
                return
            }
            if (mode == CaptureMode.MANUAL_STOP && !stoppedByUser && isRecoverableCutoff) {
                startNewRecognizerSession()
                return
            }
            destroyRecognizer()
            _state.value = recoveredNoteAtCutoff(mode, stoppedByUser, error, accumulatedText)
                ?: CaptureState.Error(error, errorMessage(error))
        }
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
        else -> "Speech recognition error ($error)"
    }
}
