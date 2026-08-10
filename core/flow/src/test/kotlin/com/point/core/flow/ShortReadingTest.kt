package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Короткое чтение раньше не судилось вовсе: до тридцати символов любой ответ движка
 * объявлялся годным (#694). Здесь два корпуса. Настоящие короткие чтения обязаны
 * выжить, мусор обязан быть отсеян.
 */
class ShortReadingTest {

    private val realReadings = listOf(
        "2500 грн",
        "1 250,50 грн",
        "Оплачено 500 грн",
        "12.05.2026",
        "Термін до 31-12-2026",
        "18:00",
        "Накладна № 59000123456789",
        "№ 20 4514 9154 9395",
        "+380 67 123 45 67",
        "+380671234567",
        "UA793220010000026208373515609",
        "Молоко",
        "Зачинено",
        "Приймальня",
        "Ведомость",
    )

    private val garbage = listOf(
        ". aa - 11 ВЕНЕ",
        "| | ~~ .",
        "a . - , i",
        "»« ~~ ][",
        "e ° a =",
        "|//~ ]{}",
        "1 2 3",
        "...",
        "   ",
    )

    @Test
    fun `короткое настоящее чтение остаётся чтением`() {
        val lost = realReadings.filter { looksLikeOcrGarbage(it) }

        assertTrue("настоящее чтение потеряно- $lost", lost.isEmpty())
    }

    @Test
    fun `короткий мусор больше не проходит за чтение`() {
        val passed = garbage.filterNot { looksLikeOcrGarbage(it) }

        assertTrue("мусор прошёл за чтение- $passed", passed.isEmpty())
    }

    @Test
    fun `дословный ответ движка на снимке без текста отсеивается`() {
        assertTrue("обрывки в один-два символа без единого значения", looksLikeOcrGarbage(". aa - 11 ВЕНЕ"))
    }

    @Test
    fun `сумма, дата и номер накладной сами по себе — уже чтение`() {
        assertFalse(looksLikeOcrGarbage("2500 грн"))
        assertFalse(looksLikeOcrGarbage("12.05.2026"))
        assertFalse(looksLikeOcrGarbage("Накладна № 59000123456789"))
    }

    @Test
    fun `номер телефона рассыпан по группам, и это не повод его выбросить`() {
        assertFalse(looksLikeOcrGarbage("+380 67 123 45 67"))
    }

    @Test
    fun `одного живого слова достаточно`() {
        assertFalse(looksLikeOcrGarbage("Молоко"))
        assertTrue("двух букв мало", looksLikeOcrGarbage("мо"))
    }

    @Test
    fun `длинные ответы судятся как прежде`() {
        val page = "Накладна № 59000123456789 від 12.05.2026, отримувач Іваненко Іван Іванович"

        assertFalse("живая страница осталась чтением", looksLikeOcrGarbage(page))
        assertTrue("длинная каша осталась кашей", looksLikeOcrGarbage("|//~ ]{} ".repeat(6)))
    }
}
