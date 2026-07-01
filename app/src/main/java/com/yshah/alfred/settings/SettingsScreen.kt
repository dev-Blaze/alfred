package com.yshah.alfred.settings

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yshah.alfred.history.HistoryActivity
import com.yshah.alfred.network.ConnectionTestResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Alfred Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = uiState.webhookUrl,
                onValueChange = viewModel::onWebhookUrlChanged,
                label = { Text("n8n Webhook URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            AuthSchemeSelector(selected = uiState.authScheme, onSelected = viewModel::onAuthSchemeChanged)

            if (uiState.authScheme == AuthScheme.CUSTOM_HEADER) {
                OutlinedTextField(
                    value = uiState.authHeaderName,
                    onValueChange = viewModel::onAuthHeaderNameChanged,
                    label = { Text("Header name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (uiState.authScheme != AuthScheme.NONE) {
                OutlinedTextField(
                    value = uiState.authSecret,
                    onValueChange = viewModel::onAuthSecretChanged,
                    label = { Text(secretLabelFor(uiState.authScheme)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = viewModel::onTestConnection,
                    enabled = !uiState.isTestingConnection,
                ) { Text("Test connection") }
                Button(
                    onClick = viewModel::onSave,
                    enabled = !uiState.isSaving,
                ) { Text("Save") }
            }

            uiState.testResult?.let { result ->
                val (text, color) = when (result) {
                    is ConnectionTestResult.Success ->
                        "Connected (HTTP ${result.httpStatusCode})" to MaterialTheme.colorScheme.primary
                    is ConnectionTestResult.Failure ->
                        "Failed: ${result.message}" to MaterialTheme.colorScheme.error
                }
                Text(text = text, color = color)
            }

            HorizontalDivider()
            BatteryOptimizationRow()

            HorizontalDivider()
            val context = LocalContext.current
            OutlinedButton(onClick = { context.startActivity(Intent(context, HistoryActivity::class.java)) }) {
                Text("View history")
            }
        }
    }
}

/**
 * Samsung's battery management is known to re-kill background processes even for apps with an
 * active foreground service — a full exemption isn't guaranteed, but this closes the one gap
 * the app can actually request. The rest (Device Care > Battery > Never sleeping apps) is a
 * manual One UI setting the user has to set themselves, and may need to be redone after OS
 * updates — see the plan's foreground-service note.
 */
@Composable
private fun BatteryOptimizationRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isExempt by remember {
        mutableStateOf(
            context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isExempt = context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column {
        Text("Battery optimization", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (isExempt) {
                "Alfred is exempt — background delivery should survive Samsung's battery management."
            } else {
                "Not exempt yet — Samsung may kill task/note delivery after you switch apps."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!isExempt) {
            Button(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            "package:${context.packageName}".toUri(),
                        ),
                    )
                },
            ) { Text("Request exemption") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthSchemeSelector(
    selected: AuthScheme,
    onSelected: (AuthScheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = labelFor(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text("Authentication") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AuthScheme.entries.forEach { scheme ->
                DropdownMenuItem(
                    text = { Text(labelFor(scheme)) },
                    onClick = {
                        onSelected(scheme)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun labelFor(scheme: AuthScheme): String = when (scheme) {
    AuthScheme.NONE -> "None"
    AuthScheme.BEARER -> "Bearer token"
    AuthScheme.BASIC -> "Basic auth"
    AuthScheme.CUSTOM_HEADER -> "Custom header"
}

private fun secretLabelFor(scheme: AuthScheme): String = when (scheme) {
    AuthScheme.BEARER -> "Token"
    AuthScheme.BASIC -> "username:password"
    AuthScheme.CUSTOM_HEADER -> "Header value"
    AuthScheme.NONE -> ""
}
