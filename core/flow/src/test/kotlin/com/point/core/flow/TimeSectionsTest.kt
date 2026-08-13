package com.point.core.flow

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Список режется по календарю, а не по прошедшим часам (#931).
 *
 * Живой снимок 13.08.2026 в 16:35: под заголовком «СЕГОДНЯ» стояли объекты вчерашнего дня —
 * 20:12 и 18:07 двенадцатого числа. Заголовка «ВЧЕРА» в списке не было вовсе, потому что
 * «сегодня» означало «меньше суток назад». Утром в девять сегодняшним числился весь вчерашний
 * рабочий день, а заголовок секции — единственная карта в длинном списке.
 */
class TimeSectionsTest {

    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(LocalDate.of(2026, 8, day), java.time.LocalTime.of(hour, minute))
            .atZone(kyiv).toInstant().toEpochMilli()

    @Test
    fun `вчерашний вечер не становится сегодняшним`() {
        val now = at(13, 16, 35)

        assertEquals(TimeSection.YESTERDAY, timeSectionOf(at(12, 20, 12), now, kyiv))
        assertEquals(TimeSection.YESTERDAY, timeSectionOf(at(12, 18, 7), now, kyiv))
    }

    @Test
    fun `утром вчерашнее остаётся вчерашним`() {
        val now = at(13, 9)

        assertEquals("вчерашние девять утра сочли сегодняшними", TimeSection.YESTERDAY, timeSectionOf(at(12, 9), now, kyiv))
        assertEquals(TimeSection.TODAY, timeSectionOf(at(13, 1), now, kyiv))
    }

    @Test
    fun `последний час — «сейчас», даже если это уже другой день`() {
        val now = at(13, 0, 30)

        assertEquals(TimeSection.NOW, timeSectionOf(at(12, 23, 58), now, kyiv))
    }

    @Test
    fun `позавчерашнее уходит в «раньше»`() {
        val now = at(13, 12)

        assertEquals(TimeSection.EARLIER, timeSectionOf(at(11, 23, 59), now, kyiv))
    }

    @Test
    fun `пустые секции не появляются, а порядок — от свежего к старому`() {
        val now = at(13, 12)
        val items = listOf(at(13, 11, 45), at(13, 2), at(12, 20), at(1, 10))

        val sections = byTimeSection(items, now, kyiv) { it }.map { it.first }

        assertEquals(
            listOf(TimeSection.NOW, TimeSection.TODAY, TimeSection.YESTERDAY, TimeSection.EARLIER),
            sections,
        )
    }

    /** Секция и подпись строки считают день одним кодом — разойтись им негде. */
    @Test
    fun `день секции и день подписи — один и тот же день`() {
        val moment = at(12, 20, 12)

        assertEquals(LocalDate.of(2026, 8, 12), dayOf(moment, kyiv))
    }
}
