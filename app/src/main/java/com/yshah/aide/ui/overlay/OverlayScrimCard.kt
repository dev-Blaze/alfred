package com.yshah.aide.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared bottom-sheet scrim+card shell used by both MainActivity's mode-switcher overlay and
 * AideVoiceInteractionSession's simpler retained-infrastructure overlay (see the plan's "Samsung
 * side button" finding for why there are two content composables sharing this shell).
 */
@Composable
fun OverlayScrimCard(
    onScrimClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onScrimClick,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                // Keeps the card above system bars (e.g. Samsung's gesture/nav bar) instead of
                // drawing behind them, since MainActivity opts into edge-to-edge content.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 24.dp)
                // Consumes taps so they don't fall through to the scrim's dismiss-on-click above.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp), content = content)
        }
    }
}
