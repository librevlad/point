package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Что телефон и компьютер говорят человеку про связь (#412).
 *
 * Правило одно на обе стороны намеренно: если телефон считает связь живой, а компьютер —
 * потерянной, спорить будут они, а виноватым окажется человек.
 */
class LinkStateTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun `не связывались ни разу — так и говорим`() {
        val state = linkStateOf(lastContactAt = null, path = null, now = now)
        assertEquals(LinkState.Never, state)
        assertEquals("ещё не связывались", linkLabel(state))
    }

    @Test
    fun `только что слышали по локальной сети`() {
        val state = linkStateOf(now - 5_000, LinkPath.LAN, now)
        assertEquals(LinkState.Live(LinkPath.LAN, 5_000), state)
        assertEquals("на связи · в этой сети", linkLabel(state))
    }

    @Test
    fun `слышали через релей — человеку это «через интернет»`() {
        // Путь важен не для красоты: он объясняет, почему вдали от дома медленнее.
        assertEquals("на связи · через интернет", linkLabel(linkStateOf(now - minute, LinkPath.RELAY, now)))
    }

    @Test
    fun `долгое молчание названо, а не спрятано`() {
        val state = linkStateOf(now - 10 * minute, LinkPath.LAN, now)
        assertEquals(LinkState.Silent(10 * minute), state)
        assertEquals("не отвечает · молчит 10 минут", linkLabel(state))
    }

    @Test
    fun `на самой границе молчания связь уже потеряна`() {
        // Граница включена в молчание: обещать связь на пороге — то же «наверное, всё хорошо».
        val state = linkStateOf(now - LINK_SILENCE_AFTER_MS, LinkPath.LAN, now)
        assertEquals(LinkState.Silent(LINK_SILENCE_AFTER_MS), state)
    }

    @Test
    fun `контакт есть, а пути нет — показываем молчание, а не выдуманный путь`() {
        val state = linkStateOf(now - 1_000, path = null, now = now)
        assertEquals(LinkState.Silent(1_000), state)
    }

    @Test
    fun `часы вместо ста минут — строка остаётся человеческой`() {
        assertEquals("не отвечает · молчит 2 часа", linkLabel(linkStateOf(now - 125 * minute, LinkPath.LAN, now)))
    }

    @Test
    fun `русский счёт минут, а не машинный вывод`() {
        assertEquals("не отвечает · молчит 5 минут", linkLabel(linkStateOf(now - 5 * minute, LinkPath.LAN, now)))
        assertEquals("не отвечает · молчит 21 минуту", linkLabel(linkStateOf(now - 21 * minute, LinkPath.LAN, now)))
        assertEquals("не отвечает · молчит 13 минут", linkLabel(linkStateOf(now - 13 * minute, LinkPath.LAN, now)))
    }

    @Test
    fun `часы у компьютера могли уйти вперёд — отрицательного времени не показываем`() {
        val state = linkStateOf(now + 10_000, LinkPath.LAN, now)
        assertEquals(LinkState.Live(LinkPath.LAN, 0), state)
    }
}
