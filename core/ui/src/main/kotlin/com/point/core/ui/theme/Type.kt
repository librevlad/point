package com.point.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The Point type system. One place decides weight/size/tracking for every screen,
 * so the app reads as *designed* rather than default M3.
 *
 * [Brand] is the single font-family seam: today it is the platform sans-serif, so
 * the scale below is what gives the cohesion. To ship the brand faces (Manrope for
 * text, Unbounded for display), drop their `.ttf` into `core/ui/src/main/res/font/`
 * and point [Brand] (and an optional [Display]) at a `FontFamily(Font(R.font.…))` —
 * every screen picks them up with no other change.
 */
private val Brand = FontFamily.SansSerif
private val Display = FontFamily.SansSerif // Unbounded drops in here

val PointTypography = Typography(
    // Display / headline — confident, tight, a touch condensed. Used for screen titles
    // ("Следующее действие", "Обработка", the busy label).
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 31.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp),

    // Titles — the workhorses (bubble labels, section headers).
    titleLarge = TextStyle(fontFamily = Brand, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Brand, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = Brand, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),

    // Body — calm and readable (text previews, messages).
    bodyLarge = TextStyle(fontFamily = Brand, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontFamily = Brand, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),

    // Labels — buttons, chips, the small captions.
    labelLarge = TextStyle(fontFamily = Brand, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Brand, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)
