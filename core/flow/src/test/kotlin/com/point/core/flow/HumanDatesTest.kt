package com.point.core.flow

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #651, слова владельца: «голое время это никогда не дата, это мусор. дата в прошлом
 * не может создавать событие». Здесь — вторая половина: что считается датой,
 * пригодной для события.
 */
class HumanDatesTest {

    private val today = LocalDate.of(2026, 8, 9)

    @Test
    fun `человеческие форматы дат читаются`() {
        assertEquals(LocalDate.of(2026, 8, 15), parseHumanDate("15.08.2026"))
        assertEquals(LocalDate.of(2026, 8, 15), parseHumanDate("15/08/2026"))
        assertEquals(LocalDate.of(2026, 8, 15), parseHumanDate("2026-08-15"))
        assertEquals(LocalDate.of(2026, 8, 15), parseHumanDate("15.08.26"))
    }

    @Test
    fun `мусор и голое время датой не читаются`() {
        assertEquals(null, parseHumanDate("11:09"))
        assertEquals(null, parseHumanDate("Оплата 2500"))
        assertEquals(null, parseHumanDate(""))
    }

    @Test
    fun `событие достойно даты сегодня и позже — прошлое не создаёт событий`() {
        val future = mapOf("entity.date" to "15.08.2026")
        val past = mapOf("entity.date" to "01.12.2020")
        val todayIs = mapOf("entity.date" to "09.08.2026")

        assertTrue(hasUpcomingDate(future, today))
        assertTrue(hasUpcomingDate(todayIs, today))
        assertFalse(hasUpcomingDate(past, today))
        assertFalse(hasUpcomingDate(emptyMap(), today))
    }

    @Test
    fun `будущая дата среди «ещё»-значений тоже считается`() {
        val meta = mapOf(
            "entity.date" to "01.12.2020",
            "entity.date.more" to altValue(listOf("15.08.2026")),
        )

        assertTrue(hasUpcomingDate(meta, today))
    }

    @Test
    fun `день значения читается и рядом с временем или подписью`() {
        assertEquals(LocalDate.of(2026, 4, 26), humanDayOf("26.04.2026 20:04"))
        assertEquals(LocalDate.of(2020, 12, 1), humanDayOf("01.12.2020 в 11:09"))
        assertEquals(null, humanDayOf("11:09"))
        assertEquals(null, humanDayOf("Оплата 2500"))
    }

    @Test
    fun `дата с временем открывает дверь события наравне с чистой датой`() {
        assertTrue(hasUpcomingDate(mapOf("entity.date" to "15.08.2026 09:30"), today))
        assertFalse(hasUpcomingDate(mapOf("entity.date" to "01.12.2020 в 11:09"), today))
    }

    @Test
    fun `слипшиеся даты режутся на значения, одиночные остаются целыми`() {
        // Скрин владельца 2026-08-09: «дата — или: 26.04.2026 26.04.2026» с чека.
        assertEquals(listOf("26.04.2026"), readDates("26.04.2026 26.04.2026"))
        assertEquals(listOf("26.04.2026", "28.04.2026"), readDates("26.04.2026 28.04.2026"))
        assertEquals(listOf("26.04.2026 20:04"), readDates("26.04.2026 20:04"))
        assertTrue("текст без даты стал датой", readDates("просто текст").isEmpty())
    }
}
