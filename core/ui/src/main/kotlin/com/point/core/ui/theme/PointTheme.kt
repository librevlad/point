package com.point.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Point's brand palette (from the Point.dc.html design): ink `#0F1626` + orange
 * accent `#F5610F` on a light `#F4F5F7` surface. White cards, muted grey
 * secondary text. Only the roles the Bubble UI uses are set; the rest fall back
 * to M3 defaults. Per-capability bubble colours live in BubbleIcons.bubbleColor.
 */

// Brand anchors
private val Ink = Color(0xFF0F1626)
private val Orange = Color(0xFFF5610F)
private val Purple = Color(0xFF7C4DFF)

private val LightColors = lightColorScheme(
    primary = Orange,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE9DB),
    onPrimaryContainer = Color(0xFFB15A1E),
    secondary = Ink,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E7EC),
    onSecondaryContainer = Ink,
    tertiary = Purple,
    background = Color(0xFFF4F5F7),
    onBackground = Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEF0F3),
    onSurfaceVariant = Color(0xFF8A93A0),
    outline = Color(0xFFCFD3DA),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Orange,
    onPrimary = Color(0xFF3A1500),
    primaryContainer = Color(0xFF7A3300),
    onPrimaryContainer = Color(0xFFFFDCC7),
    secondary = Color(0xFFCBD3E1),
    onSecondary = Ink,
    secondaryContainer = Color(0xFF232838),
    onSecondaryContainer = Color(0xFFE6E9F0),
    tertiary = Color(0xFFB39DFF),
    background = Color(0xFF0F1116),
    onBackground = Color(0xFFE6E9F0),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = Color(0xFF242833),
    onSurfaceVariant = Color(0xFF9AA3B2),
    outline = Color(0xFF3A4152),
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
        typography = PointTypography,
        content = content,
    )
}
