package com.point.core.flow

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Под «Раньше» строка называет день, а не час (#1056, решение владельца 20.08.2026, вариант B).
 *
 * Живой снимок: под секцией «РАНЬШЕ» строки печатали только «14:05» — трёхдневная и
 * трёхмесячная записи были неотличимы. Правило #880 «время сказано секцией, строке остаётся
 * час» верно для «Сейчас / Сегодня / Вчера», но «Раньше» — открытый хвост: секция дня не
 * называет. Теперь день называет строка: «17 авг», чужой год — с годом: «3 мая 2025».
 */
class RowTimeLabelTest {

    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")

    private val now: Long = moment(LocalDate.of(2026, 8, 20), 12, 0)

    private fun moment(day: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(kyiv).toInstant().toEpochMilli()

    @Test
    fun `под «Раньше» — день, час убран как бесполезный`() {
        val expected = DAY_THIS_YEAR

        assertEquals(expected, rowTimeLabel(moment(LocalDate.of(2026, 8, 17), 14, 5), now, kyiv))
    }

    @Test
    fun `чужой год — с годом`() {
        val expected = DAY_OTHER_YEAR

        assertEquals(expected, rowTimeLabel(moment(LocalDate.of(2025, 5, 3), 9, 30), now, kyiv))
    }

    /** Для «Сегодня» и «Вчера» день сказан секцией — строке по-прежнему остаётся час (#880). */
    @Test
    fun `сегодня и вчера строка остаётся часом`() {
        assertEquals("09:07", rowTimeLabel(moment(LocalDate.of(2026, 8, 20), 9, 7), now, kyiv))
        assertEquals("22:41", rowTimeLabel(moment(LocalDate.of(2026, 8, 19), 22, 41), now, kyiv))
    }

    /** Граница «Раньше» и граница секции — один код: позавчерашнее уже называет день. */
    @Test
    fun `подпись переключается ровно там, где секция`() {
        val dayBeforeYesterday = moment(LocalDate.of(2026, 8, 18), 23, 59)

        assertEquals(TimeSection.EARLIER, timeSectionOf(dayBeforeYesterday, now, kyiv))
        assertEquals(DAY_ON_THE_EDGE, rowTimeLabel(dayBeforeYesterday, now, kyiv))
    }

    private companion object {

        // Ожидания — константами: точная строка здесь не цемент формулировки, а само
        // обещание #1056 — «17 авг» вместо часа и «3 мая 2025» для чужого года.
        const val DAY_THIS_YEAR = "17 авг"
        const val DAY_OTHER_YEAR = "3 мая 2025"
        const val DAY_ON_THE_EDGE = "18 авг"
    }
}
