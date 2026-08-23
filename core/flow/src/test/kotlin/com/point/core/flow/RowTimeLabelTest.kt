package com.point.core.flow

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Под «Раньше» строка называет день, а не час (#1056, решение владельца 20.08.2026, вариант B).
 *
 * Живой снимок: под секцией «РАНЬШЕ» строки печатали только «14:05» — трёхдневная и
 * трёхмесячная записи были неотличимы. Правило #880 «время сказано секцией, строке остаётся
 * час» верно для «Сейчас / Сегодня / Вчера», но «Раньше» — открытый хвост: секция дня не
 * называет. Теперь день называет строка, а чужой год добавляет год.
 *
 * Проверяется отношение, а не готовая подпись. Прежде ожидания лежали точными строками в
 * companion — тот же цемент формулировки (#584), просто спрятанный от сторожа: переписать
 * «17 авг» на «17 августа» было нельзя, не тронув тест, хотя обещание карточки от этого не
 * меняется. Обещание — «назван день, часа нет, чужой год назван», и охраняется оно.
 */
class RowTimeLabelTest {

    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")

    private val now: Long = moment(LocalDate.of(2026, 8, 20), 12, 0)

    private fun moment(day: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(kyiv).toInstant().toEpochMilli()

    /** Слова подписи: день назван числом и месяцем, и оба ищутся среди слов, а не в куске строки. */
    private fun words(label: String): List<String> = label.split(' ')

    /** Час выглядит часом — по двоеточию между числами его и узнают глазами. */
    private fun namesHour(label: String) = Regex("""\d{1,2}:\d{2}""").containsMatchIn(label)

    @Test
    fun `под «Раньше» назван день, а часа нет`() {
        val day = LocalDate.of(2026, 8, 17)

        val label = rowTimeLabel(moment(day, 14, 5), now, kyiv)

        assertTrue("числа дня нет в подписи — «$label»", day.dayOfMonth.toString() in words(label))
        assertTrue("месяц не назван словом — «$label»", MONTHS[day.monthValue - 1] in words(label))
        assertFalse("час остался — «$label»", namesHour(label))
    }

    @Test
    fun `свой год не называется, чужой — называется`() {
        val thisYear = rowTimeLabel(moment(LocalDate.of(2026, 8, 17), 14, 5), now, kyiv)
        val otherYear = rowTimeLabel(moment(LocalDate.of(2025, 5, 3), 9, 30), now, kyiv)

        assertFalse("свой год назван зря — «$thisYear»", "2026" in words(thisYear))
        assertTrue("чужой год не назван — «$otherYear»", "2025" in words(otherYear))
        assertFalse("час остался у чужого года — «$otherYear»", namesHour(otherYear))
    }

    /**
     * Боль карточки дословно: под «Раньше» стояли одни часы, и трёхдневная запись выглядела
     * как трёхмесячная. Один и тот же час — подписи обязаны разойтись.
     */
    @Test
    fun `трёхдневная и трёхмесячная записи различимы`() {
        val threeDays = moment(LocalDate.of(2026, 8, 17), 14, 5)
        val threeMonths = moment(LocalDate.of(2026, 5, 17), 14, 5)

        assertEquals(TimeSection.EARLIER, timeSectionOf(threeDays, now, kyiv))
        assertEquals(TimeSection.EARLIER, timeSectionOf(threeMonths, now, kyiv))
        assertNotEquals(
            "две старые записи подписаны одинаково — их снова не различить",
            rowTimeLabel(threeDays, now, kyiv),
            rowTimeLabel(threeMonths, now, kyiv),
        )
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
        val day = LocalDate.of(2026, 8, 18)
        val dayBeforeYesterday = moment(day, 23, 59)

        assertEquals(TimeSection.EARLIER, timeSectionOf(dayBeforeYesterday, now, kyiv))

        val label = rowTimeLabel(dayBeforeYesterday, now, kyiv)
        assertTrue("день не назван на самой границе — «$label»", day.dayOfMonth.toString() in words(label))
        assertFalse("на границе осталась подпись часом — «$label»", namesHour(label))
    }
}
