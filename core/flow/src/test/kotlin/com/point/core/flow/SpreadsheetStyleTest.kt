package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #200 ocr++ cell markers — the vision model marks a struck correction «~~53~~ 40», a bare
 * strike-through «~~52~~», and an uncertain reading with a trailing «⚠». [styleCell] turns those
 * into rendering hints so the .xlsx shows the final value, strikes the struck, and highlights the
 * uncertain for the user to confirm (never a silent guess).
 */
class SpreadsheetStyleTest {

    @Test
    fun `plain cell is unchanged`() {
        val s = styleCell("Гречка")
        assertEquals("Гречка", s.value)
        assertFalse(s.flagged); assertFalse(s.corrected); assertFalse(s.strike)
    }

    @Test
    fun `trailing warning marks the cell flagged and is stripped`() {
        val s = styleCell("Гречка⚠")
        assertEquals("Гречка", s.value)
        assertTrue(s.flagged)
    }

    @Test
    fun `correction keeps the new value and records the original`() {
        val s = styleCell("~~53~~ 40")
        assertEquals("40", s.value)
        assertTrue(s.corrected); assertEquals("53", s.original); assertFalse(s.strike)
    }

    @Test
    fun `a bare strike keeps the struck value and marks strike`() {
        val s = styleCell("~~52~~")
        assertEquals("52", s.value)
        assertTrue(s.strike); assertFalse(s.corrected)
    }

    @Test
    fun `a flag combines with a correction`() {
        val s = styleCell("~~53~~ 40⚠")
        assertEquals("40", s.value)
        assertTrue(s.corrected); assertTrue(s.flagged)
    }

    @Test
    fun `blank stays blank`() {
        assertEquals("", styleCell("").value)
    }
}
