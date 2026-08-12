package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Нет в тексте — нет знания» (#809, решение владельца 12.08.2026).
 *
 * Живая охота 11.08.2026: на снимке рабочего стола Windows модель назвала дату 11/25/2025,
 * которой на кадре нет, и человек увидел два дня, не зная, какой из них настоящий.
 */
class NoTextNoKnowledgeTest {

    private val desktop = "Корзина  Мой компьютер  11/11/2025  14:32  Пуск"

    @Test
    fun `выдуманной моделью даты в прочитанном тексте нет`() {
        assertFalse(standsInReadText("11/25/2025", desktop))
    }

    @Test
    fun `настоящая дата с кадра остаётся знанием`() {
        assertTrue(standsInReadText("11/11/2025", desktop))
    }

    @Test
    fun `разделители значения не решают ничего`() {
        assertTrue(standsInReadText("11.11.2025", desktop))
        assertTrue(standsInReadText("11 11 2025", desktop))
    }

    @Test
    fun `номер с пробелами по группам стоит на странице, записанной слитно`() {
        assertTrue(standsInReadText("+380 67 636 05 60", "звоните 380676360560 с 9 до 18"))
    }

    @Test
    fun `починка искажения распознавания знанием остаётся`() {
        // Ради этого модель и смотрит на кадр: в словах страницы «1ваненко ван», а имя —
        // «Іваненко Іван» (#747).
        assertTrue(standsInReadText("Іваненко Іван", "Відправник 1ваненко ван Петрович"))
    }

    @Test
    fun `чужое имя починкой не считается`() {
        assertFalse(standsInReadText("Петренко Петро", "Відправник Іваненко Іван Петрович"))
    }

    @Test
    fun `пустое значение на странице не стоит`() {
        assertFalse(standsInReadText("   ", desktop))
    }

    @Test
    fun `пустая страница ничего не подтверждает`() {
        assertFalse(standsInReadText("11/11/2025", ""))
    }
}
