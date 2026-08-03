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
 * Язык десктопа (#285): та же палитра и та же типографика, что у телефона.
 *
 * До этой правки ПК говорил стандартным светлым Material — рядом с тёмным порталом телефона это
 * читалось как два разных продукта. Значения взяты из мокапа `Point Desktop.dc.html`
 * (Claude Design), он же — источник дизайн-системы вместе с `docs/design-system.png`.
 */
object PointColors {
    /** Полотно за окном — самый глубокий тон. */
    val canvas = Color(0xFF07080A)

    /** Фон окна. */
    val window = Color(0xFF0B0D10)

    /** Поверхность карточек: верх градиента. */
    val surface = Color(0xFF1A1D25)

    /** Поверхность карточек: низ градиента. */
    val surfaceDeep = Color(0xFF121419)

    /** Границы и разделители. */
    val border = Color(0xFF242833)

    val text = Color(0xFFFFFFFF)

    /** Приглушённый текст: подписи, метки, второстепенное. */
    val muted = Color(0xFFA1A6B3)

    /** Главный акцент — портал и активное. */
    val violet = Color(0xFF7B5CFF)

    /** Второй акцент — найденное, связь, живое. */
    val cyan = Color(0xFF00E0FF)
}

/**
 * Шрифты берутся из `:core:ui` и здесь **переиспользуются, а не копируются** (см.
 * `desktop/build.gradle.kts`): один файл начертания на проект — иначе телефон и ПК однажды
 * разойдутся, и никто не заметит, какой из них прав.
 */
private val Unbounded = FontFamily(
    Font(resource = "unbounded.ttf", weight = FontWeight.Normal),
    Font(resource = "unbounded.ttf", weight = FontWeight.SemiBold),
    Font(resource = "unbounded.ttf", weight = FontWeight.Bold),
)

private val Manrope = FontFamily(
    Font(resource = "manrope.ttf", weight = FontWeight.Normal),
    Font(resource = "manrope.ttf", weight = FontWeight.Medium),
    Font(resource = "manrope.ttf", weight = FontWeight.SemiBold),
    Font(resource = "manrope.ttf", weight = FontWeight.Bold),
)

/** Типографика мокапа: Unbounded говорит, Manrope рассказывает. */
object PointType {
    /** Крупное имя экрана — «Point ждёт объект». */
    val display = TextStyle(fontFamily = Unbounded, fontSize = 28.sp, color = PointColors.text)

    /** Заголовок карточки или станции. */
    val title = TextStyle(
        fontFamily = Unbounded, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        color = PointColors.text,
    )

    /** Обычный текст. */
    val body = TextStyle(fontFamily = Manrope, fontSize = 14.sp, color = PointColors.text)

    /** Второстепенное: подписи под объектом, пояснения. */
    val small = TextStyle(fontFamily = Manrope, fontSize = 13.sp, color = PointColors.muted)

    /** Метка секции: «ПРИЛЕТЕЛО», «ИЗВЛЕЧЬ» — разрядка и верхний регистр. */
    val label = TextStyle(
        fontFamily = Manrope, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp, color = PointColors.muted,
    )

    /** Горячая клавиша и прочее, что человек набирает руками. */
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = PointColors.muted)
}

/**
 * Тёмная схема ставится и в `MaterialTheme` тоже: штатные кнопки и диалоги десктопа берут цвета
 * оттуда, и без этого они светились бы белым посреди портала.
 */
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
