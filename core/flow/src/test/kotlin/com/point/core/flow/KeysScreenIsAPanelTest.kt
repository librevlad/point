package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Экран ключей — панель состояния, а не документация (#902).
 *
 * Разбор 13.08.2026: 8/10, SHIP, но главная правка названа прямо — «сделать первый экран
 * ключей не документацией, а приборной панелью состояния». Наверху висел абзац в семь строк,
 * девять строк группы несли одно лишь имя сервиса, а очередь — главное, что надо понять про
 * этот экран, — не была выражена ничем.
 */
class KeysScreenIsAPanelTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `наверху две мысли, а не мини-документация`() {
        assertTrue("очередь не названа", AI_CHAIN_WHAT.contains("по очереди"))
        assertTrue("не сказано, что ключ не обязателен", AI_CHAIN_WHAT.contains("не обязателен"))
        assertTrue("вступление снова разрослось: ${AI_CHAIN_WHAT.length}", AI_CHAIN_WHAT.length < 200)
    }

    @Test
    fun `подробное объяснение ждёт за отдельным нажатием`() {
        assertTrue("не сказано, кому нужна модель", AI_CHAIN_MORE.contains("Понять"))
        assertTrue("механика проверки не объяснена", AI_CHAIN_MORE.contains("по вашему нажатию"))
    }

    @Test
    fun `место в очереди — настоящее место обращения, а не номер внутри группы`() {
        assertEquals(1, aiChainPlace(AI_PROVIDERS.first().id))
        assertEquals(AI_PROVIDERS.size, aiChainPlace(AI_PROVIDERS.last().id))
        assertTrue("незнакомый сервис встаёт в конец", aiChainPlace("никто") > AI_PROVIDERS.size)
    }

    @Test
    fun `про ожидаемое строка молчит, про беду говорит`() {
        val answered = AiFact(AiOutcome.ANSWERED, now - 60_000)
        val limit = AiFact(AiOutcome.LIMIT, now - 60_000)

        assertNull("«ответил» — не новость, о нём молчат", aiTroubleLine(answered, now))
        assertTrue("исчерпанный лимит должен быть виден", aiTroubleLine(limit, now) != null)
        assertNull("к сервису не обращались — говорить нечего", aiTroubleLine(null, now))
    }

    @Test
    fun `группа зовётся тем, что человеку делать, а не тем, как ведёт себя сервис`() {
        val title = AiServiceGroup.SILENT.title

        assertTrue("группа снова про поведение сервиса: $title", !title.contains("олчат"))
        assertTrue("не сказано, что нужно от человека: $title", title.contains("ключ"))
    }
}
