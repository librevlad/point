package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сервис, которым Point пользуется, стоит в списке настроек — а тот, которого больше нет,
 * не стоит нигде (#799).
 *
 * Живая проверка 11.08.2026: экран говорил «Свой ключ у 1 сервиса из 9», а в цепочке моделей
 * работали двенадцать. Cloudflare Workers AI и ModelScope приехали срезами #763 и #766 и в
 * настройки не попали: в релизной сборке ключей сборки нет вовсе, а вставить свой человеку
 * было некуда — двери в списке не существовало. Список для настроек и список для цепочки —
 * две копии одного знания, и расходятся они молча.
 */
class EveryUsedProviderIsInSettingsTest {

    @Test
    fun `новые бесплатные провайдеры есть в списке настроек`() {
        listOf("cloudflare", "modelscope").forEach { id ->
            assertNotNull("$id нет в «Ключах AI»", AI_PROVIDERS.firstOrNull { it.id == id })
        }
    }

    @Test
    fun `у каждого провайдера есть, где взять ключ и что он умеет`() {
        AI_PROVIDERS.forEach { p ->
            assertTrue("${p.id}: нечего сказать про сервис", p.what.isNotBlank())
            assertTrue("${p.id}: некуда идти за ключом", p.keyUrl.startsWith("https://"))
            assertTrue("${p.id}: нет ни одной модели", p.models.isNotBlank())
        }
    }

    @Test
    fun `закрытого сервиса в списке нет`() {

        // GitHub Models закрыт (владелец 11.08.2026: «gh models закрыли»).
        assertEquals(emptyList<AiProvider>(), AI_PROVIDERS.filter { it.id == "github" })
    }

    @Test
    fun `идентификаторы не повторяются`() {
        val ids = AI_PROVIDERS.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }
}
