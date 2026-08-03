package com.point.core.flow

import com.point.core.model.CapabilityId
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Согласие спрашивают, называя настоящего адресата.
 *
 * Диалог говорил про «сервер AI-провайдера» одинаково для всего сетевого. На «Дать ссылку» это
 * прямая неправда: файл уезжает на релей Point и лежит по ссылке сутки. Просить доверия, называя
 * не того, кому отдаёшь файл, — худший способ его потратить.
 */
class CloudDestinationTest {

    @Test
    fun `ссылка честно говорит, что файл будет доступен по ней и сколько`() {
        val text = cloudDestination(CapabilityId("drop-link"))
        assertTrue(text, text.contains("ссылке"))
        assertTrue("не сказано, сколько живёт: $text", text.contains("суток"))
        assertTrue("назван чужой адресат: $text", !text.contains("AI-провайдера"))
    }

    @Test
    fun `облачное распознавание говорит про распознавание, а не про AI вообще`() {
        val text = cloudDestination(CapabilityId("cloud-ocr"))
        assertTrue(text, text.contains("распознавания"))
    }

    @Test
    fun `для остального остаётся честное умолчание про AI-провайдера`() {
        val text = cloudDestination(CapabilityId("ai"))
        assertTrue(text, text.contains("AI-провайдера"))
    }

    @Test
    fun `в каждом случае сказано, что без согласия ничего не уходит`() {
        listOf("drop-link", "cloud-ocr", "ai", "translate").forEach { id ->
            val text = cloudDestination(CapabilityId(id))
            assertTrue(
                "«$id»: человеку не сказали, что решение за ним — $text",
                text.contains("согласия") || text.contains("ссылке"),
            )
        }
    }
}
