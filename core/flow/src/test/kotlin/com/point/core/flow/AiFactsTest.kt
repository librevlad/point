package com.point.core.flow

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFactsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Kyiv")

    /** 10 августа 2026, 23:26 по Киеву. */
    private val night = java.time.ZonedDateTime.of(2026, 8, 10, 23, 26, 0, 0, zone)
        .toInstant().toEpochMilli()

    /** 10 августа 2026, 09:00 по Киеву — тот же день, что и [night]. */
    private val morning = java.time.ZonedDateTime.of(2026, 8, 10, 9, 0, 0, 0, zone)
        .toInstant().toEpochMilli()

    private fun at(hours: Long): Long = night + hours * 60 * 60_000L

    @Test
    fun `исход обращения читается по ответу сервиса`() {
        assertEquals(AiOutcome.ANSWERED, aiOutcomeOfStatus(200))
        assertEquals(AiOutcome.BAD_KEY, aiOutcomeOfStatus(401))
        assertEquals(AiOutcome.BAD_KEY, aiOutcomeOfStatus(403))
        assertEquals(AiOutcome.LIMIT, aiOutcomeOfStatus(402))
        assertEquals(AiOutcome.LIMIT, aiOutcomeOfStatus(429))
        assertEquals(AiOutcome.SILENT, aiOutcomeOfStatus(500))
        assertEquals(AiOutcome.SILENT, aiOutcomeOfStatus(null))
    }

    @Test
    fun `сорвавшееся обращение читается по словам отказа`() {
        assertEquals(AiOutcome.LIMIT, aiOutcomeOfFailure("Groq — $FREE_LIMIT_SPENT, вернитесь позже"))
        assertEquals(AiOutcome.LIMIT, aiOutcomeOfFailure("Gemini HTTP 429 — слишком часто"))
        assertEquals(AiOutcome.BAD_KEY, aiOutcomeOfFailure("Mistral — $KEY_NOT_ACCEPTED"))
        assertEquals(AiOutcome.BAD_KEY, aiOutcomeOfFailure("Gemini HTTP 401"))
        assertEquals(AiOutcome.SILENT, aiOutcomeOfFailure("нет подключения к интернету"))
    }

    @Test
    fun `свежий ответ называется минутами, а не часами`() {
        assertEquals(
            "ответил 3 минуты назад",
            aiFactLine(AiFact(AiOutcome.ANSWERED, at(0)), now = at(0) + 3 * 60_000, zone = zone),
        )
        assertEquals(
            "ответил только что",
            aiFactLine(AiFact(AiOutcome.ANSWERED, at(0)), now = at(0) + 5_000, zone = zone),
        )
    }

    @Test
    fun `сегодняшний исход называется часом, когда это было`() {
        assertEquals(
            "лимит исчерпан в 09:00",
            aiFactLine(AiFact(AiOutcome.LIMIT, morning), now = night, zone = zone),
        )
    }

    @Test
    fun `вчерашний исход так и называется — вчера`() {
        val line = aiFactLine(AiFact(AiOutcome.BAD_KEY, night), now = night + 12 * 60 * 60_000, zone = zone)

        assertEquals("ключ не подошёл вчера в 23:26", line)
    }

    @Test
    fun `давний исход называется днём и месяцем`() {
        val line = aiFactLine(AiFact(AiOutcome.SILENT, night), now = night + 5 * 24 * 60 * 60_000L, zone = zone)

        assertEquals("не отвечал 10 августа в 23:26", line)
    }

    @Test
    fun `не исследованное не выдаётся за отказ`() {
        assertEquals("ещё не обращались", aiFactLine(null, now = night, zone = zone))
    }

    @Test
    fun `сведения о проверке названы вместе со своим возрастом`() {
        val facts = mapOf("groq" to AiFact(AiOutcome.ANSWERED, night))

        assertEquals(
            "Проверено вчера в 23:26",
            aiCheckedLine(facts, now = night + 12 * 60 * 60_000, zone = zone),
        )
        assertEquals("Ещё не проверяли", aiCheckedLine(emptyMap(), now = night, zone = zone))
    }

    @Test
    fun `в списке стоят все известные сервисы, а не только те, где есть ключ`() {
        val lines = aiServiceLines(UserAiKeys.NONE, builtIn = emptySet(), facts = emptyMap(), now = night, zone = zone)

        assertEquals(AI_PROVIDERS.map { it.id }, lines.map { it.providerId })
        assertEquals(AI_PROVIDERS.map { it.name }, lines.map { it.name })
    }

    @Test
    fun `строка говорит, что сервис умеет`() {
        val lines = aiServiceLines(UserAiKeys.NONE, emptySet(), emptyMap(), night, zone)

        assertTrue(lines.all { it.what.isNotBlank() })
        assertEquals(AI_PROVIDERS.first().what, lines.first().what)
    }

    @Test
    fun `свой ключ виден человеку концом, а не целиком`() {
        val keys = UserAiKeys.NONE.with(UserAiKey("groq", "gsk-0123456789abcdef"))

        val groq = aiServiceLines(keys, emptySet(), emptyMap(), night, zone).first { it.providerId == "groq" }

        assertEquals("ваш ключ ${maskedKey("gsk-0123456789abcdef")}", groq.keyLine)
        assertTrue("середина ключа попала на экран", !groq.keyLine.contains("456789"))
        assertTrue(groq.mine)
        assertTrue(groq.ready)
    }

    @Test
    fun `наличие ключа не обещает, что сервис работает`() {
        val keys = UserAiKeys.NONE.with(UserAiKey("groq", "gsk-0123456789abcdef"))

        val groq = aiServiceLines(keys, emptySet(), emptyMap(), night, zone).first { it.providerId == "groq" }

        assertTrue("строка ключа обещает работу, которой никто не проверял", !groq.keyLine.contains("работа"))
        assertEquals("ещё не обращались", groq.factLine)
    }

    @Test
    fun `сервис на ключе Point так и называется — свой ключ не требуется`() {
        val line = aiServiceLines(UserAiKeys.NONE, setOf("groq"), emptyMap(), night, zone)
            .first { it.providerId == "groq" }

        assertEquals("работает на ключе Point", line.keyLine)
        assertTrue(line.ready)
        assertTrue(!line.mine)
    }

    @Test
    fun `сервис без единого ключа честно говорит, что молчит`() {
        val line = aiServiceLines(UserAiKeys.NONE, emptySet(), emptyMap(), night, zone)
            .first { it.providerId == "groq" }

        assertEquals("ключа нет — этот сервис молчит", line.keyLine)
        assertTrue(!line.ready)
    }

    @Test
    fun `последний факт стоит в строке своего сервиса`() {
        val facts = mapOf("groq" to AiFact(AiOutcome.LIMIT, night))

        val lines = aiServiceLines(UserAiKeys.NONE, setOf("groq"), facts, night + 30_000, zone)

        assertEquals("лимит исчерпан только что", lines.first { it.providerId == "groq" }.factLine)
        assertEquals("ещё не обращались", lines.first { it.providerId == "openrouter" }.factLine)
    }

    @Test
    fun `свой адрес человека тоже стоит в списке — иначе его ключ пропал бы`() {
        val keys = UserAiKeys.NONE.with(UserAiKey(OWN_SERVICE_ID, "ключ", baseUrl = "https://мой.прокси/v1"))

        val lines = aiServiceLines(keys, emptySet(), emptyMap(), night, zone)

        assertEquals(AI_PROVIDERS.size + 1, lines.size)
        assertEquals(OWN_SERVICE_ID, lines.last().providerId)
        assertEquals(OWN_SERVICE_NAME, lines.last().name)
    }

    @Test
    fun `записанные исходы читаются обратно теми же`() {
        val facts = mapOf(
            "groq" to AiFact(AiOutcome.LIMIT, night),
            "openrouter" to AiFact(AiOutcome.ANSWERED, night - 1000),
        )

        assertEquals(facts, decodeAiFacts(encodeAiFacts(facts)))
    }

    @Test
    fun `пустая и битая запись исходов читается как «ещё не обращались»`() {
        assertEquals(emptyMap<String, AiFact>(), decodeAiFacts(null))
        assertEquals(emptyMap<String, AiFact>(), decodeAiFacts("мусор"))
    }

    @Test
    fun `сводка настроек считает свои ключи, а не выдумывает проценты`() {
        assertEquals("Своих ключей пока нет — Point работает на своих", aiKeysSummary(UserAiKeys.NONE))
        assertEquals(
            "Свой ключ у 2 сервисов из ${AI_PROVIDERS.size}",
            aiKeysSummary(UserAiKeys.NONE.with(UserAiKey("groq", "g")).with(UserAiKey("openrouter", "o"))),
        )
        assertEquals(
            "Свой ключ у 1 сервиса из ${AI_PROVIDERS.size}",
            aiKeysSummary(UserAiKeys.NONE.with(UserAiKey("groq", "g"))),
        )
    }
}
