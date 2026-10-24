package com.point.data

import com.point.core.flow.Box
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Лист, снятый боком, видно по детектору (#1044, кадр 23 корпуса).
 *
 * Размеры — в масштабе карты вероятностей: строки таблицы по 12 точек ростом.
 */
class PaddleOcrRunsDownTest {

    private val upright = (0 until 8).map { Box(20f, 30f + it * 20f, 220f, 42f + it * 20f) }

    private val sideways = upright.map { Box(it.top, it.left, it.bottom, it.right) }

    @Test
    fun `строки стоящего листа лежат вширь`() {
        assertFalse(linesRunDown(upright))
    }

    @Test
    fun `строки листа на боку стоят столбиками`() {
        assertTrue(linesRunDown(sideways))
    }

    @Test
    fun `линия рамки и отдельные цифры листа не кладут`() {
        val frameLine = Box(5f, 0f, 9f, 400f)
        val digits = (0 until 10).map { Box(240f, 30f + it * 20f, 250f, 42f + it * 20f) }

        assertFalse(linesRunDown(upright + frameLine + digits))
    }

    @Test
    fun `две вертикальные надписи на пустом месте — ещё не поворот`() {
        assertFalse(linesRunDown(sideways.take(2)))
    }
}
