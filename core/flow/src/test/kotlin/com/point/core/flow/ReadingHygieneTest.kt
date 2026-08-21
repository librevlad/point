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
    fun `служебная пометка читателя узнаётся формой, а не словами`() {
        // Дословный ответ OCR.space на снимке без единой надписи (#1054).
        assertTrue(serviceNote("*[No text detected]*"))
        assertTrue("та же пометка без разметки выделения", serviceNote("[No text detected]"))
        assertTrue("пометка другого сервиса другими словами", serviceNote("(На изображении текста нет)"))
        assertTrue("условленный ответ модели", serviceNote("[нет текста]"))
        assertTrue("пометка в курсиве и с пробелами вокруг", serviceNote("  _[no readable text]_ \n"))
    }

    @Test
    fun `текст со страницы пометкой не считается`() {
        assertFalse("обычная строка", serviceNote("Ведомость выдачи материалов"))
        assertFalse("скобки внутри, а не вокруг", serviceNote("Петренко (бригадир) 8300,00"))
        assertFalse("две скобочные группы — не одна пометка", serviceNote("[1] Иванов [2] Петров"))
        assertFalse("номер в скобках — значение, не замечание", serviceNote("(495)"))
        assertFalse("многострочный ответ — страница", serviceNote("[Раздел 1]\nСтрока первая\nСтрока вторая"))
        assertFalse("длинная скобочная строка — уже текст", serviceNote("(" + "слово ".repeat(40) + ")"))
        assertFalse(serviceNote(""))
    }

    @Test
    fun `пустой лист и пометка — один ответ «текста нет», бессмыслица — нет`() {
        assertTrue(noTextAnswer(""))
        assertTrue(noTextAnswer("   \n "))
        assertTrue(noTextAnswer("*[No text detected]*"))

        assertFalse("мусор чтения — не ответ «текста нет», это срыв", noTextAnswer("////////////"))
        assertFalse(noTextAnswer("Tel: 918-682-1561"))
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
