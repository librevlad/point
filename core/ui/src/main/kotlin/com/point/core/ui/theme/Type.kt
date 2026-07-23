@file:OptIn(ExperimentalTextApi::class)

package com.point.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.point.core.ui.R

/**
 * The Point type system. One place decides weight/size/tracking for every screen,
 * so the app reads as *designed* rather than default M3.
 *
 * [Brand] is Manrope (the text/UI face); [Display] is Unbounded (the characterful face
 * for brand/header moments). Both are variable `.ttf`s in `core/ui/src/main/res/font/`,
 * loaded per weight via [FontVariation] (API 26+). Every screen picks them up through the
 * scale below — no other change.
 */
private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun unbounded(weight: Int) = Font(
    R.font.unbounded,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Brand = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700))
private val Display = FontFamily(unbounded(600), unbounded(700))

val PointTypography = Typography(
    // Display / headline — Unbounded, confident and characterful. Brand/header moments
    // (the "Point" wordmark, screen titles, consent/app-picker headers).
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 31.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp),

    // Titles — Manrope, the workhorses (bubble labels, object title, section headers).
    titleLarge = TextStyle(fontFamily = Brand, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Brand, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = Brand, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),

    // Body — Manrope, calm and readable (text previews, messages).
    bodyLarge = TextStyle(fontFamily = Brand, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontFamily = Brand, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),

    // Labels — Manrope, buttons/chips/captions.
    labelLarge = TextStyle(fontFamily = Brand, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Brand, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)
