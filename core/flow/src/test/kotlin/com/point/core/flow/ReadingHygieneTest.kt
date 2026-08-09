package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Гигиена прочитанного снаружи — живые находки 2026-08-09: отказ-фраза становилась
 * дверью, markdown провайдера — текстом страницы, арифметика — суммой.
 */
class ReadingHygieneTest {

    @Test
    fun `отказ-фраза не значение — дверь не отрицает саму себя`() {
        assertTrue(startsWithRefusal("нет номера"))
        assertTrue(startsWithRefusal("[нет даты]"))
        assertTrue(startsWithRefusal("не знайдено координат"))
        assertTrue(startsWithRefusal("No phone found"))
        assertTrue(startsWithRefusal("N/A"))

        assertFalse("настоящее значение живёт", startsWithRefusal("Нова Пошта"))
        assertFalse(startsWithRefusal("+380671234567"))
        assertFalse("«Нетішин» — город, не отказ", startsWithRefusal("Нетішин, вул. Шевченка, 3"))
    }

    @Test
    fun `markdown провайдера уходит, строки страницы остаются`() {
        val raw = """
            ![img-0.jpeg](img-0.jpeg)
            # Відділення 1
            Бритівка, Центральна, 586
            ```
        """.trimIndent()

        assertEquals("Відділення 1\nБритівка, Центральна, 586", stripMarkdownChrome(raw))
    }

    @Test
    fun `арифметика — не сумма, одно число — сумма`() {
        assertTrue(looksLikeExpression("127*4.32=548,64"))
        assertTrue(looksLikeExpression("500+548,64=1048,64"))
        assertTrue("две цифры подряд — выкладка, не значение", looksLikeExpression("2500 320"))

        assertFalse(looksLikeExpression("2500"))
        assertFalse(looksLikeExpression("1 200,50"))
    }

    @Test
    fun `относительное слово — не день`() {
        assertTrue(relativeDayWord("вчера"))
        assertTrue(relativeDayWord("Сьогодні"))
        assertTrue(relativeDayWord("tomorrow"))
        assertTrue(relativeDayWord("next minute"))

        assertFalse(relativeDayWord("15.08.2026"))
        assertFalse(relativeDayWord("вчера в 18:00 у метро"))
    }
}
