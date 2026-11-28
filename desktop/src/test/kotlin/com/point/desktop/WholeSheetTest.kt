package com.point.desktop

import com.point.core.flow.PageQuad
import com.point.vision.PageQuads
import com.point.vision.PageVision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import java.awt.Color
import java.awt.Font
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Лист находится целиком там, где его целиком видит человек (#1044, DOC-1; сверка с CamScanner
 * на корпусе владельца, 28.11.2026).
 *
 * Три класса снимков, на которых Point отдавал не тот лист:
 *  - лист упёрся в край кадра или загнут углом — на снимке он пятиугольник, и четыре точки
 *    срезали угол с текстом; проверка разреза их честно отвергала, и лист отдавался кадром
 *    целиком, со всем столом (кадр 18 корпуса);
 *  - лист заполнил кадр — самым крупным четырёхугольником становилась рамка таблицы на нём, и
 *    всё за рамкой терялось (кадр 04 после того, как поиск листа встал первым);
 *  - светлый стол того же тона слился с листом — страница выходила с куском стола (кадр 19).
 *
 * Кадры владельца в репозиторий не попадают: сцены здесь нарисованы по их образу.
 */
class WholeSheetTest {

    @Before fun openCv() = assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)

    private val text = "Received in full, invoice 4521, 18.07.2026"

    /** Что нашёл «Скан»: лист есть — и его углы, если он выпрямлен по краям. */
    private class Found(val quad: PageQuad?)

    private fun page(image: BufferedImage): Found? = onFrame(image) { rgba, scratch ->
        runBlocking { PageVision.straightened(rgba, scratch) }?.let { Found(it.quad) }
    }

    /** Лежит ли точка внутри найденного листа (углы — на развёрнутом кадре). */
    private fun PageQuad.holds(x: Double, y: Double): Boolean {
        val pts = listOf(topLeft, topRight, bottomRight, bottomLeft).map { Point(it.x.toDouble(), it.y.toDouble()) }
        var sign = 0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val z = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (z > 0) 1 else if (z < 0) -1 else 0
            if (s == 0) continue
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun canvas(width: Int, height: Int, paint: java.awt.Graphics2D.() -> Unit): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                paint()
                dispose()
            }
        }

    /** Пёстрый тёмный грунт — как гравий под листом на кадре 18. */
    private fun java.awt.Graphics2D.gravel(width: Int, height: Int) {
        color = Color(95, 88, 78)
        fillRect(0, 0, width, height)
        val random = java.util.Random(18)
        repeat(2500) {
            val shade = 60 + random.nextInt(70)
            color = Color(shade, shade - 6, shade - 14)
            val r = 2 + random.nextInt(7)
            fillOval(random.nextInt(width), random.nextInt(height), r, r)
        }
    }

    @Test
    fun `лист, упёршийся в край кадра углом, находится целиком, а не отдаётся кадром`() {
        // Низ листа уходит за кромку кадра, а левый нижний угол загнут: на снимке пятиугольник.
        val sheet = Polygon(intArrayOf(70, 1110, 1060, 260, 45), intArrayOf(130, 70, 1400, 1400, 1150), 5)
        val shot = canvas(1200, 1400) {
            gravel(1200, 1400)
            color = Color(242, 242, 238)
            fillPolygon(sheet)
            color = Color.BLACK
            font = Font(Font.SANS_SERIF, Font.PLAIN, 24)
            var y = 230
            while (y < 1320) {
                // Строки доходят до самого загнутого угла: срезанный угол уносит их начала.
                val from = if (y > 1080) 170 else 130
                drawString(text, from, y)
                y += 40
            }
        }

        val found = page(shot)

        assertNotNull("лист не найден вовсе — отдан бы кадр целиком со столом", found)
        val quad = found!!.quad
        assertNotNull("лист найден, но без углов — он не выпрямлен по краям", quad)
        // Начала нижних строк у загнутого угла — внутри листа, а не срезаны с углом.
        assertTrue("срезан загнутый угол с началами строк", quad!!.holds(180.0, 1270.0))
        assertTrue("срезан угол листа у верха", quad.holds(140.0, 240.0))
        // Стол за правым краем листа в страницу не вошёл.
        assertFalse("в страницу вошёл стол справа от листа", quad.holds(1170.0, 700.0))
    }

    @Test
    fun `рамка, напечатанная на листе, — не край листа`() {
        // Лист заполнил кадр; на нём рамка таблицы, а под рамкой — строка подписи.
        val shot = canvas(1200, 1400) {
            color = Color(240, 240, 236)
            fillRect(0, 0, 1200, 1400)
            color = Color.BLACK
            stroke = java.awt.BasicStroke(4f)
            drawRect(150, 180, 900, 1000)
            font = Font(Font.SANS_SERIF, Font.PLAIN, 24)
            var y = 260
            while (y < 1140) {
                drawString(text, 190, y)
                y += 40
            }
            drawString("Подпись получателя ________  18.07.2026", 190, 1310)
        }

        val found = page(shot)

        // Края листа на снимке нет: честный ответ — кадр целиком (null) или лист вместе с
        // подписью. Рамка таблицы вместо листа подпись бы потеряла.
        val quad = found?.quad
        if (quad != null) {
            assertTrue("за рамкой осталась подпись — лист обрезан по рамке таблицы", quad.holds(400.0, 1300.0))
        }
    }

    @Test
    fun `светлый стол другого тона и обложка не входят в лист`() {
        // Как кадр 19: книжка раскрыта, слева тёмная обложка, сверху светлое тёплое дерево той
        // же яркости, что зеленоватая страница; страница уходит за правый и нижний край кадра.
        val shot = canvas(1200, 1400) {
            color = Color(222, 196, 160)
            fillRect(0, 0, 1200, 1400)
            color = Color(35, 35, 40)
            fillRect(20, 90, 90, 1310)
            color = Color(204, 212, 196)
            fillRect(110, 120, 1090, 1280)
            color = Color(40, 40, 40)
            font = Font(Font.SANS_SERIF, Font.PLAIN, 24)
            var y = 200
            while (y < 1380) {
                drawString(text, 150, y)
                y += 40
            }
        }

        val found = page(shot)

        assertNotNull("страница не найдена", found)
        val quad = found!!.quad
        assertNotNull("страница найдена без углов", quad)
        assertFalse("в страницу вошло дерево над ней", quad!!.holds(600.0, 50.0))
        assertFalse("в страницу вошла обложка слева", quad.holds(60.0, 700.0))
        assertTrue("сама страница потерялась", quad.holds(600.0, 700.0))
    }

    @Test
    fun `охватывающий четырёхугольник берёт пятиугольник целиком и не выходит за кадр`() {
        val pentagon = listOf(Point(70.0, 130.0), Point(1110.0, 70.0), Point(1060.0, 1399.0), Point(260.0, 1399.0), Point(45.0, 1150.0))
        val hull = MatOfPoint2f(*pentagon.toTypedArray())
        val quad = PageQuads.enclosing(hull, Size(1200.0, 1400.0))
        hull.release()

        assertNotNull(quad)
        quad!!.forEach { assertTrue("угол $it за кадром", it.x in 0.0..1199.0 && it.y in 0.0..1399.0) }
        val area = { pts: List<Point> -> Math.abs(pts.indices.sumOf { i -> pts[i].x * pts[(i + 1) % pts.size].y - pts[(i + 1) % pts.size].x * pts[i].y }) / 2 }
        assertTrue("охват прибавил больше узкого клина", area(quad.toList()) <= 1.05 * area(pentagon))
    }

    @Test
    fun `круг — не лист с углом`() {
        val circle = (0 until 40).map { Point(600 + 400 * Math.cos(it * Math.PI / 20), 700 + 400 * Math.sin(it * Math.PI / 20)) }
        val hull = MatOfPoint2f(*circle.toTypedArray())
        val quad = PageQuads.enclosing(hull, Size(1200.0, 1400.0))
        hull.release()

        assertNull("круг охвачен четырёхугольником как лист", quad)
    }

    @Test
    fun `слипшиеся углы и вогнутость — не лист`() {
        val frame = Size(1200.0, 1400.0)
        assertTrue(PageQuads.plausible(arrayOf(Point(100.0, 100.0), Point(1000.0, 120.0), Point(980.0, 1200.0), Point(120.0, 1180.0)), frame))
        assertFalse("два угла в одной точке", PageQuads.plausible(arrayOf(Point(0.0, 0.0), Point(1075.0, 0.0), Point(919.0, 1238.0), Point(919.0, 1238.0)), frame))
        assertFalse("вогнутый", PageQuads.plausible(arrayOf(Point(100.0, 100.0), Point(1000.0, 100.0), Point(500.0, 300.0), Point(100.0, 1200.0)), frame))
    }
}
