package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #200 ocr++ multi-model consensus — several vision models read the same table; [reconcile] votes each
 * cell. Cells where the models agree pass through clean; cells where they disagree get the plurality
 * value, a ⚠ flag, and the distinct readings as candidates (the owner then picks — «шаманство с
 * ячейками»). Pure and model-free, so it is unit-tested directly.
 */
class TableConsensusTest {

    private val header = listOf("№", "Кількість")

    @Test
    fun `unanimous cells pass through clean with no candidates`() {
        val t = listOf(header, listOf("1", "5"), listOf("2", "8"))
        val c = reconcile(listOf(t, t, t))
        assertEquals(listOf(header, listOf("1", "5"), listOf("2", "8")), c.rows)
        assertTrue(c.candidates.isEmpty())
    }

    @Test
    fun `a disagreed cell gets the plurality value, a flag and the distinct candidates`() {
        val a = listOf(header, listOf("1", "5"))
        val b = listOf(header, listOf("1", "5"))
        val d = listOf(header, listOf("1", "8"))
        val c = reconcile(listOf(a, b, d))
        assertEquals("5⚠", c.rows[1][1])                 // plurality 5 of {5,5,8}, flagged uncertain
        assertEquals(listOf("5", "8"), c.candidates[1 to 1])
        assertTrue(c.candidates[1 to 0] == null)          // the id cell agreed
    }

    @Test
    fun `agreement ignores spacing and dashes`() {
        val a = listOf(header, listOf("1", "0,72 0,883"))
        val b = listOf(header, listOf("1", "0,72  0,883"))
        val c = reconcile(listOf(a, b))
        assertTrue("spacing-only diff is agreement", c.candidates.isEmpty())
    }

    @Test
    fun `a single table passes through unchanged`() {
        val a = listOf(header, listOf("1", "5"))
        assertEquals(a, reconcile(listOf(a)).rows)
    }

    @Test
    fun `ragged tables align by index and vote on present values`() {
        val a = listOf(header, listOf("1", "5"), listOf("2", "9"))
        val b = listOf(header, listOf("1", "5")) // shorter — no row 2
        val c = reconcile(listOf(a, b))
        assertEquals("9", c.rows[2][1])            // only a has it → taken as-is, not flagged
        assertTrue(c.candidates.isEmpty())
    }
}
