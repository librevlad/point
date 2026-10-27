package com.point.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionLabelTest {

    @Test
    fun `набор целиком — просто число`() {
        val label = collectionLabel(shown = 12, total = 12, atLeast = false)
        assertTrue("starts with Содержимое", label.startsWith("Содержимое"))
        assertTrue("contains shown count", label.contains("12"))
        assertFalse("does not contain 'из'", label.contains("из"))
    }

    @Test
    fun `счёта ещё нет — экран не выдумывает второе число`() {
        val label = collectionLabel(shown = 3, total = 0, atLeast = false)
        assertTrue("starts with Содержимое", label.startsWith("Содержимое"))
        assertTrue("contains shown count", label.contains("3"))
        assertFalse("does not contain 'из'", label.contains("из"))
    }

    @Test
    fun `обрезанный набор называет настоящее число`() {
        val label = collectionLabel(shown = 500, total = 1340, atLeast = false)
        assertTrue("starts with Содержимое: $label", label.startsWith("Содержимое"))
        assertTrue("contains shown count: $label", label.contains("500"))
        assertTrue("contains total count: $label", label.contains("1340") || label.contains("1 340") || label.contains("1 340"))
        assertTrue("contains 'из': $label", label.contains("из"))
    }

    @Test
    fun `счёт упёрся в потолок — число объявлено нижней границей`() {
        val label = collectionLabel(shown = 500, total = 10_000, atLeast = true)
        assertTrue("starts with Содержимое: $label", label.startsWith("Содержимое"))
        assertTrue("contains shown count: $label", label.contains("500"))
        assertTrue("contains 'более чем': $label", label.contains("более чем"))
        assertTrue("contains total count: $label", label.contains("10000") || label.contains("10 000") || label.contains("10 000"))
    }

    @Test
    fun `потолок при полном списке — сказано, что это не всё`() {
        val label = collectionLabel(shown = 300, total = 300, atLeast = true)
        assertTrue("starts with Содержимое", label.startsWith("Содержимое"))
        assertTrue("contains shown count", label.contains("300"))
        assertTrue("contains 'не всё'", label.contains("не всё"))
    }

    @Test
    fun `разряды отбиты`() {
        val nbsp = " "
        assertEquals("7", grouped(7))
        assertEquals("999", grouped(999))
        assertEquals("1${nbsp}000", grouped(1000))
        assertEquals("12${nbsp}345${nbsp}678", grouped(12345678))
    }

    @Test
    fun `короткий текст показан целиком`() {
        val head = textPreviewHead("привет", limit = 100)
        assertEquals("привет", head)
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
