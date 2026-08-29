package com.point.executors

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
 * размером число пикселей снимка — лист метр на восемьдесят сантиметров — и везла пиксели
 * как есть, поэтому файл выходил размером с фотографию и печатался не на ту площадь.
 *
 * Что здесь проверяется: лист под страницу, место страницы на листе и вес страницы. Вес —
 * числом: картинка внутри PDF лежит потоком deflate, поэтому байты страницы здесь считаются
 * тем же `Deflater`. Само рисование — Android `PdfDocument`, на JVM его не позвать, как и
 * `Bitmap.createScaledBitmap`: ужатие в этой проверке своё, и оно стоит на месте ужатия из
 * `imagesToPdf`, а не проверяет его. Уход самого `imagesToPdf` от `sheetFor` и `pageMaxPx`
 * эта проверка не увидит — она держит правило, по которому он собирает страницу.
 */
class PdfIsWorthSendingTest {

    @Test
    fun `страница ложится на лист, а не на матрицу камеры`() {
        // Снимок страницы A4 после обрезки по краям листа: пропорция — листа, а не матрицы.
        assertEquals(Sheet(595, 842), sheetFor(2000, 2828))
        assertEquals(Sheet(842, 595), sheetFor(2828, 2000))

        // Ни при каком снимке размером страницы не становится число пикселей.
        val huge = sheetFor(3200, 2400)
        assertTrue("лист из пикселей: $huge", huge.width < 1000 && huge.height < 1000)
    }

    @Test
    fun `лист берётся под пропорцию страницы`() {
        // Кадр целиком, страницу на нём не обрезали, — пропорция матрицы 4 к 3.
        assertEquals(Sheet(612, 792), sheetFor(3000, 4000))

        // Длинный чек: A4 оставил бы половину листа пустой.
        assertEquals(Sheet(612, 1008), sheetFor(1000, 2400))
    }

    @Test
    fun `страница встаёт на лист целиком, по центру и с полем`() {
        val sheet = sheetFor(1240, 1754)
        val box = sheet.boxFor(1240, 1754)

        // Поле: у принтера край листа не печатается, и без поля страницу обрезало бы.
        assertTrue("страница вышла за лист: $box", box.left >= 14f && box.top >= 14f)
        assertTrue(
            "страница вышла за лист: $box",
            box.right <= sheet.width - 14f && box.bottom <= sheet.height - 14f,
        )

        // По центру и без растяжения: то, что сняли, тем и осталось.
        assertEquals(sheet.width.toFloat(), box.left + box.right, 0.5f)
        assertEquals(sheet.height.toFloat(), box.top + box.bottom, 0.5f)
        assertEquals(1240f / 1754f, box.width / box.height, 0.01f)

        // И лист использован целиком: страница упирается в поле хотя бы одной стороной.
        assertTrue("страница мельче листа: $box", box.width >= sheet.width - 28f - 0.5f)
    }

    @Test
    fun `чёрно-белый текст и цветная печать жмутся по-разному`() {
        val ink = inkPage(1240, 1754)
        val print = printPage(1240, 1754)

        assertTrue(inkOnPaper(ink))
        assertFalse(inkOnPaper(print))

        // Не вкус, а свойство содержимого: та же страница, снятая цветной, весит в разы
        // больше — deflate снимает почти всё с двух цветов и почти ничего с шума матрицы.
        assertTrue(
            "цветная страница не тяжелее чёрно-белой: ${deflated(print)} против ${deflated(ink)}",
            deflated(print) > deflated(ink) * 4,
        )

        // Поэтому чёрно-белой странице оставлена вся чёткость, а цветной назначен предел.
        val sheet = sheetFor(1240, 1754)
        assertTrue(sheet.pageMaxPx(inkOnPaper = true) > sheet.pageMaxPx(inkOnPaper = false))
        assertTrue("чёткость текста ниже снимка", sheet.pageMaxPx(inkOnPaper = true) >= 3200)
    }

    @Test
    fun `цветная страница со снимка едет в PDF в разы легче`() {
        // Снимок с телефона после раскодирования: 3200 точек по длинной стороне.
        val sheet = sheetFor(2263, 3200)
        val was = printPage(2263, 3200)

        val fitted = shrunk(was, 2263, 3200, sheet.pageMaxPx(inkOnPaper = false))
        val now = fewerTones(fitted)

        assertTrue(
            "страница не полегчала: ${deflated(now)} против ${deflated(was)}",
            deflated(now) * 4 < deflated(was),
        )
    }

    @Test
    fun `чёрно-белую страницу правило не портит`() {
        // Её вес — не в пикселях, а цена мелкого шрифта на распечатке — в них: на листе
        // ей оставлено больше точек, чем даёт раскодированный снимок.
        val sheet = sheetFor(2263, 3200)

        assertTrue(sheet.pageMaxPx(inkOnPaper = true) >= 3200)

        // А округление оттенков ей нечего дать: цветов на ней и так два.
        val ink = inkPage(1240, 1754)
        assertEquals(deflated(ink), deflated(fewerTones(ink)))
    }

    /** Страница после бинаризации скана: только чёрное и белое. */
    private fun inkPage(width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height) { WHITE }
        for (line in 0 until height / 24) {
            val top = line * 24 + 6
            for (y in top until top + 8) {
                for (x in width / 12 until width - width / 12) {
                    if ((x / 7 + line) % 5 != 0) pixels[y * width + x] = BLACK
                }
            }
        }
        return pixels
    }

    /** Та же страница снимком: неровный свет, цвет бумаги и шум матрицы. */
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

    /** Ужатие до предела длинной стороны — на месте `Bitmap.createScaledBitmap`. */
    private fun shrunk(pixels: IntArray, width: Int, height: Int, maxPx: Int): IntArray {
        val longEdge = maxOf(width, height)
        if (longEdge <= maxPx) return pixels
        val out = width.toLong() * maxPx / longEdge
        val down = height.toLong() * maxPx / longEdge
        return IntArray((out * down).toInt()) { at ->
            val x = (at % out.toInt()).toLong() * width / out
            val y = (at / out.toInt()).toLong() * height / down
            pixels[(y * width + x).toInt()]
        }
    }

    /** Столько байт займёт страница в PDF: картинка лежит там потоком deflate. */
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
    }
}
