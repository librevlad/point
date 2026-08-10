package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProvidersTest {

    @Test
    fun `у каждого провайдера есть куда пойти за ключом`() {
        AI_PROVIDERS.forEach { provider ->
            assertTrue(
                "${provider.name}: ссылка за ключом обязана быть настоящей",
                provider.keyUrl.startsWith("https://") && provider.keyUrl.length > 12,
            )
        }
    }

    @Test
    fun `у каждого есть адрес и модель — человеку их набирать не придётся`() {
        AI_PROVIDERS.forEach { provider ->
            assertTrue("${provider.name}: нет адреса", provider.baseUrl.startsWith("https://"))
            assertTrue("${provider.name}: нет модели", provider.models.isNotBlank())
            assertTrue("${provider.name}: нечего сказать о нём человеку", provider.what.isNotBlank())
        }
    }

    @Test
    fun `адрес ведёт в OpenAI-совместимую дверь, а не в корень домена`() {

        AI_PROVIDERS.forEach { provider ->
            val path = provider.baseUrl.removePrefix("https://").substringAfter('/', "")
            assertTrue(
                "${provider.name}: адрес «${provider.baseUrl}» — корень домена, а нужна дверь API",
                path.isNotBlank(),
            )
        }
    }

    @Test
    fun `имена не повторяются — иначе выбор не выбор`() {
        assertEquals(AI_PROVIDERS.size, AI_PROVIDERS.map { it.id }.toSet().size)
        assertEquals(AI_PROVIDERS.size, AI_PROVIDERS.map { it.baseUrl }.toSet().size)
    }

    @Test
    fun `первым идёт тот, с кого проще начать`() {

        assertEquals("openrouter", AI_PROVIDERS.first().id)
    }

    @Test
    fun `обещание бесплатности всегда с датой проверки`() {

        AI_PROVIDERS.mapNotNull { it.freeNote }.forEach { note ->
            assertTrue("«$note» — обещание без даты проверки", note.contains("проверено"))
        }
    }

    @Test
    fun `сохранённый адрес узнаётся — экран откроется на своём провайдере`() {
        val groq = AI_PROVIDERS.first { it.id == "groq" }
        assertEquals(groq, providerForBaseUrl(groq.baseUrl))
        assertEquals(groq, providerForBaseUrl(groq.baseUrl + "/"))
        assertNotNull(providerForBaseUrl(groq.baseUrl.uppercase()))
    }

    @Test
    fun `чужой адрес не выдаётся за знакомый`() {

        assertNull(providerForBaseUrl("https://мой-прокси.local/v1"))
        assertNull(providerForBaseUrl(""))
    }

    @Test
    fun `ключ Groq из настроек виден тому, кто спрашивает про Groq`() {
        val keys = UserAiKeys.NONE.with(UserAiKey(GROQ_PROVIDER_ID, " gsk-мой "))

        assertEquals("gsk-мой", keys.keyFor(GROQ_PROVIDER_ID))
    }

    @Test
    fun `ключ другого провайдера за ключ Groq не выдаётся`() {

        val keys = UserAiKeys.NONE.with(UserAiKey("openrouter", "sk-or-мой"))

        assertEquals("", keys.keyFor(GROQ_PROVIDER_ID))
        assertEquals("", UserAiKeys.NONE.keyFor(GROQ_PROVIDER_ID))
    }

    @Test
    fun `отказ, который чинится ключом, узнаётся по словам обоих поколений`() {
        assertTrue(refusalNeedsKey("Расшифровать некому: Whisper слушает по ключу Groq. $KEY_SETTINGS_CALL"))
        assertTrue(refusalNeedsKey("AI не настроен — задайте свой ключ"))

        assertTrue(refusalNeedsKey("задайте свой ключ — откройте «$SETTINGS_TITLE» на домашнем экране"))

        assertFalse(refusalNeedsKey("AI недоступен — нет подключения к интернету"))
        assertFalse(refusalNeedsKey("В записи не слышно речи"))
    }
}
