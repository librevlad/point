package com.point.core.flow

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож между чужой моделью и человеком (#280).
 *
 * Случай, ради которого он есть, взят из замера 04.08.2026: на архивной рукописи `mistral-ocr`
 * выдал одну сочинённую строку 71 раз подряд, с обычной уверенностью и без слова «не читаю».
 * Человек обязан увидеть отказ, а не выдумку.
 */
class DegeneratedReadingTest {

    private fun repeated(line: String, times: Int) = List(times) { line }.joinToString("\n")

    @Test
    fun `модель зациклилась — это отказ, а не результат`() {
        val reason = degeneratedReading(repeated("Ивановъ Петръ Сидоровичъ, крестьянинъ", 71))
        assertNotNull("зацикливание принято за чтение", reason)
        assertTrue(reason!!, reason.contains("повторена"))
    }

    @Test
    fun `шести повторов подряд уже довольно — порог замера`() {
        assertNotNull(degeneratedReading(repeated("Опись имущества двора", 6)))
        assertNull("пять повторов — ещё не приговор", degeneratedReading(repeated("Опись имущества двора", 5)))
    }

    @Test
    fun `пустой ответ — это не пустая страница`() {
        assertNotNull(degeneratedReading(""))
        assertNotNull(degeneratedReading("   \n\n  \t "))
    }

    @Test
    fun `ответ из одних служебных символов — отказ`() {
        val reason = degeneratedReading("|---|---|---|\n| | | |\n---\n***")
        assertNotNull("шум принят за текст", reason)
        assertTrue(reason!!, reason.contains("буквы"))
    }

    @Test
    fun `настоящая ведомость проходит — повторы там короткие и законные`() {
        val sheet = buildString {
            appendLine("| № | Табельный | Фамилия | Сумма |")
            appendLine("|---|---|---|---|")
            repeat(24) { i -> appendLine("| ${i + 1} | ${1000 + i} | Петренко О.В. | 8300,00 |") }
            appendLine("РАЗОМ: 199 200,00")
        }
        assertNull("живая ведомость забракована", degeneratedReading(sheet))
    }

    @Test
    fun `короткие одинаковые строки повторяются в таблицах законно`() {
        // «шт.» в колонке единиц измерения — это документ, а не зацикливание.
        assertNull(degeneratedReading(repeated("шт.", 40) + "\nВедомость выдачи материалов"))
    }

    @Test
    fun `повторы вразбивку — не зацикливание`() {
        val text = (1..20).joinToString("\n") { i ->
            if (i % 2 == 0) "Итого по разделу: 1200,00" else "Строка расхода номер $i"
        }
        assertNull("чередование принято за цикл", degeneratedReading(text))
    }

    @Test
    fun `одна строка ответа — не повтор`() {
        assertNull(degeneratedReading("Показание счётчика: 04127"))
    }
}
