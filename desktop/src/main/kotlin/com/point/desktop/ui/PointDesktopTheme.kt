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
import com.point.core.ui.PointPalette

/**
 * Цвета окна компьютера — общая палитра Point (`PointPalette`, #851) под именами окна.
 *
 * С макета 19.10.2026 ПК на несколько часов получил свою палитру — стекло поверх тёплой
 * сливовой земли, — а телефон остался на прежней. С 20.10.2026 тем же стеклом рисуется и
 * телефон (решение владельца), и цвета снова одни: здесь только имена, литералов нет.
 * Значки и цвета действий тоже общие (`bubbleIcon`, `bubbleColor`): одно действие узнаётся на
 * обоих устройствах.
 */
object PointColors {

    val canvas = PointPalette.canvas

    val window = PointPalette.window

    val surface = PointPalette.surface

    val surfaceDeep = PointPalette.surfaceDeep

    val border = PointPalette.border

    val hairline = PointPalette.hairline

    val text = PointPalette.text

    val soft = PointPalette.soft

    val muted = PointPalette.muted

    val faint = PointPalette.faint

    val violet = PointPalette.violet

    val link = PointPalette.link

    val blue = PointPalette.blue

    val cyan = PointPalette.cyan

    val pink = PointPalette.pink

    val orange = PointPalette.orange

    val green = PointPalette.green

    val yellow = PointPalette.yellow

    /** Кнопка «закрыть» в шапке окна — единственное место тёплого тона. */
    val close = PointPalette.warm

    val ink = PointPalette.ink

    val lightTop = PointPalette.lightTop
    val lightBottom = PointPalette.lightBottom
}

/**
 * Шрифт окна — Ubuntu (макет владельца, 19.10.2026), моно-строки — Ubuntu Mono.
 *
 * Файлы общие с телефоном: лежат в шрифтах `core/ui` (`res/font`), лицензия — рядом в его
 * `assets/fonts/UFL.txt` (Ubuntu Font Licence 1.0); окно подключает оба каталога ресурсами,
 * как звуки из `data` (#650).
 * Каждый вес — свой файл, поэтому вес текста задаёт сам файл, а не ось переменного шрифта:
 * беды #626 (весь текст ExtraLight по умолчанию оси) здесь не бывает по устройству.
 */
internal object DesktopFonts {

    const val REGULAR = "ubuntu_regular.ttf"
    const val MEDIUM = "ubuntu_medium.ttf"
    const val BOLD = "ubuntu_bold.ttf"
    const val MONO = "ubuntu_mono_regular.ttf"

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
