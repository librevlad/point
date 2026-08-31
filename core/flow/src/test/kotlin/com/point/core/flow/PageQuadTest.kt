package com.point.core.flow

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Место найденного едет с выпрямленной копии обратно на снимок (#1332).
 *
 * Копия — четырёхугольник страницы, растянутый в прямоугольник. Обратный ход обязан ставить
 * найденное туда, куда смотрит человек, а не туда, где оно оказалось после выпрямления.
 */
class PageQuadTest {

    @Test
    fun `прямая страница во весь кадр - место не двигается`() {
        val page = PageQuad(Spot(0f, 0f), Spot(100f, 0f), Spot(100f, 200f), Spot(0f, 200f))

        val box = page.toSourceFrame(Box(10f, 20f, 30f, 40f), copyWidth = 100, copyHeight = 200)

        assertClose(Box(10f, 20f, 30f, 40f), box)
    }

    @Test
    fun `страница углом в кадре - место едет к её углу`() {
        val page = PageQuad(Spot(50f, 30f), Spot(150f, 30f), Spot(150f, 230f), Spot(50f, 230f))

        val box = page.toSourceFrame(Box(0f, 0f, 100f, 200f), copyWidth = 100, copyHeight = 200)

        assertClose(Box(50f, 30f, 150f, 230f), box)
    }

    @Test
    fun `снимок под углом - верх строки уже низа, как на самом снимке`() {
        // Снятая под углом страница: верх кадра дальше от камеры и потому у́же.
        val page = PageQuad(Spot(30f, 0f), Spot(70f, 0f), Spot(100f, 100f), Spot(0f, 100f))

        val top = page.toSourceFrame(Box(0f, 0f, 100f, 1f), copyWidth = 100, copyHeight = 100)
        val bottom = page.toSourceFrame(Box(0f, 99f, 100f, 100f), copyWidth = 100, copyHeight = 100)

        assertTrue(
            "верх страницы на снимке под углом уже низа: было ${top.width} и ${bottom.width}",
            top.width < bottom.width,
        )
        assertClose(Spot(30f, 0f), page.at(0f, 0f))
        assertClose(Spot(70f, 0f), page.at(1f, 0f))
        assertClose(Spot(100f, 100f), page.at(1f, 1f))
        assertClose(Spot(0f, 100f), page.at(0f, 1f))
    }

    @Test
    fun `середина строки на снимке под углом - не середина отрезка между углами`() {
        val page = PageQuad(Spot(30f, 0f), Spot(70f, 0f), Spot(100f, 100f), Spot(0f, 100f))

        val middle = page.at(0.5f, 0.5f)

        // Перспектива, а не растяжение: середина копии уезжает выше середины четырёхугольника.
        assertTrue("перспектива не посчитана: середина осталась на ${middle.y}", middle.y < 50f)
    }

    @Test
    fun `слова слоя приезжают на снимок вместе с местом`() {
        val page = PageQuad(Spot(50f, 30f), Spot(150f, 30f), Spot(150f, 230f), Spot(50f, 230f))
        val layer = AtomLayer(listOf(Atom("a1", "НАКЛАДНА", Box(0f, 0f, 50f, 20f), confidence = 0.9f)))

        val onSource = layer.onSourceFrame(page, copyWidth = 100, copyHeight = 200)

        assertClose(Box(50f, 30f, 100f, 50f), onSource.atoms.single().box)
        assertEquals("слова уже в координатах файла: второго перевода нет", null, onSource.transform)
    }

    @Test
    fun `углы страницы переводятся к файлу тем же ходом, что и место найденного`() {
        val toFile = FrameTransform(sample = 2, uprightWidth = 100, uprightHeight = 200)
        val page = PageQuad(Spot(10f, 20f), Spot(60f, 20f), Spot(60f, 120f), Spot(10f, 120f))

        val onFile = toFile.toRaw(page)

        assertClose(Spot(20f, 40f), onFile.topLeft)
        assertClose(Spot(120f, 240f), onFile.bottomRight)
    }

    private fun assertClose(expected: Box, actual: Box) {
        assertTrue(
            "ждали $expected, пришло $actual",
            abs(expected.left - actual.left) < 0.01f &&
                abs(expected.top - actual.top) < 0.01f &&
                abs(expected.right - actual.right) < 0.01f &&
                abs(expected.bottom - actual.bottom) < 0.01f,
        )
    }

    private fun assertClose(expected: Spot, actual: Spot) {
        assertTrue(
            "ждали $expected, пришло $actual",
            abs(expected.x - actual.x) < 0.01f && abs(expected.y - actual.y) < 0.01f,
        )
    }
}
