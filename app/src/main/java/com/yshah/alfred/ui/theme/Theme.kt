package com.yshah.alfred.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AlfredDarkColorScheme = darkColorScheme(
    primary = AlfredPrimary,
    onPrimary = AlfredOnPrimary,
    secondary = AlfredSecondary,
    onSecondary = AlfredOnSecondary,
    background = AlfredBackground,
    onBackground = AlfredOnBackground,
    surface = AlfredSurface,
    onSurface = AlfredOnSurface,
    error = AlfredError,
    onError = AlfredOnError,
)

private val AlfredLightColorScheme = lightColorScheme(
    primary = AlfredOnPrimary,
    onPrimary = AlfredPrimary,
    secondary = AlfredOnSecondary,
    onSecondary = AlfredSecondary,
)

/**
 * Dark-first by default to match the assistant-overlay aesthetic (clean, consistent regardless
 * of wallpaper). Dynamic/Material-You color is intentionally not wired in for the same reason.
 */
@Composable
fun AlfredTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) AlfredDarkColorScheme else AlfredLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AlfredTypography,
        content = content,
    )
}
