package com.point.desktop

import com.point.vision.PageOrientation
import com.point.vision.PageVision
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Point
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Лист после скана стоит так, чтобы его можно было читать (#1044, замер 23.10.2026): на
 * корпусе владельца три паперових кадра из шести выходили боком или вверх ногами.
 */
class PageOrientationTest {

    private val words = (
        "This is to certify that the goods listed below were received in full and in good " +
            "condition on the date shown Invoice number 4521 total amount due 1250.00 payable " +
            "within thirty days of receipt Please quote the reference on all correspondence"
        ).split(" ")

    /** Страница письма: строки слева направо, рваный правый край, рамки таблицы через одну. */
    private fun letter(seed: Long, table: Boolean): BufferedImage {
        val width = 1240
        val height = 1754
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.color = Color.BLACK
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 26)
        val random = java.util.Random(seed)
        val metrics = g.fontMetrics
        var y = 120
        var i = 0
        while (y < height - 150) {
            if (table && (y / 200) % 2 == 0) g.drawRect(100, y - 26, width - 200, 104)
            var line = ""
            val limit = width - 220 - random.nextInt(300)
            while (metrics.stringWidth("$line ${words[i % words.size]}") < limit) {
                line += (if (line.isEmpty()) "" else " ") + words[i % words.size]
                i++
            }
            g.drawString(line, 110, y)
            y += 42
        }
        g.dispose()
        return image
    }

    @Test
    fun `лист боком и вверх ногами встаёт прямо`() {
        assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)

        for (table in listOf(false, true)) for (lying in 0..3) {
            val page = rgbaMatOf(letter(seed = lying + 1L, table = table))
            val scratch = mutableListOf<Mat>(page)
            try {
                val shot = PageOrientation.turned(page, lying, scratch)
                assertEquals("таблица=$table, лежал на $lying четверти", (4 - lying) % 4, PageOrientation.quarterTurns(shot, scratch))
            } finally {
                scratch.forEach { it.release() }
            }
        }
    }

    @Test
    fun `пустой лист не поворачивается`() {
        assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)

        val blank = BufferedImage(1000, 1400, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply { color = Color.WHITE; fillRect(0, 0, 1000, 1400); dispose() }
        }
        val page = rgbaMatOf(blank)
        val scratch = mutableListOf<Mat>(page)
        try {
            assertEquals(0, PageOrientation.quarterTurns(page, scratch))
        } finally {
            scratch.forEach { it.release() }
        }
    }

    @Test
    fun `углы поворачиваются вместе с листом`() {
        val tl = Point(0.0, 0.0)
        val tr = Point(10.0, 0.0)
        val br = Point(10.0, 20.0)
        val bl = Point(0.0, 20.0)
        val corners = arrayOf(tl, tr, br, bl)

        // Четверть по часовой: сверху слева на копии оказывается бывший нижний левый угол.
        assertArrayEquals(arrayOf(bl, tl, tr, br), PageVision.turnedCorners(corners, 1))
        assertArrayEquals(arrayOf(br, bl, tl, tr), PageVision.turnedCorners(corners, 2))
        assertArrayEquals(corners, PageVision.turnedCorners(corners, 4))
    }
}
