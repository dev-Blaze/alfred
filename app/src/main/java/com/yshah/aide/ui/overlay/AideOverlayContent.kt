package com.yshah.aide.ui.overlay

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Simple content used by AideVoiceInteractionSession (retained infrastructure — see the plan's
 * "Samsung side button" finding). MainActivity uses the richer AideModeOverlayContent instead.
 */
@Composable
fun AideOverlayContent(isUnlocked: Boolean, onScrimClick: () -> Unit) {
    OverlayScrimCard(onScrimClick = onScrimClick) {
        Text(
            text = if (isUnlocked) "Hello from Aide" else "Unlock your phone to use Aide",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
