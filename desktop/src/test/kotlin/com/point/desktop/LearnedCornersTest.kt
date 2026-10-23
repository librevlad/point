package com.point.desktop

import com.point.vision.LearnedCorners
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.awt.Color
import java.awt.Polygon
import java.awt.image.BufferedImage

/**
 * Второй ход поиска листа — обученная модель углов (#1044).
 *
 * Модель исполняется по-настоящему, той же OpenCV, что у компьютера в работе. Проверяется
 * ровно её слово: на пёстром светлом столе она находит четыре угла бежевого листа, а на
 * пёстром столе без листа честно говорит «документа нет» — догадка вместо страницы была бы
 * хуже, чем «не нашлось».
 */
class LearnedCornersTest {

    @Before fun openCv() = assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)

    private val sheet = listOf(300 to 150, 950 to 200, 900 to 780, 250 to 720)

    /** Светлый шумный стол в пятнах; лист почти того же цвета — своему ходу зацепиться не за что. */
    private fun busyDesk(withSheet: Boolean): Mat {
        val random = java.util.Random(7)
        val image = BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 900) for (x in 0 until 1200) {
            image.setRGB(x, y, Color(170 + random.nextInt(60), 170 + random.nextInt(60), 170 + random.nextInt(60)).rgb)
        }
        image.createGraphics().apply {
            repeat(300) {
                color = Color(150 + random.nextInt(105), 150 + random.nextInt(105), 150 + random.nextInt(105))
                val r = 5 + random.nextInt(35)
                fillOval(random.nextInt(1200) - r, random.nextInt(900) - r, 2 * r, 2 * r)
            }
            if (withSheet) {
                color = Color(228, 226, 220)
                fillPolygon(Polygon(sheet.map { it.first }.toIntArray(), sheet.map { it.second }.toIntArray(), 4))
                color = Color(60, 60, 60)
                for (i in 0 until 14) {
                    val t = 0.12 + i * 0.055
                    drawLine((315 - 50 * t).toInt(), (155 + 570 * t).toInt(), (885 - 50 * t).toInt(), (195 + 580 * t).toInt())
                }
            }
            dispose()
        }
        return rgbaOf(image)
    }

    private fun rgbaOf(image: BufferedImage): Mat {
        val bytes = ByteArray(image.width * image.height * 4)
        var i = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            bytes[i++] = (rgb shr 16).toByte()
            bytes[i++] = (rgb shr 8).toByte()
            bytes[i++] = rgb.toByte()
            bytes[i++] = 0xFF.toByte()
        }
        return Mat(image.height, image.width, CvType.CV_8UC4).apply { put(0, 0, bytes) }
    }

    @Test fun `на пёстром столе модель находит четыре угла листа`() {
        val scratch = mutableListOf<Mat>()
        val corners = LearnedCorners.find(busyDesk(withSheet = true), scratch)
        scratch.forEach(Mat::release)

        assertNotNull("модель не нашла лист, который видно глазом", corners)
        corners!!.zip(sheet).forEach { (found, truth) ->
            val miss = Math.hypot(found.x - truth.first, found.y - truth.second)
            assertTrue("угол $truth найден в $found — промах ${miss.toInt()} px", miss < 40)
        }
    }

    @Test fun `без листа модель не выдумывает страницу`() {
        val scratch = mutableListOf<Mat>()
        val corners = LearnedCorners.find(busyDesk(withSheet = false), scratch)
        scratch.forEach(Mat::release)

        assertNull("на пустом столе найдена страница: ${corners?.toList()}", corners)
    }
}
