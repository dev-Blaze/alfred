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
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject

private const val NO_WEBHOOK_MESSAGE = "Set a webhook URL in Settings first"

data class OverlayUiState(
    val activeMode: AssistantMode = AssistantMode.CONVO,
    val captureState: CaptureState = CaptureState.Idle,
    val convoState: ConvoState = ConvoState.Idle,
    val shouldDismiss: Boolean = false,
    val draftText: String? = null,
    val isEnqueuing: Boolean = false,
    val deliveryError: String? = null,
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
            speechCaptureController.state.collect { captureState ->
                // Convo mode drives this same controller through ConvoStateMachine instead.
                if (_uiState.value.activeMode == AssistantMode.CONVO) return@collect
                _uiState.value = _uiState.value.copy(captureState = captureState)
                if (captureState is CaptureState.Finished) {
                    _uiState.value = _uiState.value.copy(draftText = captureState.finalText.takeIf { it.isNotBlank() })
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
        if (_uiState.value.isEnqueuing || _uiState.value.draftText != null) return
        speechCaptureController.cancel()
        if (_uiState.value.activeMode == AssistantMode.CONVO && mode != AssistantMode.CONVO) {
            convoStateMachine.endConversation()
        }
        _uiState.value = _uiState.value.copy(
            activeMode = mode,
            captureState = CaptureState.Idle,
            convoState = ConvoState.Idle,
        )
        viewModelScope.launch {
            try { modePreferences.setLastMode(mode) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Selection still works for this session. */ }
        }
    }

    fun onMicTapped() {
        if (_uiState.value.isEnqueuing || _uiState.value.draftText != null) return
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
            val error = configurationError()
            if (_uiState.value.activeMode != mode) return@launch
            if (error != null) {
                _uiState.value = _uiState.value.copy(captureState = CaptureState.Error(-1, error))
                return@launch
            }
            val captureMode = if (mode == AssistantMode.TASK) CaptureMode.AUTO_STOP else CaptureMode.MANUAL_STOP
            speechCaptureController.start(captureMode)
        }
    }

    private fun onConvoMicTapped() {
        when (_uiState.value.convoState) {
            is ConvoState.Idle, is ConvoState.Ended, is ConvoState.Error -> {
                viewModelScope.launch {
                    val error = configurationError()
                    if (_uiState.value.activeMode != AssistantMode.CONVO) return@launch
                    if (error != null) {
                        _uiState.value = _uiState.value.copy(convoState = ConvoState.Error(error))
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
        val message = "Enable microphone access in Settings > Apps > Alfred > Permissions, then retry"
        _uiState.value = _uiState.value.copy(
            captureState = CaptureState.Error(-1, message),
            convoState = ConvoState.Error(message),
        )
    }

    private suspend fun configurationError(): String? = try {
        if (secureSettingsStore.currentSettingsSnapshot().webhookUrl.isBlank()) NO_WEBHOOK_MESSAGE else null
    } catch (e: CancellationException) { throw e
    } catch (_: Exception) { "Could not read webhook settings. Open Settings to check them, then retry." }

    fun onDraftChanged(text: String) {
        if (!_uiState.value.isEnqueuing) _uiState.value = _uiState.value.copy(draftText = text, deliveryError = null)
    }

    fun onCancelDraft() {
        if (_uiState.value.isEnqueuing) return
        speechCaptureController.cancel()
        _uiState.value = _uiState.value.copy(draftText = null, deliveryError = null, captureState = CaptureState.Idle)
    }

    fun onSendDraft() {
        if (_uiState.value.isEnqueuing) return
        val text = _uiState.value.draftText?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val type = if (_uiState.value.activeMode == AssistantMode.TASK) "task" else "note"
        _uiState.value = _uiState.value.copy(isEnqueuing = true, deliveryError = null)
        viewModelScope.launch {
            try {
                WebhookForegroundService.enqueue(appContext, text = text, type = type, sessionId = UUID.randomUUID().toString())
                _uiState.value = _uiState.value.copy(isEnqueuing = false, shouldDismiss = true)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isEnqueuing = false,
                    deliveryError = "Could not save for delivery. Your text is still here. Check device storage and retry.")
            }
        }
    }

    override fun onCleared() {
        speechCaptureController.cancel()
        convoStateMachine.endConversation()
        super.onCleared()
    }
}
