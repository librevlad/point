package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

class AgoLabelTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `совсем недавнее — «только что»`() {
        assertEquals("только что", agoLabel(0))
        assertEquals("только что", agoLabel(30_000))
        assertEquals("только что", agoLabel(-5_000))
    }

    @Test
    fun `минуты склоняются по-русски`() {
        assertEquals("1 минуту назад", agoLabel(minute))
        assertEquals("2 минуты назад", agoLabel(2 * minute))
        assertEquals("5 минут назад", agoLabel(5 * minute))
        assertEquals("21 минуту назад", agoLabel(21 * minute))
        assertEquals("38 минут назад", agoLabel(38 * minute))
    }

    @Test
    fun `подростковые числа не ломают склонение`() {
        assertEquals("11 минут назад", agoLabel(11 * minute))
        assertEquals("12 минут назад", agoLabel(12 * minute))
        assertEquals("13 минут назад", agoLabel(13 * minute))
        assertEquals("14 минут назад", agoLabel(14 * minute))
    }

    @Test
    fun `часы склоняются по-русски`() {
        assertEquals("1 час назад", agoLabel(hour))
        assertEquals("2 часа назад", agoLabel(2 * hour))
        assertEquals("3 часа назад", agoLabel(3 * hour))
        assertEquals("5 часов назад", agoLabel(5 * hour))
        assertEquals("21 час назад", agoLabel(21 * hour))
    }

    @Test
    fun `вчера называется вчера, а не «1 день назад»`() {
        assertEquals("вчера", agoLabel(day))
        assertEquals("вчера", agoLabel(day + 3 * hour))
        assertEquals("2 дня назад", agoLabel(2 * day))
        assertEquals("6 дней назад", agoLabel(6 * day))
    }

    @Test
    fun `дальше — недели, месяцы и «давно»`() {
        assertEquals("1 неделю назад", agoLabel(7 * day))
        assertEquals("2 недели назад", agoLabel(14 * day))
        assertEquals("1 месяц назад", agoLabel(31 * day))
        assertEquals("2 месяца назад", agoLabel(70 * day))
        assertEquals("давно", agoLabel(400 * day))
    }

    @Test
    fun `в подписи нет латиницы`() {
        val spans = listOf(0L, minute, 38 * minute, 3 * hour, day, 5 * day, 20 * day, 60 * day, 400 * day)
        spans.forEach { ms ->
            val said = agoLabel(ms)
            assertEquals("латиница в «$said»", 0, said.count { it in 'a'..'z' || it in 'A'..'Z' })
        }
    }

    private val plus3 = java.time.ZoneOffset.ofHours(3)

    private fun at(month: Int, day: Int, hour: Int, minute: Int): Long =
        java.time.ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, plus3).toInstant().toEpochMilli()

    @Test
    fun `точное время пишется днём, месяцем и часами`() {
        assertEquals("4 авг 19:25", stampLabel(at(8, 4, 19, 25), plus3))
        assertEquals("31 дек 00:05", stampLabel(at(12, 31, 0, 5), plus3))
        assertEquals("1 янв 09:00", stampLabel(at(1, 1, 9, 0), plus3))
    }

    @Test
    fun `в точном времени тоже нет латиницы`() {
        (1..12).forEach { month ->
            val said = stampLabel(at(month, 15, 12, 30), plus3)
            assertEquals("латиница в «$said»", 0, said.count { it in 'a'..'z' || it in 'A'..'Z' })
        }
    }
}
