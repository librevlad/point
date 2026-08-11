package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замазанное со снимка не восстанавливается (#549, приёмка 1 и 4).
 *
 * Человек отправляет фото из общественного места, с документом на столе, с чужими детьми в
 * кадре. Замазывание, которое кладёт слой поверх, выглядит так же, но снимается одной
 * командой — это обещание безопасности, которого оно не даёт. Поэтому содержимое именно
 * заменяется: блок становится своим средним цветом, и восстанавливать нечего.
 */
class HiddenAreaIsGoneTest {

    private val width = 60

    private val height = 40

    /** «Текст» на снимке: чёрные штрихи через один столбец на белом — читается по контрасту. */
    private fun page(): IntArray = IntArray(width * height) { i ->
        if ((i % width) % 2 == 0) BLACK else WHITE
    }

    /**
     * Читается ли на месте мелочь размером со штрих: соседние пиксели различаются поодиночке.
     * После замазывания цвет держится блоками, и различать внутри места нечего.
     */
    private fun readable(pixels: IntArray, place: Box): Boolean {
        for (y in place.top.toInt() until place.bottom.toInt()) {
            var run = 1
            for (x in place.left.toInt() + 1 until place.right.toInt()) {
                if (pixels[y * width + x] == pixels[y * width + x - 1]) {
                    run++
                } else {
                    if (run == 1) return true
                    run = 1
                }
            }
        }
        return false
    }

    @Test
    fun `на замазанном месте различать больше нечего`() {
        val pixels = page()
        val place = Box(10f, 10f, 40f, 30f)

        assertTrue("штрихи не различались и до замазывания", readable(pixels, place))
        Redaction.hide(pixels, width, height, listOf(place))

        assertTrue("на замазанном месте всё ещё видны штрихи", !readable(pixels, place))
    }

    @Test
    fun `замазывается только показанное`() {
        val pixels = page()

        Redaction.hide(pixels, width, height, listOf(Box(10f, 10f, 40f, 30f)))

        assertTrue("замазало и то, чего не показывали", readable(pixels, Box(41f, 0f, 60f, 40f)))
        assertTrue(readable(pixels, Box(0f, 0f, 9f, 40f)))
    }

    /** Приёмка 3: несколько мест за один заход. */
    @Test
    fun `несколько мест замазываются за один заход`() {
        val pixels = page()
        val first = Box(2f, 2f, 20f, 18f)
        val second = Box(38f, 20f, 58f, 38f)

        Redaction.hide(pixels, width, height, listOf(first, second))

        assertTrue("первое место осталось читаемым", !readable(pixels, first))
        assertTrue("второе место осталось читаемым", !readable(pixels, second))
        assertTrue("замазало всё между показанными местами", readable(pixels, Box(22f, 2f, 36f, 18f)))
    }

    /**
     * Приёмка 4: содержимое заменено, а не закрыто слоем. Из результата исходные штрихи
     * не достаются никакой обратной операцией — их там больше нет.
     */
    @Test
    fun `исходное содержимое в результате не остаётся`() {
        val was = page()
        val now = page()
        val place = Box(0f, 0f, width.toFloat(), height.toFloat())

        Redaction.hide(now, width, height, listOf(place))

        val samePixels = was.indices.count { was[it] == now[it] }
        assertTrue("половина снимка осталась нетронутой: $samePixels", samePixels < was.size / 2)
    }

    @Test
    fun `место за краем снимка ничего не ломает`() {
        val pixels = page()

        Redaction.hide(pixels, width, height, listOf(Box(-50f, -50f, -10f, -10f)))

        assertTrue(readable(pixels, Box(0f, 0f, 60f, 40f)))
    }

    /** Мазок толщиной в пиксель — тоже место: замазать его должно, а не пропустить. */
    @Test
    fun `совсем узкое место всё равно замазывается`() {
        val pixels = page()
        val thin = Box(10f, 10f, 14f, 11f)

        Redaction.hide(pixels, width, height, listOf(thin))

        assertTrue(!readable(pixels, thin))
    }

    @Test
    fun `показанных мест нет — снимок не трогают`() {
        val was = page()
        val now = page()

        Redaction.hide(now, width, height, emptyList())

        assertEquals(was.toList(), now.toList())
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
