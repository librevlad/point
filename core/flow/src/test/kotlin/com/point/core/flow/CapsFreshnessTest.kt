package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Одно правило свежести на обе стороны связки (#633, #624): устройство не выдаёт чужое
 * состояние недельной давности за нынешнее. Порознь у телефона и компьютера разъехались бы
 * и сроки, и поведение — молча.
 */
class CapsFreshnessTest {

    private val now = 1_000_000_000L

    @Test fun `только что объявленное — свежее`() {
        assertTrue(capsFresh(now - 60_000, now))
    }

    @Test fun `объявленное вчера уже не выдаётся за нынешнее`() {
        assertFalse(capsFresh(now - 24 * 60 * 60 * 1000, now))
    }

    @Test fun `никогда не объявлявшееся свежим не считается`() {
        assertFalse(capsFresh(null, now))
        assertFalse(capsFresh(0, now))
    }

    @Test fun `на границе срока состояние уже старое`() {
        assertFalse(capsFresh(now - CAPS_FRESH_MS, now))
        assertTrue(capsFresh(now - CAPS_FRESH_MS + 1, now))
    }
}
