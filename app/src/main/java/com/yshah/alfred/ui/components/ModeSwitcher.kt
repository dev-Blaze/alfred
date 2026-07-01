package com.yshah.alfred.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.yshah.alfred.assistant.AssistantMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSwitcher(
    activeMode: AssistantMode,
    onModeSelected: (AssistantMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = AssistantMode.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == activeMode,
                onClick = { onModeSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                icon = {},
                label = { Icon(imageVector = iconFor(mode), contentDescription = labelFor(mode)) },
            )
        }
    }
}

private fun iconFor(mode: AssistantMode): ImageVector = when (mode) {
    AssistantMode.TASK -> Icons.Filled.Checklist
    AssistantMode.NOTE -> Icons.Filled.Mic
    AssistantMode.CONVO -> Icons.Filled.Forum
}

private fun labelFor(mode: AssistantMode): String = when (mode) {
    AssistantMode.TASK -> "Task"
    AssistantMode.NOTE -> "Note"
    AssistantMode.CONVO -> "Convo"
}
