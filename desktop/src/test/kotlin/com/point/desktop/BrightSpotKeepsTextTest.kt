package com.point.desktop

import com.point.vision.PageVision
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.awt.Color
import java.awt.Font
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Светлое пятно с видимым текстом — не блик (#1044, кадр 07 корпуса): на снимке монитора
 * тонкий серый текст в самом светлом месте экрана закрашивался вместе с «бликом».
 */
class BrightSpotKeepsTextTest {

    private fun screen(): BufferedImage {
        val image = BufferedImage(1000, 700, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.paint = RadialGradientPaint(500f, 350f, 500f, floatArrayOf(0f, 1f), arrayOf(Color(250, 250, 250), Color(210, 210, 210)))
        g.fillRect(0, 0, 1000, 700)
        g.color = Color(110, 110, 110)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
        var y = 60
        while (y < 680) {
            g.drawString("The quick brown fox jumps over the lazy dog 0123456789 settings", 60, y)
            y += 26
        }
        g.dispose()
        return image
    }

    /** Тёмные точки текста в середине кадра — там, где пятно светлее всего. */
    private fun inkInSpot(rgba: Mat): Int {
        val gray = Mat()
        val dark = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(gray.submat(250, 450, 300, 700), dark, 150.0, 255.0, Imgproc.THRESH_BINARY_INV)
            return Core.countNonZero(dark)
        } finally {
            gray.release()
            dark.release()
        }
    }

    @Test
    fun `текст в самом светлом месте после выбеливания на месте`() {
        assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)

        val source = rgbaMatOf(screen())
        val scratch = mutableListOf(source)
        try {
            val before = inkInSpot(source)
            val after = inkInSpot(PageVision.whiten(source, scratch))
            assertTrue("было $before тёмных точек, осталось $after", after >= 0.9 * before)
        } finally {
            scratch.forEach { it.release() }
        }
    }
}
