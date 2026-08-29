package com.point.executors

import com.point.core.flow.sampleSizeFor
import java.io.File
import java.util.Random
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF, который не стыдно отправить (#1047, решение владельца 23.08.2026 — «размер листа +
 * сжатие по содержимому»).
 *
 * Человек снял документ, чтобы его отправить. До этой правки страница PDF объявляла своим
 * размером число пикселей снимка — лист 71×53 см — и везла пиксели как есть, поэтому файл
 * выходил размером с фотографию и печатался не на ту площадь.
 *
 * Меряется здесь тот размер, который человеку и достаётся: `Bitmaps.decodeUpright` отдаёт
 * снимок 12 Мп раскодированным в 2000×1500, а не в матрицу камеры, и все числа ниже стоят на
 * нём — размер считается тут же, `sampleSizeFor`, а не вписан рукой.
 *
 * Вес — числом: картинка внутри PDF лежит потоком deflate, поэтому байты страницы считаются
 * тем же `Deflater`. Ужатие своё: `Bitmap.createScaledBitmap` на JVM не позвать. Образец
 * страницы поэтому проверяется отдельно — он не легче настоящей бинаризованной страницы со
 * снимков документа из корпуса (замер: 21–94 КБ на мегапиксель), иначе на нём можно доказать
 * что угодно.
 *
 * Само рисование — Android `PdfDocument`, его здесь нет. Чтобы «страницу собирает правило
 * листа» не разошлось с кодом молча, обе двери человека — «Сканировать в PDF» / «Объединить
 * в PDF» и «В PDF» — проверяются отдельно, по их собственному тексту.
 */
class PdfIsWorthSendingTest {

    @Test
    fun `страница ложится на лист, а не на матрицу камеры`() {
        // Снимок страницы после обрезки по краям листа.
        assertEquals(A4, sheetFor(2000, 2828))

        // Ни при каком снимке размером страницы не становится число пикселей.
        val huge = sheetFor(3200, 2400)
        assertTrue("лист из пикселей — $huge", huge.width < 1000 && huge.height < 1000)
    }

    @Test
    fun `лист один, и он поворачивается за страницей`() {
        // Кадр целиком, страницу на нём не обрезали, — пропорция матрицы 4 к 3. По пропорции
        // ближе всего US Letter, и человек с A4-принтером получал бы американский лист за то,
        // что не обрезал кадр.
        assertEquals(Sheet(A4.height, A4.width), sheetFor(2000, 1500))

        // Длинный чек ложится на тот же лист, только книжный.
        assertEquals(A4, sheetFor(1000, 2400))
    }

    @Test
    fun `страница встаёт на лист целиком, по центру и с полем`() {
        val sheet = sheetFor(1240, 1754)
        val box = sheet.boxFor(1240, 1754)

        // Поле: у принтера край листа не печатается, и без поля страницу обрезало бы.
        assertTrue("страница вышла за лист — $box", box.left >= 14f && box.top >= 14f)
        assertTrue(
            "страница вышла за лист — $box",
            box.right <= sheet.width - 14f && box.bottom <= sheet.height - 14f,
        )

        // По центру и без растяжения — то, что сняли, тем и осталось.
        assertEquals(sheet.width.toFloat(), box.left + box.right, 0.5f)
        assertEquals(sheet.height.toFloat(), box.top + box.bottom, 0.5f)
        assertEquals(1240f / 1754f, box.width / box.height, 0.01f)

        // И лист использован целиком — страница упирается в поле хотя бы одной стороной.
        assertTrue("страница мельче листа — $box", box.width >= sheet.width - 28f - 0.5f)
    }

    /**
     * Правило листа проверяется здесь, а собирает страницу — `PdfDocument`, которого на JVM
     * нет. Поэтому обе двери человека сверяются со своим же текстом: пока в них стоит размер
     * снимка, человек получает лист 71×53 см, сколько бы чистых функций тут ни сошлось.
     */
    @Test
    fun `обе двери человека собирают страницу одним правилом листа`() {
        val many = File("src/main/kotlin/com/point/executors/ImagesToPdf.kt").readText()
        val one = File("src/main/kotlin/com/point/executors/PdfAction.kt").readText()

        assertTrue(
            "набор страниц не идёт через общее правило",
            many.contains("document.addPage(bitmap, pages + 1)"),
        )
        assertTrue("одиночный снимок не идёт через общее правило", one.contains("addPage(bitmap, 1)"))
        assertTrue(
            "размер страницы взят не с листа",
            many.contains("PageInfo.Builder(sheet.width, sheet.height"),
        )
        assertTrue("страница встаёт не в своё место на листе", many.contains("sheet.boxFor(fitted.width"))

        listOf("ImagesToPdf" to many, "PdfAction" to one).forEach { (name, source) ->
            assertFalse(
                "$name снова меряет страницу пикселями снимка",
                source.contains("PageInfo.Builder(bitmap.width"),
            )
            assertFalse(
                "$name снова кладёт снимок в угол страницы",
                source.contains("drawBitmap(bitmap, 0f, 0f"),
            )
        }
    }

    @Test
    fun `чёрно-белый текст и цветная печать жмутся по-разному`() {
        val ink = inkPage(1240, 1754)
        val print = printPage(1240, 1754)

        assertTrue(inkOnPaper(ink))
        assertFalse(inkOnPaper(print))

        // Не вкус, а свойство содержимого — та же страница, снятая цветной, весит в разы
        // больше: deflate снимает почти всё с двух цветов и почти ничего с шума матрицы.
        assertTrue(
            "цветная страница не тяжелее чёрно-белой — ${deflated(print)} против ${deflated(ink)}",
            deflated(print) > deflated(ink) * 4,
        )
    }

    @Test
    fun `цветной снимок документа едет в PDF в разы легче`() {
        val (width, height) = decoded(4000, 3000)
        val was = printPage(width, height)
        val sheet = sheetFor(width, height)

        // Снимок не помещается в чёткость листа — иначе мерить было бы нечего.
        assertTrue("снимок и так мельче листа", maxOf(width, height) > sheet.pageMaxPx())

        val now = fewerTones(shrunk(was, width, height, sheet.pageMaxPx()))

        // На снимках документа из корпуса это 2,5–3,8 раза; порог назван по самому слабому замеру.
        assertTrue(
            "страница не полегчала — ${deflated(now)} против ${deflated(was)}",
            deflated(now) * 5 < deflated(was) * 2,
        )
    }

    @Test
    fun `чёрно-белую страницу ужимать нечем`() {
        val (width, height) = decoded(4000, 3000)
        val ink = inkPage(width, height)
        val sheet = sheetFor(width, height)

        // Образец честный — не легче настоящей бинаризованной страницы со снимков документа
        // из корпуса (там 21–94 КБ на мегапиксель). На выдуманной странице из ровных полос
        // deflate снимает почти всё, и любой вывод о весе получается сам собой.
        val perMegapixel = deflated(ink).toLong() * 1_000_000 / (width.toLong() * height)
        assertTrue("образец легче настоящей страницы — $perMegapixel байт на мегапиксель", perMegapixel >= 20 * 1024)

        // Ужатие до предела листа снимает меньше пятой части веса — на снимках документа из
        // корпуса оно меняет его на −2 %…+1 %. Той же операцией цветная страница выше теряет
        // больше половины.
        val smaller = shrunk(ink, width, height, sheet.pageMaxPx())
        assertTrue(
            "ужатие вдруг снимает вес — ${deflated(smaller)} против ${deflated(ink)}",
            deflated(smaller) * 5 > deflated(ink) * 4,
        )

        // …зато добавляет буквам серые края: двух цветов на странице становится больше.
        assertTrue("ужатие не добавило полутонов", tones(smaller) > tones(ink))

        // И округлять оттенки ей нечего — цветов на ней и так два.
        assertEquals(deflated(ink), deflated(fewerTones(ink)))
    }

    /** Столько пикселей отдаёт `Bitmaps.decodeUpright` со снимка этого размера. */
    private fun decoded(width: Int, height: Int): Pair<Int, Int> {
        val sample = sampleSizeFor(width, height, Bitmaps.PROCESS_MAX_PX)
        return width / sample to height / sample
    }

    /**
     * Страница после бинаризации скана — только чёрное и белое.
     *
     * Буквы неровные, а по бумаге идёт крапина адаптивного порога: настоящая бинаризованная
     * страница выглядит так, и жмётся она так же.
     */
    private fun inkPage(width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height) { WHITE }
        val random = Random(17)
        val glyphs = Array(48) { BooleanArray(GLYPH_CELLS * GLYPH_CELLS) { random.nextInt(3) > 0 } }

        val lineHeight = height / 46
        val cell = maxOf(1, lineHeight / (GLYPH_CELLS + 3))
        val glyph = cell * GLYPH_CELLS
        val margin = width / 10
        var top = lineHeight
        while (top + glyph < height - lineHeight) {
            var left = margin
            while (left + glyph < width - margin) {
                val word = 3 + random.nextInt(7)
                for (letter in 0 until word) {
                    if (left + glyph >= width - margin) break
                    val mask = glyphs[random.nextInt(glyphs.size)]
                    for (cy in 0 until GLYPH_CELLS) {
                        for (cx in 0 until GLYPH_CELLS) {
                            if (!mask[cy * GLYPH_CELLS + cx]) continue
                            for (y in 0 until cell) {
                                val row = (top + cy * cell + y) * width
                                for (x in 0 until cell) pixels[row + left + cx * cell + x] = BLACK
                            }
                        }
                    }
                    left += glyph + cell
                }
                left += glyph
            }
            top += lineHeight
        }

        // Крапина порога: точка тут, точка там — её-то deflate и не снимает.
        repeat(width * height / 400) {
            pixels[random.nextInt(pixels.size)] = BLACK
        }
        return pixels
    }

    /** Та же страница снимком — неровный свет, цвет бумаги и шум матрицы. */
    private fun printPage(width: Int, height: Int): IntArray {
        val ink = inkPage(width, height)
        val random = Random(17)
        return IntArray(width * height) { at ->
            val x = at % width
            val y = at / width
            val light = 210 + 40 * x / width - 30 * y / height
            val base = if (ink[at] == BLACK) 40 else light
            val red = (base + random.nextInt(13) - 6).coerceIn(0, 255)
            val green = (base - 4 + random.nextInt(13) - 6).coerceIn(0, 255)
            val blue = (base - 9 + random.nextInt(13) - 6).coerceIn(0, 255)
            ALPHA or (red shl 16) or (green shl 8) or blue
        }
    }

    /** Ужатие до предела длинной стороны — на месте `Bitmap.createScaledBitmap` со сглаживанием. */
    private fun shrunk(pixels: IntArray, width: Int, height: Int, maxPx: Int): IntArray {
        val longEdge = maxOf(width, height)
        if (longEdge <= maxPx) return pixels
        val out = (width.toLong() * maxPx / longEdge).toInt().coerceAtLeast(1)
        val down = (height.toLong() * maxPx / longEdge).toInt().coerceAtLeast(1)
        return IntArray(out * down) { at ->
            val x = at % out
            val y = at / out
            val x0 = x.toLong() * width / out
            val x1 = ((x + 1).toLong() * width / out).coerceAtLeast(x0 + 1)
            val y0 = y.toLong() * height / down
            val y1 = ((y + 1).toLong() * height / down).coerceAtLeast(y0 + 1)
            var red = 0
            var green = 0
            var blue = 0
            var taken = 0
            for (sy in y0 until y1) {
                for (sx in x0 until x1) {
                    val pixel = pixels[(sy * width + sx).toInt()]
                    red += (pixel shr 16) and 0xFF
                    green += (pixel shr 8) and 0xFF
                    blue += pixel and 0xFF
                    taken++
                }
            }
            ALPHA or ((red / taken) shl 16) or ((green / taken) shl 8) or (blue / taken)
        }
    }

    /** Сколько разных цветов на странице — больше восьми считать незачем. */
    private fun tones(pixels: IntArray): Int {
        val seen = IntArray(9)
        var found = 0
        for (pixel in pixels) {
            if ((0 until found).any { seen[it] == pixel }) continue
            if (found == seen.size) return seen.size
            seen[found++] = pixel
        }
        return found
    }

    /** Столько байт займёт страница в PDF — картинка лежит там потоком deflate. */
    private fun deflated(pixels: IntArray): Int {
        val raw = ByteArray(pixels.size * 3)
        for (at in pixels.indices) {
            raw[at * 3] = ((pixels[at] shr 16) and 0xFF).toByte()
            raw[at * 3 + 1] = ((pixels[at] shr 8) and 0xFF).toByte()
            raw[at * 3 + 2] = (pixels[at] and 0xFF).toByte()
        }
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(1 shl 16)
        var total = 0
        while (!deflater.finished()) total += deflater.deflate(buffer)
        deflater.end()
        return total
    }

    private companion object {
        const val ALPHA = 0xFF shl 24
        const val WHITE = (0xFF shl 24) or 0xFFFFFF
        const val BLACK = 0xFF shl 24

        /** Сколько клеток в букве образца по каждой стороне. */
        const val GLYPH_CELLS = 5
    }
}
