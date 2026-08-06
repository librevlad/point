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
/**
 * Одно начертание — одна насыщенность (#590).
 *
 * Прежде один и тот же файл объявлялся под тремя-четырьмя насыщенностями сразу. Настоящих
 * полужирного и жирного у нас нет — в папке ровно два файла, — и Skia утолщала обычное сама:
 * буквы разной толщины, неровные штрихи, поплывшая ширина. Владелец назвал это «кривые шрифты».
 *
 * На телефоне того же не видно: Android подставляет системное начертание, а не подделывает.
 *
 * Объявлять то, чего нет, — обещание, которое рисуется подделкой. Иерархию держат размер, цвет и
 * разрядка; они честные.
 */
private val Unbounded = FontFamily(Font(resource = "unbounded.ttf", weight = FontWeight.Normal))

private val Manrope = FontFamily(Font(resource = "manrope.ttf", weight = FontWeight.Normal))

/**
 * Как рисовать буквы (#590). Выбрано владельцем глазами по образцу — вариант 4.
 *
 * Текст в окне рисует Skia, а не Windows, и системного ClearType там нет по умолчанию: отсюда
 * тонкие размытые буквы, непохожие на любое соседнее окно. Владелец: «у java приложений в принципе
 * проблема со сглаживанием шрифтов как таковая, и это бесит».
 *
 * До этой правки стояло умолчание библиотеки — то есть никто ничего не выбирал.
 *
 * Обычное сглаживание, а не субпиксельное: субпиксельное подкрашивает края в цвет и красиво только
 * на одном виде экрана, а этот же Point открывают и на ноутбуке с масштабом 150 %. Полный хинтинг
 * сажает буквы на пиксельную сетку — именно он и делает мелкий текст читаемым.
 *
 * Собрано образцом (`./gradlew :desktop:fontSample`): пять вариантов рядом, снимок владельцу,
 * выбор одним словом. Описанием такое не выбирают.
 */
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

/** Типографика мокапа: Unbounded говорит, Manrope рассказывает. */
object PointType {
    /** Крупное имя экрана — «Point ждёт объект». */
    val display = TextStyle(
        fontFamily = Unbounded, fontSize = 28.sp, color = PointColors.text,
        platformStyle = PointRasterization,
    )

    /** Заголовок карточки или станции. */
    val title = TextStyle(
        fontFamily = Unbounded, fontSize = 18.sp, color = PointColors.text,
        platformStyle = PointRasterization,
    )

    /** Обычный текст. */
    val body = TextStyle(
        fontFamily = Manrope, fontSize = 14.sp, color = PointColors.text,
        platformStyle = PointRasterization,
    )

    /** Второстепенное: подписи под объектом, пояснения. */
    val small = TextStyle(
        fontFamily = Manrope, fontSize = 13.sp, color = PointColors.muted,
        platformStyle = PointRasterization,
    )

    /** Метка секции: «ПРИЛЕТЕЛО», «ИЗВЛЕЧЬ» — разрядка и верхний регистр. */
    val label = TextStyle(
        fontFamily = Manrope, fontSize = 11.sp,
        letterSpacing = 1.6.sp, color = PointColors.muted,
        platformStyle = PointRasterization,
    )

    /** Горячая клавиша и прочее, что человек набирает руками. */
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = PointColors.muted,
        platformStyle = PointRasterization,
    )
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
