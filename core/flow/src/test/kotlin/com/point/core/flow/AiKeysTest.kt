package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiKeysTest {

    private val openRouter = AI_PROVIDERS.first { it.id == "openrouter" }
    private val groq = AI_PROVIDERS.first { it.id == GROQ_PROVIDER_ID }

    @Test
    fun `ключ хранится у своего сервиса, а не один на всех`() {
        val keys = UserAiKeys.NONE
            .with(UserAiKey(openRouter.id, "sk-or-мой"))
            .with(UserAiKey(groq.id, "gsk-мой"))

        assertEquals("sk-or-мой", keys.keyFor(openRouter.id))
        assertEquals("gsk-мой", keys.keyFor(groq.id))
        assertEquals("", keys.keyFor("gemini"))
    }

    @Test
    fun `второй ключ того же сервиса заменяет первый, а не ложится рядом`() {
        val keys = UserAiKeys.NONE
            .with(UserAiKey(groq.id, "gsk-старый"))
            .with(UserAiKey(groq.id, "gsk-новый"))

        assertEquals(1, keys.entries.size)
        assertEquals("gsk-новый", keys.keyFor(groq.id))
    }

    @Test
    fun `пустой ключ ничего не задаёт — строка остаётся без ключа`() {
        val keys = UserAiKeys.NONE.with(UserAiKey(groq.id, "   "))

        assertEquals("", keys.keyFor(groq.id))
        assertTrue(keys.mine.isEmpty())
    }

    @Test
    fun `удалённый ключ уходит только у своего сервиса`() {
        val keys = UserAiKeys.NONE
            .with(UserAiKey(openRouter.id, "sk-or-мой"))
            .with(UserAiKey(groq.id, "gsk-мой"))
            .without(groq.id)

        assertEquals("", keys.keyFor(groq.id))
        assertEquals("sk-or-мой", keys.keyFor(openRouter.id))
    }

    @Test
    fun `свои ключи идут в том порядке, в каком Point обращается`() {
        val keys = UserAiKeys.NONE
            .with(UserAiKey("gemini", "g-мой"))
            .with(UserAiKey(openRouter.id, "sk-or-мой"))
            .with(UserAiKey(OWN_SERVICE_ID, "свой", baseUrl = "https://мой.прокси/v1"))

        assertEquals(
            listOf(openRouter.id, "gemini", OWN_SERVICE_ID),
            keys.mine.map { it.providerId },
        )
    }

    @Test
    fun `единственный старый ключ переезжает к своему сервису`() {
        val old = UserAiConfig("gsk-мой", groq.baseUrl, "llama-3.3-70b-versatile", savedAt = 777)

        val moved = keysFromSingleKey(old)

        assertEquals("gsk-мой", moved.keyFor(GROQ_PROVIDER_ID))
        assertEquals("llama-3.3-70b-versatile", moved.of(GROQ_PROVIDER_ID)?.model)
        assertEquals(777L, moved.of(GROQ_PROVIDER_ID)?.savedAt)
    }

    @Test
    fun `старый ключ на своём адресе не теряется — адрес переезжает вместе с ним`() {
        val old = UserAiConfig("ключ", "https://мой.прокси/v1", "моя-модель")

        val moved = keysFromSingleKey(old)

        assertEquals("ключ", moved.keyFor(OWN_SERVICE_ID))
        assertEquals("https://мой.прокси/v1", moved.of(OWN_SERVICE_ID)?.baseUrl)
    }

    @Test
    fun `когда старого ключа не было, переносить нечего`() {
        assertEquals(UserAiKeys.NONE, keysFromSingleKey(null))
        assertEquals(UserAiKeys.NONE, keysFromSingleKey(UserAiConfig("  ", groq.baseUrl, "м")))
    }

    @Test
    fun `обращение к сервису берёт адрес и модель из списка, если человек их не трогал`() {
        val call = aiCall(UserAiKey(groq.id, "gsk-мой"))

        assertEquals(groq.baseUrl, call.baseUrl)
        assertEquals(groq.models.substringBefore(','), call.model)
        assertEquals("gsk-мой", call.apiKey)
    }

    @Test
    fun `свои адрес и модель сильнее списка`() {
        val call = aiCall(UserAiKey(groq.id, "gsk-мой", model = "моя", baseUrl = "https://мой.прокси/v1"))

        assertEquals("https://мой.прокси/v1", call.baseUrl)
        assertEquals("моя", call.model)
    }

    @Test
    fun `без ключа обращаться не к чему`() {
        assertNull(UserAiKeys.NONE.callFor(groq.id))
    }

    @Test
    fun `записанные ключи читаются обратно теми же`() {
        val keys = UserAiKeys.NONE
            .with(UserAiKey(openRouter.id, "sk-or-мой", model = "gemma", savedAt = 12))
            .with(UserAiKey(OWN_SERVICE_ID, "ключ", model = "м", baseUrl = "https://мой.прокси/v1", savedAt = 34))

        assertEquals(keys, decodeUserAiKeys(encodeUserAiKeys(keys)))
    }

    @Test
    fun `пустая и битая запись читаются как «ключей нет», а не падают`() {
        assertEquals(UserAiKeys.NONE, decodeUserAiKeys(null))
        assertEquals(UserAiKeys.NONE, decodeUserAiKeys(""))
        assertEquals(UserAiKeys.NONE, decodeUserAiKeys("мусор без разделителей"))
    }
}
