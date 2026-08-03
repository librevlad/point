package com.point.core.flow

import org.junit.Assert.assertEquals
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
}
