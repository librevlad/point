package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Скрин владельца 2026-08-09 (чек monobank): модель ответила «DATE=26.04.2026
 * 26.04.2026», и слипшееся значение прошло в спор целиком. Несколько дат в одном
 * значении — несколько кандидатов; одинаковые схлопываются.
 */
class UnderstandingProtocolTest {

    private fun dates(answer: String): List<String> =
        parseFieldCandidates(answer).fields[META_ENTITY_PREFIX + "date"].orEmpty().map { it.text }

    @Test
    fun `две одинаковые даты в одном значении — один кандидат`() {
        assertEquals(listOf("26.04.2026"), dates("DATE=26.04.2026 26.04.2026"))
    }

    @Test
    fun `две разные даты в одном значении — два кандидата`() {
        assertEquals(listOf("26.04.2026", "28.04.2026"), dates("DATE=26.04.2026 28.04.2026"))
    }

    @Test
    fun `дата с временем остаётся целым значением`() {
        assertEquals(listOf("26.04.2026 20:04"), dates("DATE=26.04.2026 20:04"))
    }

    @Test
    fun `резаные кандидаты не обходят общий дедуп строк ответа`() {
        assertEquals(
            listOf("26.04.2026"),
            dates("DATE=26.04.2026 26.04.2026\nDATE=26.04.2026"),
        )
    }
}
