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

// The design system (docs/design-system.png), tokens copied exactly: ФОН #0B0D10, ПОВЕРХНОСТЬ
// #14161C, ГРАНИЦЫ #242833, АКЦЕНТ1 (violet) #7B5CFF, АКЦЕНТ2 (cyan) #00E0FF, ТЕКСТ #FFFFFF,
// ВТОРИЧНЫЙ #A1A6B3. A deep near-black field with the portal's violet→cyan neon accents.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7B5CFF), // АКЦЕНТ1 — the portal/icon violet; buttons, aura, active states
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF211B3E), // dark violet for banners / chips
    onPrimaryContainer = Color(0xFFD6CCFF),
    secondary = Color(0xFFA1A6B3), // ВТОРИЧНЫЙ
    onSecondary = Ink,
    secondaryContainer = Color(0xFF1B1E27),
    onSecondaryContainer = Color(0xFFE6E9F0),
    tertiary = Color(0xFF00E0FF), // АКЦЕНТ2 — portal cyan (AI ring / secondary neon)
    background = Color(0xFF0B0D10), // ФОН — deep near-black field
    onBackground = Color(0xFFFFFFFF), // ТЕКСТ
    surface = Color(0xFF14161C), // ПОВЕРХНОСТЬ — dark card
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1B1E27), // one step up from surface: icon plates, elevated rows
    onSurfaceVariant = Color(0xFFA1A6B3), // ВТОРИЧНЫЙ — captions, sublines, secondary labels
    outline = Color(0xFF242833), // ГРАНИЦЫ
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
