package com.yshah.alfred.ui.overlay

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yshah.alfred.assistant.AssistantMode
import com.yshah.alfred.capture.CaptureMode
import com.yshah.alfred.capture.CaptureState
import com.yshah.alfred.capture.SpeechCaptureController
import com.yshah.alfred.convo.ConvoState
import com.yshah.alfred.convo.ConvoStateMachine
import com.yshah.alfred.prefs.ModePreferences
import com.yshah.alfred.settings.SecureSettingsStore
import com.yshah.alfred.webhook.WebhookForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

private const val NO_WEBHOOK_MESSAGE = "Set a webhook URL in Settings first"

data class OverlayUiState(
    val activeMode: AssistantMode = AssistantMode.TASK,
    val captureState: CaptureState = CaptureState.Idle,
    val convoState: ConvoState = ConvoState.Idle,
    val shouldDismiss: Boolean = false,
)

@HiltViewModel
class AlfredOverlayViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val modePreferences: ModePreferences,
    private val secureSettingsStore: SecureSettingsStore,
    private val speechCaptureController: SpeechCaptureController,
    private val convoStateMachine: ConvoStateMachine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(activeMode = modePreferences.lastMode.first())
        }
        viewModelScope.launch {
            speechCaptureController.state.collect { captureState ->
                // Convo mode drives this same controller through ConvoStateMachine instead.
                if (_uiState.value.activeMode == AssistantMode.CONVO) return@collect
                _uiState.value = _uiState.value.copy(captureState = captureState)
                if (captureState is CaptureState.Finished) {
                    handOffCapturedText(captureState.finalText)
                }
            }
        }
        viewModelScope.launch {
            convoStateMachine.state.collect { convoState ->
                _uiState.value = _uiState.value.copy(convoState = convoState)
            }
        }
    }

    fun onModeSelected(mode: AssistantMode) {
        speechCaptureController.cancel()
        if (_uiState.value.activeMode == AssistantMode.CONVO && mode != AssistantMode.CONVO) {
            convoStateMachine.endConversation()
        }
        _uiState.value = _uiState.value.copy(
            activeMode = mode,
            captureState = CaptureState.Idle,
            convoState = ConvoState.Idle,
        )
        viewModelScope.launch { modePreferences.setLastMode(mode) }
    }

    fun onMicTapped() {
        val current = _uiState.value
        if (current.activeMode == AssistantMode.CONVO) {
            onConvoMicTapped()
            return
        }
        when (current.captureState) {
            is CaptureState.Listening, is CaptureState.PartialTranscript -> {
                if (current.activeMode == AssistantMode.NOTE) {
                    speechCaptureController.stop()
                }
                // Task mode auto-stops itself on a pause in speech; nothing to do here for it.
            }
            else -> startCaptureIfConfigured(current.activeMode)
        }
    }

    /** Checked before capture starts, not just before sending — no point making the user talk
     * through a whole task/note/turn only to fail at the very end for a missing webhook URL. */
    private fun startCaptureIfConfigured(mode: AssistantMode) {
        viewModelScope.launch {
            if (secureSettingsStore.currentSettingsSnapshot().webhookUrl.isBlank()) {
                _uiState.value = _uiState.value.copy(captureState = CaptureState.Error(-1, NO_WEBHOOK_MESSAGE))
                return@launch
            }
            val captureMode = if (mode == AssistantMode.TASK) CaptureMode.AUTO_STOP else CaptureMode.MANUAL_STOP
            speechCaptureController.start(captureMode)
        }
    }

    private fun onConvoMicTapped() {
        when (_uiState.value.convoState) {
            is ConvoState.Idle, is ConvoState.Ended -> {
                viewModelScope.launch {
                    if (secureSettingsStore.currentSettingsSnapshot().webhookUrl.isBlank()) {
                        _uiState.value = _uiState.value.copy(convoState = ConvoState.Error(NO_WEBHOOK_MESSAGE))
                        return@launch
                    }
                    convoStateMachine.startConversation()
                }
            }
            is ConvoState.Speaking -> convoStateMachine.bargeIn()
            else -> {} // mid-turn (listening/sending) — ignore extra taps
        }
    }

    fun onEndConversation() {
        convoStateMachine.endConversation()
    }

    /** Called when the user denies RECORD_AUDIO/POST_NOTIFICATIONS — surfaces guidance instead of
     * silently doing nothing, since a repeated system prompt won't show once denied. */
    fun onCapturePermissionDenied() {
        _uiState.value = _uiState.value.copy(
            captureState = CaptureState.Error(-1, "Enable microphone access in Settings > Apps > Alfred > Permissions"),
        )
    }

    /**
     * Fires the webhook off to WebhookForegroundService — started here, while the overlay is
     * still visible, so the background-service start is covered by the visible-transition
     * exemption (see the plan's foreground-service note) — then dismisses immediately. The
     * result arrives later as a notification, not inline in the overlay.
     */
    private fun handOffCapturedText(text: String) {
        if (text.isBlank()) {
            _uiState.value = _uiState.value.copy(captureState = CaptureState.Idle)
            return
        }
        val type = if (_uiState.value.activeMode == AssistantMode.TASK) "task" else "note"
        WebhookForegroundService.start(appContext, text = text, type = type, sessionId = UUID.randomUUID().toString())
        _uiState.value = _uiState.value.copy(captureState = CaptureState.Idle, shouldDismiss = true)
    }

    override fun onCleared() {
        speechCaptureController.cancel()
        convoStateMachine.endConversation()
        super.onCleared()
    }
}
