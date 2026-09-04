package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Продиктованная дата без года становится днём, если рядом время (#1426 ч.2, решение владельца
 * 04.09.2026: «ближайший день от даты объекта», охват «только с временем рядом»).
 *
 * Живая охота: Whisper отдал «Friday, September 11 at 3 p.m.» — года в речи нет, и «В календарь»
 * не предлагалось. Теперь дата со временем получает ближайший такой день, а дата без времени
 * («родился 5 мая») остаётся текстом, чтобы не превращать любое упоминание в событие.
 */
class SpokenDateTest {

    private val dateKey = META_ENTITY_PREFIX + "date"
    private val lineKey = dateKey + META_LINE_SUFFIX

    private fun obj(date: String, line: String? = null, shotAt: String? = null): Map<String, String> =
        buildMap {
            put(dateKey, date)
            line?.let { put(lineKey, it) }
            shotAt?.let { put(META_SHOT_AT, it) }
        }

    @Test fun `дата со временем без года — ближайший будущий такой день`() {
        val meta = obj("September 11", line = "September 11 at 3 p.m.")

        assertEquals(listOf(LocalDate.of(2026, 9, 11)), eventDays(meta, LocalDate.of(2026, 9, 3)))
        // Сказано после 11 сентября — ближайшее уже в следующем году.
        assertEquals(listOf(LocalDate.of(2027, 9, 11)), eventDays(meta, LocalDate.of(2026, 9, 20)))
    }

    @Test fun `по-русски и по-украински со временем — тоже день`() {
        assertEquals(
            listOf(LocalDate.of(2026, 9, 11)),
            eventDays(obj("11 сентября", line = "11 сентября в 15:00"), LocalDate.of(2026, 9, 3)),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 8, 11)),
            eventDays(obj("11 серпня", line = "зустріч 11 серпня о 9:30"), LocalDate.of(2026, 8, 1)),
        )
    }

    @Test fun `дата без времени рядом остаётся текстом — не выдумываем год`() {
        val meta = obj("September 11", line = "born September 11")

        assertTrue("бездатное «September 11» уехало в календарь", eventDays(meta, LocalDate.of(2026, 9, 3)).isEmpty())
        assertFalse(hasUpcomingDate(meta, LocalDate.of(2026, 9, 3)))
        assertEquals("«11 сентября» без времени стало днём", null, spokenDay("11 сентября", null, LocalDate.of(2026, 9, 3)))
    }

    @Test fun `год из даты объекта, а не из сегодня`() {
        // Объект снят в 2027-м: продиктованный «5 мая» — ближайший от даты съёмки.
        val meta = obj("5 мая", line = "встреча 5 мая в 10:00", shotAt = "01.01.2027, 08:00")

        assertEquals(listOf(LocalDate.of(2027, 5, 5)), eventDays(meta, LocalDate.of(2026, 9, 3)))
    }

    @Test fun `полная дата с годом идёт прежним путём`() {
        assertEquals(
            listOf(LocalDate.of(2026, 9, 11)),
            eventDays(obj("September 11, 2026", line = "September 11, 2026 at 3 p.m."), LocalDate.of(2026, 1, 1)),
        )
        // Без нового кода это уже работало — спутником проверяем, что не сломали.
        assertEquals(LocalDate.of(2026, 9, 11), humanDayOf("September 11, 2026"))
    }

    @Test fun `похожее на дату слово со временем днём не становится`() {
        // «market» — не месяц, поэтому даже со временем рядом дня нет.
        assertEquals(null, spokenDay("market 3", "market 3 at 5 p.m.", LocalDate.of(2026, 9, 3)))
    }
}
