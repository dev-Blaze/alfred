package com.yshah.alfred.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yshah.alfred.capture.TtsController
import com.yshah.alfred.network.ConnectionTestResult
import com.yshah.alfred.network.WebhookClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class SettingsUiState(
    val webhookUrl: String = "",
    val authScheme: AuthScheme = AuthScheme.NONE,
    val authHeaderName: String = "Authorization",
    val authSecret: String = "",
    val isSaving: Boolean = false,
    val isTestingConnection: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    val credentialUnavailable: Boolean = false,
    val saveMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SecureSettingsStore,
    private val webhookClient: WebhookClient,
    private val ttsController: TtsController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = settingsStore.settings.first()
            _uiState.value = SettingsUiState(
                webhookUrl = saved.webhookUrl,
                authScheme = saved.authScheme,
                authHeaderName = saved.authHeaderName,
                authSecret = saved.authSecret,
                credentialUnavailable = saved.credentialUnavailable,
            )
        }
    }

    fun onWebhookUrlChanged(url: String) {
        _uiState.value = _uiState.value.copy(webhookUrl = url)
    }

    fun onAuthSchemeChanged(scheme: AuthScheme) {
        _uiState.value = _uiState.value.copy(authScheme = scheme)
    }

    fun onAuthHeaderNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(authHeaderName = name)
    }

    fun onAuthSecretChanged(secret: String) {
        _uiState.value = _uiState.value.copy(authSecret = secret, credentialUnavailable = false)
    }

    fun onSave() {
        if (_uiState.value.isSaving) return
        val draft = draftSettings()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, saveMessage = null)
            try {
                settingsStore.save(draft)
                _uiState.value = _uiState.value.copy(saveMessage = "Settings saved")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(saveMessage = if (e is IllegalArgumentException) e.message else "Could not save settings. Re-enter authentication and try again.")
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }

    fun onTestConnection() {
        if (_uiState.value.isTestingConnection) return
        val draft = draftSettings()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingConnection = true, testResult = null)
            try {
                draft.validate()
                val result = webhookClient.testConnection(draft)
                _uiState.value = _uiState.value.copy(testResult = result)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(testResult = ConnectionTestResult.Failure(
                    if (e is IllegalArgumentException) e.message ?: "Invalid settings" else "Connection test failed"))
            } finally {
                _uiState.value = _uiState.value.copy(isTestingConnection = false)
            }
        }
    }

    fun onPreviewVoice() {
        ttsController.speak("Good evening. Alfred at your service — how may I help?")
    }

    private fun draftSettings(): WebhookSettings {
        val state = _uiState.value
        return WebhookSettings(state.webhookUrl, state.authScheme, state.authHeaderName, state.authSecret, state.credentialUnavailable)
    }
}
