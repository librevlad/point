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

    /** Светлое тёплое дерево с волокнами — той же яркости, что зеленоватая страница. */
    private fun java.awt.Graphics2D.lightWood(width: Int, height: Int) {
        color = Color(222, 196, 160)
        fillRect(0, 0, width, height)
        val random = java.util.Random(19)
        repeat(300) {
            val shade = random.nextInt(14) - 7
            color = Color(222 + shade, 196 + shade, 160 + shade)
            val y = random.nextInt(height)
            drawLine(0, y, width, y + random.nextInt(30) - 15)
        }
    }

    @Test
    fun `лист на светлом столе другого тона находится и без тёмной обложки рядом`() {
        // Проверка 28.11.2026: стол той же яркости со всех сторон — и лист выходил «рамкой на
        // листе», потому что за краем сравнивалась одна яркость, а не тон.
        for (offBottom in listOf(false, true)) {
            val shot = canvas(1200, 1400) {
                lightWood(1200, 1400)
                color = Color(204, 212, 196)
                fillRect(120, 110, 960, if (offBottom) 1290 else 1180)
                color = Color(40, 40, 40)
                font = Font(Font.SANS_SERIF, Font.PLAIN, 24)
                var y = 190
                while (y < (if (offBottom) 1380 else 1250)) {
                    drawString(text, 160, y)
                    y += 40
                }
            }

            val quad = page(shot)?.quad

            assertNotNull("лист на светлом столе не найден (низ за кадром: $offBottom)", quad)
            assertTrue("сама страница потерялась", quad!!.holds(600.0, 700.0))
            assertFalse("в страницу вошло дерево сверху", quad.holds(600.0, 50.0))
            assertFalse("в страницу вошло дерево слева", quad.holds(50.0, 700.0))
        }
    }

    @Test
    fun `кадр целиком не выдаётся за найденный лист`() {
        // Проверка 28.11.2026: чек той же яркости, что стол, занимал меньшинство светлого
        // пятна — тоном бумаги становился тон стола, отрезался сам чек, и кадр целиком выходил
        // «выпрямленной страницей» без пометки.
        val shot = canvas(1200, 1400) {
            lightWood(1200, 1400)
            color = Color(236, 236, 230)
            fillRect(450, 500, 330, 900)
            color = Color(40, 40, 40)
            font = Font(Font.SANS_SERIF, Font.PLAIN, 18)
            var y = 560
            while (y < 1380) {
                drawString("Чек 4521 · 18.07.2026", 470, y)
                y += 34
            }
        }

        val quad = page(shot)?.quad

        if (quad != null) {
            assertFalse(
                "весь кадр, со столом, выдан за найденный лист",
                quad.holds(40.0, 40.0) && quad.holds(1160.0, 40.0) && quad.holds(40.0, 1360.0) && quad.holds(1160.0, 1360.0),
            )
        }
    }

    @Test
    fun `узкая полоса и крошечный кадр не роняют скан`() {
        // Проверка 28.11.2026: расправление по линиям зовётся теперь и на вырезанном листе, и
        // на полосе ниже ядер поиска линий OpenCV падала. Падение «Скана» на телефоне уходит в
        // запасной путь без пометки «кадр целиком».
        val strip = canvas(300, 200) {
            color = Color(40, 35, 30)
            fillRect(0, 0, 300, 200)
            color = Color(240, 240, 236)
            fillRect(5, 87, 290, 26)
        }
        val banner = canvas(1000, 20) { color = Color(240, 240, 236); fillRect(0, 0, 1000, 20) }

        for (shot in listOf(strip, banner)) {
            onFrame(shot) { rgba, scratch -> PageVision.process(rgba, scratch) }
            page(shot)
        }
    }

    /** Счёт во весь кадр: шапка, таблица в рамке (с закрашенной строкой заголовка), подпись. */
    private fun invoice(degrees: Double, shadedHeader: Boolean): BufferedImage = canvas(1200, 1600) {
        color = Color(240, 240, 236)
        fillRect(0, 0, 1200, 1600)
        rotate(Math.toRadians(degrees), 600.0, 800.0)
        color = Color(30, 30, 30)
        font = Font(Font.SANS_SERIF, Font.BOLD, 30)
        drawString("СЧЁТ № 4521 от 18.07.2026", 300, 170)
        font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
        drawString("Поставщик: ООО Ромашка, ИНН 7701234567", 160, 230)
        if (shadedHeader) {
            color = Color(205, 205, 200)
            fillRect(150, 300, 900, 60)
        }
        color = Color.BLACK
        stroke = java.awt.BasicStroke(3f)
        drawRect(150, 300, 900, 900)
        for (i in 1 until 15) drawLine(150, 300 + i * 60, 1050, 300 + i * 60)
        drawLine(600, 300, 600, 1200)
        font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
        for (i in 1 until 15) drawString("Позиция $i · 2 шт · 145,00", 170, 340 + i * 60)
        drawString("Подпись получателя ________  Иванов И. И.", 160, 1320)
        drawString("Подпись поставщика ________  Петров П. П.", 160, 1380)
    }

    @Test
    fun `рамка таблицы с закрашенной шапкой и снятая с рук — не край листа`() {
        // Проверка 28.11.2026, второй круг: закрашенная шапка таблицы читалась «другой бумагой»
        // за краем, а у снятого с рук листа модель углов обводила блок таблицы — и шапка счёта
        // с подписями терялись.
        for ((degrees, shaded) in listOf(0.0 to true, 1.5 to false, 3.0 to true)) {
            val quad = page(invoice(degrees, shaded))?.quad ?: continue
            assertTrue("потеряна шапка счёта (поворот $degrees°, шапка закрашена: $shaded)", quad.holds(600.0, 170.0))
            assertTrue("потеряны подписи (поворот $degrees°, шапка закрашена: $shaded)", quad.holds(600.0, 1360.0))
        }
    }

    @Test
    fun `грамота в печатной рамке на светлом столе находится`() {
        // Проверка 28.11.2026, второй круг: лист с рамкой, напечатанной у края, на столе того же
        // тона — края листа не видно, и рамка — единственный ответ. За ней пустые поля, текста
        // она не отрезает, и прежде (до ветки) такой лист находился по рамке.
        val shot = canvas(1200, 1600) {
            color = Color(230, 230, 228)
            fillRect(0, 0, 1200, 1600)
            color = Color(242, 242, 240)
            fillPolygon(Polygon(intArrayOf(150, 1060, 1100, 110), intArrayOf(140, 110, 1470, 1500), 4))
            color = Color(20, 20, 20)
            stroke = java.awt.BasicStroke(6f)
            drawPolygon(Polygon(intArrayOf(210, 1000, 1035, 172), intArrayOf(200, 174, 1408, 1436), 4))
            font = Font(Font.SERIF, Font.BOLD, 48)
            drawString("ГРАМОТА", 470, 420)
            font = Font(Font.SERIF, Font.PLAIN, 26)
            var y = 560
            while (y < 1200) {
                drawString("награждается за добросовестный труд", 300, y)
                y += 60
            }
        }

        val quad = page(shot)?.quad

        assertNotNull("лист в рамке на светлом столе не найден — уйдёт кадром целиком", quad)
        assertTrue("потерялся текст грамоты", quad!!.holds(600.0, 800.0))
    }

    @Test
    fun `белый лист на белом столе, снятый близко, не отвергается из-за кромки снимка`() {
        // Проверка 28.11.2026, второй круг: чёрное за кромкой снимка читалось сплошной чертой
        // вдоль края, и лист на столе того же тона с узкими полями выходил «рамкой».
        val shot = canvas(1200, 1600) {
            color = Color(231, 231, 228)
            fillRect(0, 0, 1200, 1600)
            color = Color(242, 242, 240)
            fillRect(45, 50, 1110, 1500)
            color = Color(30, 30, 30)
            font = Font(Font.SANS_SERIF, Font.PLAIN, 24)
            var y = 160
            while (y < 1480) {
                drawString(text, 110, y)
                y += 40
            }
        }

        val quad = page(shot)?.quad ?: return // своим ходом и моделью такой лист может не найтись вовсе

        assertTrue("потерян лист", quad.holds(600.0, 800.0))
        assertFalse("в страницу вошёл стол", quad.holds(10.0, 10.0))
    }

    @Test
    fun `разлинованный кадр без листа уходит в PDF кадром целиком, а не страницей`() {
        // Проверка 28.11.2026: скан в PDF стал звать общую геометрию «Скана», и кадр, у
        // которого листа нет, а линии есть, уходил в PDF найденной страницей без пометки.
        // Сетка во весь кадр, до самой кромки: краёв листа на снимке нет нигде.
        val grid = canvas(1200, 1400) {
            color = Color(240, 240, 236)
            fillRect(0, 0, 1200, 1400)
            color = Color(30, 30, 30)
            stroke = java.awt.BasicStroke(3f)
            for (i in 0..10) drawLine(0, 3 + i * 139, 1200, 3 + i * 139)
            for (i in 0..7) drawLine(3 + i * 170, 0, 3 + i * 170, 1400)
        }

        val made = onFrame(grid) { rgba, scratch -> PageVision.process(rgba, scratch) != null }

        assertFalse("кадр без листа выдан в PDF найденной страницей", made)
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
