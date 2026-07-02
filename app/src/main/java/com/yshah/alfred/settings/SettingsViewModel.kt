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
import javax.inject.Inject

data class SettingsUiState(
    val webhookUrl: String = "",
    val authScheme: AuthScheme = AuthScheme.NONE,
    val authHeaderName: String = "Authorization",
    val authSecret: String = "",
    val isSaving: Boolean = false,
    val isTestingConnection: Boolean = false,
    val testResult: ConnectionTestResult? = null,
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
        _uiState.value = _uiState.value.copy(authSecret = secret)
    }

    fun onSave() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            persistCurrentState()
            _uiState.value = _uiState.value.copy(isSaving = false)
        }
    }

    fun onTestConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingConnection = true, testResult = null)
            persistCurrentState()
            val result = webhookClient.testConnection()
            _uiState.value = _uiState.value.copy(isTestingConnection = false, testResult = result)
        }
    }

    fun onPreviewVoice() {
        ttsController.speak("Good evening. Alfred at your service — how may I help?")
    }

    private suspend fun persistCurrentState() {
        val state = _uiState.value
        settingsStore.updateWebhookUrl(state.webhookUrl)
        settingsStore.updateAuth(state.authScheme, state.authHeaderName, state.authSecret)
    }
}
