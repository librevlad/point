package com.point.executors

import com.point.core.flow.Box
import kotlin.math.abs

/**
 * Лист под страницу PDF и сжатие страницы по её содержимому (#1047, решение владельца
 * 23.08.2026 — «размер листа + сжатие по содержимому»).
 *
 * PDF меряет страницу в точках 1/72 дюйма, а Point отдавал размером страницы число пикселей
 * снимка: страница со снимка 3200×2400 объявляла себя листом 111×83 см. Такой PDF печатается
 * не на ту площадь и открывается не тем, чем он есть.
 *
 * Второе — вес. Картинка внутри PDF лежит потоком deflate: он снимает почти всё со страницы,
 * на которой пара цветов, и почти ничего — со снимка с шумом матрицы. Отсюда и правило:
 * чёрно-белый текст жмётся иначе, чем цветная печать. Замеры на JVM — deflate сырых пикселей
 * одной страницы A4:
 *
 *  - чёрно-белый текст, 3200 px — 214 КБ. Он уже сжат, и трогать его нечем: ужать его со
 *    сглаживанием до 2339 px дало 524 КБ, вдвое тяжелее исходного, потому что серые края
 *    букв съедают сжатие;
 *  - цветная страница со снимка, 3200 px — 13,4 МБ. Предел листа в 150 dpi даёт 4,0 МБ,
 *    округление оттенков — 1,3 МБ; вместе страница легче в десять раз.
 */

/** Лист в точках PDF — 1/72 дюйма; в них же `PdfDocument` меряет страницу. */
internal data class Sheet(val width: Int, val height: Int)

/**
 * Лист под страницу: тот из листов, чья пропорция ближе к странице, в её же ориентации.
 *
 * Пропорция, а не пиксели: снимок ничего не знает о бумаге, зато сама страница знает, во
 * сколько раз она длиннее, чем шире. При равенстве побеждает A4 — он первым в списке.
 */
internal fun sheetFor(widthPx: Int, heightPx: Int): Sheet {
    val width = widthPx.coerceAtLeast(1)
    val height = heightPx.coerceAtLeast(1)
    val ratio = maxOf(width, height).toFloat() / minOf(width, height)
    val sheet = SHEETS.minBy { abs(it.height.toFloat() / it.width - ratio) }
    return if (height >= width) sheet else Sheet(sheet.height, sheet.width)
}

/**
 * Сколько пикселей длинной стороны несёт страница на этом листе.
 *
 * Чёрно-белой странице оставлена вся чёткость, какую позволяет лист: её вес — не в пикселях,
 * а цена мелкого шрифта на распечатке — в них. Цветной странице чёткость стоит веса, и лист
 * получает столько, сколько нужно, чтобы прочитать её на бумаге.
 */
internal fun Sheet.pageMaxPx(inkOnPaper: Boolean): Int =
    maxOf(width, height) * (if (inkOnPaper) INK_DPI else PRINT_DPI) / POINTS_PER_INCH

/**
 * Место страницы на листе: по центру, с полем и без растяжения.
 *
 * Поле — не украшение: у принтера край листа не печатается, и без поля страница выходит
 * обрезанной по краям вместо «на лист целиком».
 */
internal fun Sheet.boxFor(widthPx: Int, heightPx: Int): Box {
    val pageWidth = widthPx.coerceAtLeast(1)
    val pageHeight = heightPx.coerceAtLeast(1)
    val fit = minOf(
        (width - 2 * SHEET_MARGIN).toFloat() / pageWidth,
        (height - 2 * SHEET_MARGIN).toFloat() / pageHeight,
    )
    val left = (width - pageWidth * fit) / 2f
    val top = (height - pageHeight * fit) / 2f
    return Box(left, top, left + pageWidth * fit, top + pageHeight * fit)
}

/**
 * Страница — краска на бумаге, а не цветная печать: разных цветов на ней считаные единицы.
 *
 * Такую страницу оставляют как есть: она уже сжата до предела, а любое сглаживание или
 * округление только добавит ей полутонов и веса.
 */
internal fun inkOnPaper(pixels: IntArray): Boolean {
    val seen = IntArray(INK_COLOURS)
    var found = 0
    for (pixel in pixels) {
        var known = false
        for (i in 0 until found) {
            if (seen[i] == pixel) {
                known = true
                break
            }
        }
        if (known) continue
        if (found == INK_COLOURS) return false
        seen[found++] = pixel
    }
    return true
}

/**
 * Те же пиксели, но оттенков в каждом канале — ступени, а не все 256.
 *
 * Шум матрицы даёт соседним пикселям разные значения там, где глаз видит один цвет; deflate
 * такой шум не жмёт вовсе. Ступень в 1/32 канала — ошибка не больше 4 из 255, меньше самого
 * шума, а вес страницы падает втрое.
 */
internal fun fewerTones(pixels: IntArray): IntArray = IntArray(pixels.size) { at ->
    val pixel = pixels[at]
    (pixel and ALPHA) or
        (step((pixel shr 16) and 0xFF) shl 16) or
        (step((pixel shr 8) and 0xFF) shl 8) or
        step(pixel and 0xFF)
}

/** Канал, округлённый до ближайшей ступени; чистые 0 и 255 остаются собой. */
private fun step(channel: Int): Int {
    val level = (channel * (TONES - 1) + 255 / 2) / 255
    return (level * 255 + (TONES - 1) / 2) / (TONES - 1)
}

/** Лист по умолчанию: на нём же Point печатает текст, разложенный по страницам. */
internal val A4 = Sheet(595, 842)

/** Листы книжной ориентации: A4, Letter, Legal — три разные пропорции, A4 первым. */
private val SHEETS = listOf(A4, Sheet(612, 792), Sheet(612, 1008))

/** Точек PDF в дюйме. */
private const val POINTS_PER_INCH = 72

/** Поле листа, ~5 мм: столько у принтера не печатается. */
private const val SHEET_MARGIN = 14

/** Чёткость чёрно-белой страницы: мелкий шрифт остаётся читаемым на бумаге. */
private const val INK_DPI = 300

/** Чёткость цветной страницы: столько нужно, чтобы прочитать её на листе. */
private const val PRINT_DPI = 150

/** Сколько разных цветов ещё считается краской на бумаге. */
private const val INK_COLOURS = 8

/** Сколько ступеней оставляем каналу цветной страницы. */
private const val TONES = 32

private const val ALPHA = 0xFF shl 24
