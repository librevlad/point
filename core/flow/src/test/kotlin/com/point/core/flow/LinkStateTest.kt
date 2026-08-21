package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkStateTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    /** Счёт молчания из подписи — число и мера, без фразы вокруг них. */
    private fun countAfter(minutes: Long): String {
        val label = linkLabel(linkStateOf(now - minutes * minute, now))
        return Regex("""\d+ [а-яё]+""").find(label)?.value ?: label
    }

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

        assertEquals("на связи", linkLabel(linkStateOf(now - minute, now)))
    }

    @Test
    fun `давно не слышали — факт «был на связи N назад», а не диагноз (#1114)`() {
        val state = linkStateOf(now - 10 * minute, now)
        assertEquals(LinkState.Silent(10 * minute), state)

        // Живой прогон 18.08.2026: «Телефон · не отвечает · молчит 2 часа» читалось как поломка,
        // хотя телефон просто лежал с погашенным экраном. Подпись говорит, когда его слышали.
        val label = linkLabel(state)
        assertTrue(label, label.startsWith("был на связи") && label.endsWith("назад"))
        assertTrue(label, "10 минут" in label)
    }

    @Test
    fun `ни одна подпись связи не ставит диагноз и не говорит метафорами (#1114)`() {
        val labels = listOf(
            linkStateOf(null, now),
            linkStateOf(now - 5_000, now),
            linkStateOf(now - 125 * minute, now),
            linkStateOf(null, now, probing = true),
            linkStateOf(null, now, knownButUnheard = true),
        ).map(::linkLabel)

        labels.forEach { label ->
            assertFalse(label, listOf("не отвечает", "молчит", "нет ответа").any { it in label })
        }
    }

    @Test
    fun `на самой границе молчания связь уже потеряна`() {

        val state = linkStateOf(now - LINK_SILENCE_AFTER_MS, now)
        assertEquals(LinkState.Silent(LINK_SILENCE_AFTER_MS), state)
    }

    @Test
    fun `часы вместо ста минут — строка остаётся человеческой`() {
        val minutes = 125L

        // Проверяется счёт, а не фраза вокруг него (#1114): мера — часы, число из них же.
        assertEquals("${minutes / 60} часа", countAfter(minutes))
    }

    @Test
    fun `русский счёт минут, а не машинный вывод`() {
        // Мера согласована с числом — это здесь и проверяется. Слова подписи вокруг счёта
        // вправе меняться, не роняя тест (#1114).
        val agreed = mapOf(5L to "минут", 21L to "минуту", 13L to "минут")

        agreed.forEach { (minutes, word) -> assertEquals("$minutes $word", countAfter(minutes)) }
    }

    @Test
    fun `часы у компьютера могли уйти вперёд — отрицательного времени не показываем`() {
        val state = linkStateOf(now + 10_000, now)
        assertEquals(LinkState.Live(0), state)
    }

    @Test
    fun `запрос в пути, а прошлого нет — «проверяю», а не «ещё не связывались»`() {

        val state = linkStateOf(lastContactAt = null, now = now, probing = true)
        assertEquals(LinkState.Checking, state)
        assertEquals("проверяю связь…", linkLabel(state))
    }

    @Test
    fun `запрос в пути поверх старого молчания — тоже «проверяю»`() {

        assertEquals(LinkState.Checking, linkStateOf(now - 120 * minute, now, probing = true))
    }

    @Test
    fun `свежий контакт запрос не гасит — мигать вместо ответа незачем`() {

        assertEquals(LinkState.Live(5_000), linkStateOf(now - 5_000, now, probing = true))
    }

    @Test
    fun `запроса нет — «ещё не связывались» остаётся честным ответом`() {

        assertEquals(LinkState.Never, linkStateOf(lastContactAt = null, now = now, probing = false))
    }

    @Test
    fun `телефон знаком, но эта сессия его ещё не слышала — «ждёт связи», а не молчание с 1970 года`() {

        // Живой прогон 2026-08-09: свежий процесс ПК показывал «не отвечает · молчит 496177 часов».
        val state = linkStateOf(lastContactAt = null, now = now, knownButUnheard = true)
        assertEquals(LinkState.Waiting, state)
        assertEquals("ждёт связи", linkLabel(state))
    }

    @Test
    fun `знакомый телефон уже слышали — «ждёт связи» уступает реальному времени`() {

        assertEquals(LinkState.Live(5_000), linkStateOf(now - 5_000, now, knownButUnheard = true))
        assertEquals(
            LinkState.Silent(10 * minute),
            linkStateOf(now - 10 * minute, now, knownButUnheard = true),
        )
    }

    @Test
    fun `запрос в пути поверх «ждёт связи» — «проверяю»`() {

        assertEquals(
            LinkState.Checking,
            linkStateOf(lastContactAt = null, now = now, probing = true, knownButUnheard = true),
        )
    }
}
