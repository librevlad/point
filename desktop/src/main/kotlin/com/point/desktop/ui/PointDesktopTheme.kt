package com.point.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/**
 * Токены — мобильной тёмной темы (core:ui PointTheme.DarkColors): палитры поверхностей
 * не расходятся, приоритет у телефонной (решение владельца 2026-08-09).
 */
object PointColors {

    val canvas = Color(0xFF0B0D10)

    val window = Color(0xFF0B0D10)

    val surface = Color(0xFF14161C)

    val surfaceDeep = Color(0xFF1B1E27)

    val border = Color(0xFF242833)

    val text = Color(0xFFFFFFFF)

    val muted = Color(0xFFA1A6B3)

    val violet = Color(0xFF7B5CFF)

    val cyan = Color(0xFF00E0FF)
}

private val Unbounded = FontFamily(Font(resource = "unbounded.ttf", weight = FontWeight.Normal))

/**
 * Manrope — variable-шрифт с осью веса 200…800, и по умолчанию эта ось стоит на 200:
 * телефон уводит её на 400–700 явно (`core:ui` Type.kt), а ПК объявлял просто «файл» и потому
 * рисовал весь свой текст ExtraLight. Отсюда и «слишком тонкие шрифты на ПК» (#626) — не
 * оттенок вкуса, а начертание, которого дизайн не выбирал.
 *
 * Ось задаётся здесь через skia: desktop-обёртка `Font(resource = …)` вариаций не принимает,
 * а одно семейство держит одно начертание — поэтому вес выбирается семейством, не `fontWeight`.
 */
internal fun manropeFace(weight: Int): org.jetbrains.skia.Typeface {
    val bytes = PointColors::class.java.getResourceAsStream("/manrope.ttf")!!.use { it.readBytes() }
    return org.jetbrains.skia.FontMgr.default
        .makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes))!!
        .makeClone(org.jetbrains.skia.FontVariation("wght", weight.toFloat()))
}

private fun manrope(weight: Int): FontFamily =
    FontFamily(androidx.compose.ui.text.platform.Typeface(manropeFace(weight)))

private val Manrope = manrope(400)

/** На ступень плотнее — подписи, метки и вторые строки компакта (решение владельца, #626). */
private val ManropeDense = manrope(500)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val PointRasterization = androidx.compose.ui.text.PlatformTextStyle(
    null,
    androidx.compose.ui.text.PlatformParagraphStyle(
        androidx.compose.ui.text.FontRasterizationSettings(
            smoothing = androidx.compose.ui.text.FontSmoothing.AntiAlias,
            hinting = androidx.compose.ui.text.FontHinting.Full,
            subpixelPositioning = false,
            autoHintingForced = true,
        ),
    ),
)

object PointType {

    val display = TextStyle(
        fontFamily = Unbounded, fontSize = 28.sp, color = PointColors.text,
        platformStyle = PointRasterization,
    )

    val title = TextStyle(
        fontFamily = Unbounded, fontSize = 18.sp, color = PointColors.text,
        platformStyle = PointRasterization,
    )

    val body = TextStyle(
        fontFamily = Manrope, fontSize = 15.sp, color = PointColors.text,
        platformStyle = PointRasterization,
    )

    val small = TextStyle(
        fontFamily = ManropeDense, fontSize = 14.sp, color = PointColors.muted,
        platformStyle = PointRasterization,
    )

    val label = TextStyle(
        fontFamily = ManropeDense, fontSize = 12.sp,
        letterSpacing = 1.4.sp, color = PointColors.muted,
        platformStyle = PointRasterization,
    )

    val mono = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = PointColors.muted,
        platformStyle = PointRasterization,
    )
}

@Composable
fun PointDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PointColors.violet,
            onPrimary = PointColors.text,
            secondary = PointColors.cyan,
            background = PointColors.window,
            onBackground = PointColors.text,
            surface = PointColors.surface,
            onSurface = PointColors.text,
            surfaceVariant = PointColors.surfaceDeep,
            onSurfaceVariant = PointColors.muted,
            outline = PointColors.border,
        ),
        typography = Typography(
            headlineSmall = PointType.display,
            titleMedium = PointType.title,
            bodyMedium = PointType.body,
            bodySmall = PointType.small,
            labelSmall = PointType.label,
        ),
        content = content,
    )
}
