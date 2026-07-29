package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #200 ocr++ logic validation — a model-free safety net that flags cells a lone OCR would silently
 * guess: a stray letter jammed into a number, and a broken monotone id/article run. Column types are
 * inferred from CONTENT (row 0 is the header and is never flagged). Cheap precision before a human sees it.
 */
class TableValidationTest {

    @Test
    fun `flags a number with a stray letter in a numeric column`() {
        val rows = listOf(
            listOf("Дата", "Кількість"), // header
            listOf("01.02", "15"),
            listOf("02.02", "1O0"),       // corrupt: letter O among digits
            listOf("03.02", "18"),
        )
        val bad = validateTable(rows)
        assertTrue("corrupt number flagged", bad.contains(2 to 1))
        assertFalse("clean number not flagged", bad.contains(1 to 1))
        assertFalse("header never flagged", bad.contains(0 to 1))
    }

    @Test
    fun `flags a non-increasing article-number run`() {
        val rows = listOf(
            listOf("Арт. №", "Назва"),
            listOf("11004", "a"),
            listOf("11006", "b"),
            listOf("11005", "c"), // out of order — lower than the previous id
        )
        assertTrue(validateTable(rows).contains(3 to 0))
    }

    @Test
    fun `a clean numeric table has no flags`() {
        val rows = listOf(
            listOf("№", "Сума"),
            listOf("1", "10"),
            listOf("2", "20"),
            listOf("3", "30"),
        )
        assertEquals(emptySet<Pair<Int, Int>>(), validateTable(rows))
    }

    @Test
    fun `a text column is never flagged for its letters`() {
        val rows = listOf(
            listOf("Найменування"),
            listOf("Гречка із свин."),
            listOf("Пшено із свин."),
        )
        assertTrue(validateTable(rows).isEmpty())
    }

    @Test
    fun `ignores markers when reading the value`() {
        // a corrected/uncertain cell already carries its own flag downstream — don't double-report the marker
        val rows = listOf(
            listOf("№", "Кількість"),
            listOf("1", "~~48~~ 40"),
            listOf("2", "48⚠"),
        )
        assertTrue("correction value 40 is clean", validateTable(rows).isEmpty())
    }
}
