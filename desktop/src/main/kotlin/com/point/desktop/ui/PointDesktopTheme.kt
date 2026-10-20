package com.point.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/**
 * Цвета окна компьютера — стекло поверх тёплой сливовой земли (макет владельца «Point · концепт
 * в стиле Gestalt», 19.10.2026).
 *
 * До макета ПК брал телефонную палитру целиком (#851): почти чёрный холст, яркий фиолетовый и
 * плоские тёмные карточки — на большом экране это выглядело телефоном в узком окне. Макет даёт
 * компьютеру свой язык: мягкий контраст, три вида стекла (окно, панель, строка) и приглушённый
 * фиолетовый Point вместо терракоты Gestalt. Значки и цвета действий остаются общими с телефоном
 * (`bubbleIcon`, `bubbleColor`): одно действие узнаётся на обоих устройствах.
 *
 * Полупрозрачные тона ложатся на обои окна (`GlassBackdrop`); там, где нужна плотная заливка
 * (меню, поле ввода, диалог), есть свои непрозрачные значения.
 */
object PointColors {

    /** Земля под стеклом: то, что видно сквозь окно, без пятен обоев. */
    val canvas = Color(0xFF2B2531)

    /** Стекло окна, сведённое на землю в один цвет, — для мест, где сквозь него ничего не видно. */
    val window = Color(0xFF26212C)

    /** Панель внутри окна: группа действий, блок содержимого. */
    val surface = Color.White.copy(alpha = 0.055f)

    /** Плотная тёмная поверхность: меню, поле ввода, диалог. */
    val surfaceDeep = Color(0xFF282230)

    /** Кромка стекла. */
    val border = Color.White.copy(alpha = 0.12f)

    /** Тонкая линия между частями окна. */
    val hairline = Color.White.copy(alpha = 0.08f)

    val text = Color(0xFFEDE7EA)

    /** Вторая строка о самом объекте: вид, откуда пришёл, когда. */
    val soft = text.copy(alpha = 0.74f)

    /** Подпись, обещание действия, счётчик. */
    val muted = text.copy(alpha = 0.58f)

    /** Самое тихое: время в строке, моно-строка файла. */
    val faint = text.copy(alpha = 0.48f)

    /** Цвет самого Point — приглушённый фиолетовый. */
    val violet = Color(0xFFB6A3E3)

    /** Фиолетовый для слова-ссылки на тёмном стекле: светлее, чтобы читался. */
    val link = Color(0xFFCBBEEE)

    val blue = Color(0xFF8FB4E8)

    val cyan = Color(0xFF8BC9D6)

    val pink = Color(0xFFE39DB9)

    val orange = Color(0xFFE3A27C)

    /** Телефон рядом и на связи. */
    val green = Color(0xFF86CBA6)

    val yellow = Color(0xFFE0CC8B)

    /** Кнопка «закрыть» в шапке окна — единственное место тёплого тона. */
    val close = Color(0xFFD98B63)

    /** Тёмный текст на светлой главной строке. */
    val ink = Color(0xFF2B2531)

    /** Светлая главная строка: верх и низ её мягкого градиента. */
    val lightTop = Color(0xF7F1ECF4)
    val lightBottom = Color(0xEBE5DEED)
}

/**
 * Шрифт окна — Ubuntu (макет владельца, 19.10.2026), моно-строки — Ubuntu Mono.
 *
 * Файлы лежат в ресурсах ПК вместе с лицензией (`fonts/UFL.txt`, Ubuntu Font Licence 1.0).
 * Каждый вес — свой файл, поэтому вес текста задаёт сам файл, а не ось переменного шрифта:
 * беды #626 (весь текст ExtraLight по умолчанию оси) здесь не бывает по устройству.
 */
internal object DesktopFonts {

    const val REGULAR = "fonts/ubuntu-regular.ttf"
    const val MEDIUM = "fonts/ubuntu-medium.ttf"
    const val BOLD = "fonts/ubuntu-bold.ttf"
    const val MONO = "fonts/ubuntu-mono-regular.ttf"

    /** Начертание файла так, как его видит Skia: по нему тест сверяет вес с выбранным. */
    fun face(resource: String): org.jetbrains.skia.Typeface {
        val bytes = PointColors::class.java.getResourceAsStream("/$resource")!!.use { it.readBytes() }
        return org.jetbrains.skia.FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes))!!
    }
}

private val Ubuntu = FontFamily(
    Font(resource = DesktopFonts.REGULAR, weight = FontWeight.Normal),
    Font(resource = DesktopFonts.MEDIUM, weight = FontWeight.Medium),
    Font(resource = DesktopFonts.BOLD, weight = FontWeight.Bold),
)

private val UbuntuMono = FontFamily(Font(resource = DesktopFonts.MONO, weight = FontWeight.Normal))

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

private fun ubuntu(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = PointColors.text) = TextStyle(
    fontFamily = Ubuntu,
    fontWeight = weight,
    fontSize = size.sp,
    color = color,
    platformStyle = PointRasterization,
)

/**
 * Ступени текста окна. Размеры — макета: окно плотнее телефона, мышь точнее пальца, а
 * вертикаль на компьютере дороже (#879).
 */
object PointType {

    /** Имя объекта у кольца, заголовок входа. */
    val display = ubuntu(22f, FontWeight.Bold)

    /** Заголовок внутри панели: раздел настроек, отказ входа. */
    val title = ubuntu(16f, FontWeight.Bold)

    /** Имя окна в шапке. */
    val header = ubuntu(13.5f, FontWeight.Bold)

    /** Обычный текст окна. */
    val body = ubuntu(13.5f)

    /** Имя строки: объекта в списке, действия в панели. */
    val rowTitle = ubuntu(13.5f, FontWeight.Medium)

    /** Подпись: вторая строка, пояснение, сообщение. */
    val small = ubuntu(12f, color = PointColors.muted)

    /** Вторая строка в строке списка и действия. */
    val note = ubuntu(11.5f, color = PointColors.muted)

    /** Заголовок группы — прописными, с разрядкой, рисует вызывающий. */
    val label = ubuntu(11f, FontWeight.Medium, PointColors.text.copy(alpha = 0.52f)).copy(letterSpacing = 0.66.sp)

    /** Содержимое объекта: текст читают, а не пробегают глазами. */
    val reading = ubuntu(14.5f, color = PointColors.text.copy(alpha = 0.9f)).copy(lineHeight = 23.sp)

    val mono = TextStyle(
        fontFamily = UbuntuMono,
        fontSize = 11.5.sp,
        color = PointColors.faint,
        platformStyle = PointRasterization,
    )
}


@Composable
fun PointDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        // Схема стандартных компонентов одна на телефон и ПК (#853): стекло окна рисуется своими
        // токенами выше, а роли Material не назначаются заново.
        colorScheme = com.point.core.ui.PointDarkColors,
        typography = Typography(
            headlineSmall = PointType.display,
            titleMedium = PointType.title,
            titleLarge = PointType.title,
            bodyLarge = PointType.body,
            bodyMedium = PointType.body,
            bodySmall = PointType.small,
            labelLarge = PointType.rowTitle,
            labelMedium = PointType.small,
            labelSmall = PointType.label,
        ),
        content = content,
    )
}
