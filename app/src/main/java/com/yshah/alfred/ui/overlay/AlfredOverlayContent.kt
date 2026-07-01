package com.yshah.alfred.ui.overlay

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Simple content used by AlfredVoiceInteractionSession (retained infrastructure — see the plan's
 * "Samsung side button" finding). MainActivity uses the richer AlfredModeOverlayContent instead.
 */
@Composable
fun AlfredOverlayContent(isUnlocked: Boolean, onScrimClick: () -> Unit) {
    OverlayScrimCard(onScrimClick = onScrimClick) {
        Text(
            text = if (isUnlocked) "Hello from Alfred" else "Unlock your phone to use Alfred",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
