package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Вес объекта человеческими словами (#459) — то, что стоит под объектом, когда длину сказать
 *  нечем. Проверяется не арифметика, а читаемость: разряд один, лишней дроби нет. */
class ObjectSizeTest {

    @Test
    fun `разряд один, и он тот, в котором человек и думает`() {
        assertEquals("512 Б", humanWeight(512))
        assertEquals("540 КБ", humanWeight(540L * 1024))
        assertEquals("5,2 МБ", humanWeight((5.2 * 1024 * 1024).toLong()))
        assertEquals("1,5 ГБ", humanWeight((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `круглое число остаётся круглым — «2 МБ», а не «2,0 МБ»`() {
        assertEquals("2 МБ", humanWeight(2L * 1024 * 1024))
    }

    @Test
    fun `веса нет — и слова нет`() {
        assertNull(humanWeight(0))
        assertNull(humanWeight(-1))
    }
}
