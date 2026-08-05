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
        val state = linkStateOf(lastContactAt = null, now = now)
        assertEquals(LinkState.Never, state)
        assertEquals("ещё не связывались", linkLabel(state))
    }

    @Test
    fun `только что слышали — так и говорим, без разговора о дороге`() {
        val state = linkStateOf(now - 5_000, now)
        assertEquals(LinkState.Live(5_000), state)
        assertEquals("на связи", linkLabel(state))
    }

    @Test
    fun `дорога у связи одна, и человеку про неё сказать нечего`() {
        // Раньше здесь различались «в этой сети» и «через интернет»: слово объясняло скорость.
        // Выбора больше нет (#475), и лишнее слово только просило бы человека о чём-то подумать.
        assertEquals("на связи", linkLabel(linkStateOf(now - minute, now)))
    }

    @Test
    fun `долгое молчание названо, а не спрятано`() {
        val state = linkStateOf(now - 10 * minute, now)
        assertEquals(LinkState.Silent(10 * minute), state)
        assertEquals("не отвечает · молчит 10 минут", linkLabel(state))
    }

    @Test
    fun `на самой границе молчания связь уже потеряна`() {
        // Граница включена в молчание: обещать связь на пороге — то же «наверное, всё хорошо».
        val state = linkStateOf(now - LINK_SILENCE_AFTER_MS, now)
        assertEquals(LinkState.Silent(LINK_SILENCE_AFTER_MS), state)
    }

    @Test
    fun `часы вместо ста минут — строка остаётся человеческой`() {
        assertEquals("не отвечает · молчит 2 часа", linkLabel(linkStateOf(now - 125 * minute, now)))
    }

    @Test
    fun `русский счёт минут, а не машинный вывод`() {
        assertEquals("не отвечает · молчит 5 минут", linkLabel(linkStateOf(now - 5 * minute, now)))
        assertEquals("не отвечает · молчит 21 минуту", linkLabel(linkStateOf(now - 21 * minute, now)))
        assertEquals("не отвечает · молчит 13 минут", linkLabel(linkStateOf(now - 13 * minute, now)))
    }

    @Test
    fun `часы у компьютера могли уйти вперёд — отрицательного времени не показываем`() {
        val state = linkStateOf(now + 10_000, now)
        assertEquals(LinkState.Live(0), state)
    }

    // --- Пока запрос в пути (#451) ---

    @Test
    fun `запрос в пути, а прошлого нет — «проверяю», а не «ещё не связывались»`() {
        // Тот самый экран из #451: связывались вчера, память о контакте не пережила перезапуск.
        // «Ещё не связывались» — утверждение о прошлом, которого не было.
        val state = linkStateOf(lastContactAt = null, now = now, probing = true)
        assertEquals(LinkState.Checking, state)
        assertEquals("проверяю связь…", linkLabel(state))
    }

    @Test
    fun `запрос в пути поверх старого молчания — тоже «проверяю»`() {
        // Приговор «молчит два часа» вынесен ДО того, как компьютер успел ответить.
        assertEquals(LinkState.Checking, linkStateOf(now - 120 * minute, now, probing = true))
    }

    @Test
    fun `свежий контакт запрос не гасит — мигать вместо ответа незачем`() {
        // Ответ у человека уже есть, и он верный: гасить «на связи» на секунду проверки значит
        // мигать вместо того, чтобы сообщать.
        assertEquals(LinkState.Live(5_000), linkStateOf(now - 5_000, now, probing = true))
    }

    @Test
    fun `запроса нет — «ещё не связывались» остаётся честным ответом`() {
        // Состояние никуда не делось: оно правдиво ровно тогда, когда правда никого не спрашивали.
        assertEquals(LinkState.Never, linkStateOf(lastContactAt = null, now = now, probing = false))
    }
}
