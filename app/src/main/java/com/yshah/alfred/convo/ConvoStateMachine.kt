package com.yshah.alfred.convo

import com.yshah.alfred.capture.CaptureMode
import com.yshah.alfred.capture.CaptureState
import com.yshah.alfred.capture.SpeechCaptureController
import com.yshah.alfred.capture.TtsController
import com.yshah.alfred.capture.TtsState
import com.yshah.alfred.network.WebhookClient
import com.yshah.alfred.network.WebhookResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class ConvoState {
    data object Idle : ConvoState()
    data object Listening : ConvoState()
    data class PartialTranscript(val text: String) : ConvoState()
    data object Sending : ConvoState()
    data class Speaking(val responseText: String) : ConvoState()
    data class Error(val message: String) : ConvoState()
    data object Ended : ConvoState()
}

private const val MAX_CONSECUTIVE_ERRORS = 2

/**
 * Listening -> Sending -> Speaking -> Listening loop. Deliberately does NOT go through
 * WebhookForegroundService's 300s notify-later path — the user is actively waiting for a spoken
 * reply, so a "notification arrives later" UX would be wrong here. Uses
 * WebhookClient.sendConvoTurn's short (~20s) in-session timeout instead, and turns
 * errors/timeouts into a spoken in-loop message rather than a deferred notification — see the
 * plan's convo-mode timeout-tension note.
 */
@Singleton
class ConvoStateMachine @Inject constructor(
    private val speechCaptureController: SpeechCaptureController,
    private val ttsController: TtsController,
    private val webhookClient: WebhookClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<ConvoState>(ConvoState.Idle)
    val state: StateFlow<ConvoState> = _state

    private var sessionId: String = UUID.randomUUID().toString()
    private var consecutiveErrors = 0
    private var captureJob: Job? = null
    private var ttsJob: Job? = null

    fun startConversation() {
        sessionId = UUID.randomUUID().toString()
        consecutiveErrors = 0
        listen()
    }

    /** Interrupts TTS mid-speech and immediately starts listening for the next turn. */
    fun bargeIn() {
        ttsJob?.cancel()
        ttsController.stop()
        listen()
    }

    fun endConversation() {
        captureJob?.cancel()
        ttsJob?.cancel()
        speechCaptureController.cancel()
        ttsController.stop()
        _state.value = ConvoState.Ended
    }

    private fun listen() {
        _state.value = ConvoState.Listening
        captureJob?.cancel()
        captureJob = scope.launch {
            speechCaptureController.state.collect { captureState ->
                when (captureState) {
                    is CaptureState.PartialTranscript ->
                        _state.value = ConvoState.PartialTranscript(captureState.text)
                    is CaptureState.Finished -> {
                        captureJob?.cancel()
                        onTranscript(captureState.finalText)
                    }
                    is CaptureState.Error -> {
                        captureJob?.cancel()
                        onTurnError("Didn't catch that.")
                    }
                    else -> {}
                }
            }
        }
        speechCaptureController.start(CaptureMode.AUTO_STOP)
    }

    private fun onTranscript(text: String) {
        if (text.isBlank()) {
            listen()
            return
        }
        _state.value = ConvoState.Sending
        scope.launch {
            when (val result = webhookClient.sendConvoTurn(text, sessionId)) {
                is WebhookResult.Success -> {
                    consecutiveErrors = 0
                    speak(result.response.responseText ?: result.response.message ?: "Okay.")
                }
                else -> onTurnError("Sorry, that's taking too long.")
            }
        }
    }

    private fun speak(text: String) {
        _state.value = ConvoState.Speaking(text)
        ttsJob?.cancel()
        ttsJob = scope.launch {
            ttsController.state.collect { ttsState ->
                if (ttsState is TtsState.Done || ttsState is TtsState.Error) {
                    ttsJob?.cancel()
                    listen()
                }
            }
        }
        ttsController.speak(text)
    }

    private fun onTurnError(message: String) {
        consecutiveErrors++
        _state.value = ConvoState.Error(message)
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            endConversation()
        } else {
            speak(message)
        }
    }
}
