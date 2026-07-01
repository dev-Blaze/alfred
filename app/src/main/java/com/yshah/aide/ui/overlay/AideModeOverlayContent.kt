package com.yshah.aide.ui.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yshah.aide.assistant.AssistantMode
import com.yshah.aide.capture.CaptureState
import com.yshah.aide.convo.ConvoState
import com.yshah.aide.ui.components.ListeningIndicator
import com.yshah.aide.ui.components.ModeSwitcher

@Composable
fun AideModeOverlayContent(
    activeMode: AssistantMode,
    captureState: CaptureState,
    convoState: ConvoState,
    onModeSelected: (AssistantMode) -> Unit,
    onScrimClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onMicTapped: () -> Unit,
    onEndConversation: () -> Unit,
) {
    OverlayScrimCard(onScrimClick = onScrimClick) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalIconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ModeSwitcher(activeMode = activeMode, onModeSelected = onModeSelected, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(20.dp))
        when (activeMode) {
            AssistantMode.TASK -> VoiceCaptureContent(
                captureState = captureState,
                showStopButton = false,
                onMicTapped = onMicTapped,
            )
            AssistantMode.NOTE -> VoiceCaptureContent(
                captureState = captureState,
                showStopButton = true,
                onMicTapped = onMicTapped,
            )
            AssistantMode.CONVO -> ConvoModeContent(
                convoState = convoState,
                onMicTapped = onMicTapped,
                onEndConversation = onEndConversation,
            )
        }
    }
}

@Composable
private fun VoiceCaptureContent(
    captureState: CaptureState,
    showStopButton: Boolean,
    onMicTapped: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        when (captureState) {
            is CaptureState.Listening, is CaptureState.PartialTranscript -> {
                ListeningIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                val partial = (captureState as? CaptureState.PartialTranscript)?.text.orEmpty()
                Text(text = partial.ifBlank { "Listening…" }, style = MaterialTheme.typography.bodyLarge)
                if (showStopButton) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onMicTapped) { Text("Stop") }
                }
            }
            is CaptureState.Error -> {
                Text(text = captureState.message, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(12.dp))
                MicButton(onClick = onMicTapped)
            }
            else -> MicButton(onClick = onMicTapped)
        }
    }
}

@Composable
private fun ConvoModeContent(
    convoState: ConvoState,
    onMicTapped: () -> Unit,
    onEndConversation: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        when (convoState) {
            is ConvoState.Idle, is ConvoState.Ended -> {
                Text("Tap to start a conversation", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(12.dp))
                MicButton(onClick = onMicTapped)
            }
            is ConvoState.Listening, is ConvoState.PartialTranscript -> {
                ListeningIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                val partial = (convoState as? ConvoState.PartialTranscript)?.text.orEmpty()
                Text(text = partial.ifBlank { "Listening…" }, style = MaterialTheme.typography.bodyLarge)
            }
            is ConvoState.Sending -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Thinking…")
            }
            is ConvoState.Speaking -> {
                Text(text = convoState.responseText, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onMicTapped) { Text("Interrupt") }
            }
            is ConvoState.Error -> {
                Text(text = convoState.message, color = MaterialTheme.colorScheme.error)
            }
        }
        if (convoState !is ConvoState.Idle && convoState !is ConvoState.Ended) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onEndConversation) { Text("End conversation") }
        }
    }
}

@Composable
private fun MicButton(onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, modifier = Modifier.size(64.dp)) {
        Icon(imageVector = Icons.Filled.Mic, contentDescription = "Start listening", modifier = Modifier.size(32.dp))
    }
}
