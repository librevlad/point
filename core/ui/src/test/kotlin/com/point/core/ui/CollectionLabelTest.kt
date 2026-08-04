package com.point.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Обрезанный список обязан называть себя обрезанным (#460) — и начало текста тоже. */
class CollectionLabelTest {

    private val nbsp = " "

    @Test
    fun `набор целиком — просто число`() {
        assertEquals("Содержимое · 12", collectionLabel(shown = 12, total = 12, atLeast = false))
    }

    @Test
    fun `счёта ещё нет — экран не выдумывает второе число`() {
        assertEquals("Содержимое · 3", collectionLabel(shown = 3, total = 0, atLeast = false))
    }

    @Test
    fun `обрезанный набор называет настоящее число`() {
        assertEquals(
            "Содержимое · 500 из 1${nbsp}340",
            collectionLabel(shown = 500, total = 1340, atLeast = false),
        )
    }

    @Test
    fun `счёт упёрся в потолок — число объявлено нижней границей`() {
        assertEquals(
            "Содержимое · 500 из более чем 10${nbsp}000",
            collectionLabel(shown = 500, total = 10_000, atLeast = true),
        )
    }

    @Test
    fun `потолок при полном списке — сказано, что это не всё`() {
        assertEquals(
            "Содержимое · 300, и это не всё",
            collectionLabel(shown = 300, total = 300, atLeast = true),
        )
    }

    @Test
    fun `разряды отбиты`() {
        assertEquals("7", grouped(7))
        assertEquals("999", grouped(999))
        assertEquals("1${nbsp}000", grouped(1000))
        assertEquals("12${nbsp}345${nbsp}678", grouped(12345678))
    }

    @Test
    fun `короткий текст показан целиком`() {
        assertEquals("привет", textPreviewHead("привет", limit = 100))
    }

    @Test
    fun `длинный текст режется по границе строки`() {
        val text = "первая строка\nвторая строка\nтретья строка"

        val head = textPreviewHead(text, limit = 30)

        assertEquals("первая строка\nвторая строка", head)
        assertTrue(head.length < text.length)
    }

    @Test
    fun `текст без переносов режется ровно по пределу`() {
        val text = "я".repeat(100)

        assertEquals(40, textPreviewHead(text, limit = 40).length)
    }
}
