package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каталог провайдеров судится тестом, потому что цена ошибки здесь — человек, которому нужен ключ
 * прямо сейчас, и ссылка, ведущая в никуда.
 */
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
        // Ключ человека ходит через `OpenAiCompatibleClient`, который дописывает `/chat/completions`.
        // Голый домен давал адрес, которого нет, — и Gemini с Anthropic отказывали человеку,
        // сделавшему всё правильно (#465). Ловится это только здесь: снаружи такое выглядит как
        // «сервис не знает такой модели».
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
        // Один ключ OpenRouter открывает сразу несколько бесплатных моделей — это самый короткий
        // путь человека от «хочу попробовать» до работающего Point.
        assertEquals("openrouter", AI_PROVIDERS.first().id)
    }

    @Test
    fun `обещание бесплатности всегда с датой проверки`() {
        // Бесплатные уровни протухают быстрее, чем пишется код. Обещание без даты — ловушка.
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
        // Человек мог вписать свой прокси — подменять его выбором из списка нельзя.
        assertNull(providerForBaseUrl("https://мой-прокси.local/v1"))
        assertNull(providerForBaseUrl(""))
    }

    // --- Ключ человека доходит до того, кому он адресован (#467) ---

    @Test
    fun `ключ Groq из настроек виден тому, кто спрашивает про Groq`() {
        val groq = AI_PROVIDERS.first { it.id == GROQ_PROVIDER_ID }
        val config = UserAiConfig(" gsk-мой ", groq.baseUrl, groq.models.substringBefore(','))

        assertEquals("gsk-мой", config.keyFor(GROQ_PROVIDER_ID))
    }

    @Test
    fun `ключ другого провайдера за ключ Groq не выдаётся`() {
        // Иначе ключ OpenRouter уехал бы в Groq — то есть в чужой сервис под чужим именем, и человек
        // получил бы «401» вместо честного «нужен ключ Groq».
        val other = AI_PROVIDERS.first { it.id == "openrouter" }

        assertEquals("", UserAiConfig("sk-or-мой", other.baseUrl, "").keyFor(GROQ_PROVIDER_ID))
        assertEquals("", UserAiConfig("ключ", "https://мой-прокси.local/v1", "").keyFor(GROQ_PROVIDER_ID))
        assertEquals("", null.keyFor(GROQ_PROVIDER_ID))
    }

    @Test
    fun `отказ, который чинится ключом, узнаётся по словам обоих поколений`() {
        assertTrue(refusalNeedsKey("Расшифровать некому: Whisper слушает по ключу Groq. $KEY_SETTINGS_CALL"))
        assertTrue(refusalNeedsKey("AI не настроен — задайте свой ключ"))
        assertTrue(refusalNeedsKey("задайте свой ключ (шестерёнка на домашнем экране)"))
        // А это чинится не ключом, и утаскивать человека в настройки было бы враньём.
        assertFalse(refusalNeedsKey("AI недоступен — нет подключения к интернету"))
        assertFalse(refusalNeedsKey("В записи не слышно речи"))
    }
}
