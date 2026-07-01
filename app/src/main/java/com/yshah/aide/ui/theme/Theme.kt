package com.yshah.aide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AideDarkColorScheme = darkColorScheme(
    primary = AidePrimary,
    onPrimary = AideOnPrimary,
    secondary = AideSecondary,
    onSecondary = AideOnSecondary,
    background = AideBackground,
    onBackground = AideOnBackground,
    surface = AideSurface,
    onSurface = AideOnSurface,
    error = AideError,
    onError = AideOnError,
)

private val AideLightColorScheme = lightColorScheme(
    primary = AideOnPrimary,
    onPrimary = AidePrimary,
    secondary = AideOnSecondary,
    onSecondary = AideSecondary,
)

/**
 * Dark-first by default to match the assistant-overlay aesthetic (clean, consistent regardless
 * of wallpaper). Dynamic/Material-You color is intentionally not wired in for the same reason.
 */
@Composable
fun AideTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) AideDarkColorScheme else AideLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AideTypography,
        content = content,
    )
}
