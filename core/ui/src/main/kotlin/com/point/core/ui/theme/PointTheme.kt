package com.point.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Point's palette. The app ships **dark** (the portal redesign — референсы владельца): a deep
 * near-black field with the icon's violet-blue neon accent, so the whole glowing UI reads as one
 * language. `livingBackground` then drifts primary/tertiary over the background — the ambient glow
 * of the mock-ups. The light scheme is kept for a future theme toggle (экран настроек). Only the
 * roles the Bubble UI uses are set; per-capability bubble colours live in BubbleIcons.bubbleColor.
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

// The dark palette of the mock-ups: deep near-black + the icon's violet-blue neon accent (no orange).
private val DarkColors = darkColorScheme(
    primary = Purple, // the portal/icon accent — unifies buttons, aura, active states with the glow
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF2A2350), // dark violet for banners / chips
    onPrimaryContainer = Color(0xFFD6CCFF),
    secondary = Color(0xFFC7CEDE),
    onSecondary = Ink,
    secondaryContainer = Color(0xFF1D1D2A),
    onSecondaryContainer = Color(0xFFE6E9F0),
    tertiary = Color(0xFF6EA8FF), // portal blue (AI ring / secondary neon)
    background = Color(0xFF0A0A12), // deep near-black field
    onBackground = Color(0xFFE9ECF4),
    surface = Color(0xFF14141F), // dark card, faint violet tint
    onSurface = Color(0xFFE9ECF4),
    surfaceVariant = Color(0xFF1E1E2C),
    onSurfaceVariant = Color(0xFF9AA3B7),
    outline = Color(0xFF2E2E40),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun PointTheme(
    darkTheme: Boolean = true, // the app is dark (portal redesign); light kept for a future toggle
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PointTypography,
        content = content,
    )
}
