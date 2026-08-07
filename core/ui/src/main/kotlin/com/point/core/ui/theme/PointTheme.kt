package com.point.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    primary = Color(0xFF7B5CFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF211B3E),
    onPrimaryContainer = Color(0xFFD6CCFF),
    secondary = Color(0xFFA1A6B3),
    onSecondary = Ink,
    secondaryContainer = Color(0xFF1B1E27),
    onSecondaryContainer = Color(0xFFE6E9F0),
    tertiary = Color(0xFF00E0FF),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF14161C),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1B1E27),
    onSurfaceVariant = Color(0xFFA1A6B3),
    outline = Color(0xFF242833),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun PointTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PointTypography,
        content = content,
    )
}
