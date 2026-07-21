package com.point.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Point's design system — an intentional indigo/violet palette (not the M3
 * baseline default) so the app reads as its own thing. Only the roles the Bubble
 * UI actually uses are overridden; the rest fall back to sensible M3 defaults.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF5A4AE3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF17008A),
    secondary = Color(0xFF615A8A),
    secondaryContainer = Color(0xFFE9DEFF),
    onSecondaryContainer = Color(0xFF1D1147),
    tertiary = Color(0xFFB4436C),
    background = Color(0xFFFDFAFF),
    onBackground = Color(0xFF1B1B22),
    surface = Color(0xFFFDFAFF),
    onSurface = Color(0xFF1B1B22),
    surfaceVariant = Color(0xFFEBE6F3),
    onSurfaceVariant = Color(0xFF615B70),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCBBEFF),
    onPrimary = Color(0xFF2B1A76),
    primaryContainer = Color(0xFF42319E),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFC8BFF3),
    secondaryContainer = Color(0xFF484068),
    onSecondaryContainer = Color(0xFFE9DEFF),
    tertiary = Color(0xFFF7B1C8),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF48455A),
    onSurfaceVariant = Color(0xFFCAC3DC),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun PointTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
