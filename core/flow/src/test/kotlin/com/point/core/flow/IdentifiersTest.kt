package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that stops a waybill number from falling through the floor (#222). ML Kit reads it as
 * a phone, [isPlausible] correctly drops it as undialable, and until now nobody picked it up.
 */
class IdentifiersTest {

    @Test
    fun `finds the waybill from a real Nova Poshta screenshot, spaces and all`() {
        // The number off the parcel screen that started this whole change.
        val text = "Прибула в пункт 1, Олексіївка\n20 4514 9154 9395\nОстанній день зберігання – 29.07"

        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers(text))
    }

    @Test
    fun `finds an ungrouped waybill too`() {
        assertEquals(listOf("20451491549395"), waybillNumbers("ТТН 20451491549395 прибула"))
    }

    @Test
    fun `a phone is not a waybill`() {
        // 12 digits. The two rules must not fight over the same string.
        assertTrue(waybillNumbers("тел. +380 67 123 45 67").isEmpty())
    }

    @Test
    fun `a card number is not a waybill`() {
        // 16 digits — PAYMENT_CARD has its own path and its own masking.
        assertTrue(waybillNumbers("4149 6293 1234 5678").isEmpty())
    }

    @Test
    fun `dates and small numbers are left alone`() {
        assertTrue(waybillNumbers("Invoice № 146 від 08.06.2026, 8 970.00 грн").isEmpty())
    }

    @Test
    fun `a longer digit run is not silently truncated to 14`() {
        // An account number must not be mistaken for a waybill just because it contains one.
        assertTrue(waybillNumbers("рахунок 202045149154939512").isEmpty())
    }

    @Test
    fun `the same number twice is reported once`() {
        val text = "20 4514 9154 9395 ... повторно 20 4514 9154 9395"

        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers(text))
    }

    @Test
    fun `does not run across a line break into a neighbouring number`() {
        // Newlines are not grouping — «1234567\n8901234567» is two numbers, not one waybill.
        assertTrue(waybillNumbers("1234567\n8901234567").isEmpty())
    }

    @Test
    fun `blank text yields nothing`() {
        assertTrue(waybillNumbers("").isEmpty())
        assertTrue(waybillNumbers("   \n  ").isEmpty())
    }

    @Test
    fun `the reading is marked structural, not verified`() {
        // No published check-digit algorithm went into the rule, and the pipeline must know:
        // consensus or a later validator is what raises this.
        assertTrue("structural match must stay below certainty", WAYBILL_CONFIDENCE < 1f)
    }
}
