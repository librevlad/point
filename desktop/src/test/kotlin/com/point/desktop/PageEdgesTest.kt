package com.point.desktop

import com.point.vision.PageEdges
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Углы, легшие внутрь листа, не выдаются за страницу (#1044, замер 23.10.2026): на двух
 * кадрах корпуса копия выходила без концов строк, и молча.
 */
class PageEdgesTest {

    private val left = 200.0
    private val top = 150.0
    private val right = 1000.0
    private val bottom = 1250.0

    /** Лист с текстом на столе; стол ровный или пёстрый, как дерево. */
    private fun photo(sideways: Boolean = false, grainy: Boolean = false): Mat {
        val image = BufferedImage(1200, 1400, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(90, 70, 50)
        g.fillRect(0, 0, 1200, 1400)
        if (grainy) {
            val random = java.util.Random(7)
            g.color = Color(40, 30, 20)
            repeat(400) {
                val y = random.nextInt(1400)
                g.drawLine(0, y, 1200, y + random.nextInt(40) - 20)
            }
        }
        g.color = Color(245, 245, 240)
        g.fillRect(left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt())
        g.color = Color.BLACK
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
        val text = "Received in full and in good condition, invoice 4521"
        if (sideways) {
            g.rotate(Math.PI / 2)
            var x = 260
            while (x < 960) {
                g.drawString(text, 190, -x)
                x += 34
            }
        } else {
            var y = 200
            while (y < 1220) {
                g.drawString(text, 250, y)
                y += 34
            }
        }
        g.dispose()
        val rgba = rgbaMatOf(image)
        return Mat().also { Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2GRAY); rgba.release() }
    }

    private fun quad(l: Double, t: Double, r: Double, b: Double) =
        arrayOf(Point(l, t), Point(r, t), Point(r, b), Point(l, b))

    private fun cuts(gray: Mat, corners: Array<Point>): Boolean {
        val scratch = mutableListOf(gray)
        try {
            return PageEdges.cutsText(gray, corners, scratch)
        } finally {
            scratch.forEach { it.release() }
        }
    }

    @Test
    fun `углы по краям бумаги текст не режут`() {
        assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)
        assertFalse(cuts(photo(), quad(left, top, right, bottom)))
        assertFalse(cuts(photo(grainy = true), quad(left, top, right, bottom)))
    }

    @Test
    fun `край внутри строк — лист не найден`() {
        assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)
        assertTrue("отрезано начало строк", cuts(photo(), quad(400.0, top, right, bottom)))
        assertTrue("отрезаны концы строк", cuts(photo(grainy = true), quad(left, top, 700.0, bottom)))
        assertTrue("лист боком, отрезаны концы строк", cuts(photo(sideways = true), quad(left, 450.0, right, bottom)))
    }
}
