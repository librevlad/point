package com.point.executors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadsheetFormatTest {

    @Test
    fun `aligns columns to their widest cell`() {
        val out = formatSpreadsheet(listOf(listOf("A", "BB"), listOf("CCC", "D")))
        val lines = out.split("\n")
        assertEquals("A    BB", lines[0]) // "A" padded to width 3, gutter, "BB"
        assertEquals("CCC  D", lines[1]) // trailing pad on last column trimmed
    }

    @Test
    fun `truncates over-wide cells with an ellipsis`() {
        val out = formatSpreadsheet(listOf(listOf("x".repeat(50))), maxColWidth = 10)
        assertEquals(10, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `ragged rows keep their column count`() {
        val out = formatSpreadsheet(listOf(listOf("a", "b", "c"), listOf("d")))
        assertEquals(3, out.split("\n")[0].split("  ").size)
    }

    @Test
    fun `empty input yields empty string`() {
        assertEquals("", formatSpreadsheet(emptyList()))
    }
}
