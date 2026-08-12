package com.point.core.flow

import com.point.core.model.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDestinationTest {

    private val pickedByPoint = listOf(
        "Mistral", "OCR.space", "OVH", "Groq", "Gemini", "Qwen", "SambaNova", "Cerebras",
        "Tesseract", "Whisper",
    )

    @Test
    fun `ссылка честно говорит, что файл будет доступен по ней и сколько`() {
        val text = cloudDestination(CapabilityId("drop-link"))
        assertTrue(text, text.contains("ссылк"))
        assertTrue("не сказано, сколько живёт: $text", text.contains("сутк"))
        assertTrue("назван чужой адресат: $text", !text.contains("AI-провайдера"))
    }

    @Test
    fun `облачное распознавание говорит про распознавание, а не про AI вообще`() {
        listOf("ocr", "ocr-cloud").forEach { id ->
            val text = cloudDestination(CapabilityId(id))
            assertTrue("«$id»: $text", text.contains("распознавания"))
            assertTrue("«$id»: показано умолчание вместо своего текста — $text", !text.contains("AI-провайдера"))
        }
    }

    @Test
    fun `каждый вопрос говорит, что уходит и что вернётся`() {
        listOf("ocr", "ocr-cloud", "ai", "translate").forEach { id ->
            val text = cloudDestination(CapabilityId(id))
            assertTrue("«$id»: не сказано, что уходит — $text", text.contains("уйдёт"))
            assertTrue("«$id»: не сказано, что вернётся — $text", text.contains("вернётся"))
        }
        val link = cloudDestination(CapabilityId("drop-link"))
        assertTrue("не сказано, что уходит: $link", link.contains("уедет"))
    }

    @Test
    fun `в вопросе нет имени сервиса, который Point выбрал сам`() {
        val ids = listOf("drop-link", "ocr", "ocr-cloud", "ai", "translate", "excel")
        val guilty = ids.flatMap { id ->
            listOf(null, "OpenRouter").flatMap { service ->
                val text = cloudDestination(CapabilityId(id), aiService = service)
                pickedByPoint.filter { text.contains(it, ignoreCase = true) }.map { "«$id»: «$it» в «$text»" }
            }
        }

        assertTrue(guilty.joinToString("\n"), guilty.isEmpty())
    }

    @Test
    fun `вопрос помещается на экран`() {
        listOf("drop-link", "ocr", "ocr-cloud", "ai", "translate").forEach { id ->
            val text = cloudDestination(CapabilityId(id), aiService = "OpenRouter")
            assertTrue("«$id»: ${text.length} знаков — «$text»", text.length <= 120)
        }
    }

    @Test
    fun `AI-ветка называет выбранный сервис по имени`() {
        val text = cloudDestination(CapabilityId("ai"), aiService = "OpenRouter")
        assertTrue("адресат не назван: $text", text.contains("OpenRouter"))
        assertTrue("имя есть, а класс адресатов остался: $text", !text.contains("AI-провайдера"))
    }

    @Test
    fun `без известного имени остаётся честное умолчание про чужой сервер`() {
        val text = cloudDestination(CapabilityId("ai"))
        assertTrue(text, text.contains("чужой сервер"))
    }

    @Test
    fun `ссылка и распознавание не начинают называть AI-сервис`() {
        listOf("drop-link", "ocr", "ocr-cloud").forEach { id ->
            val text = cloudDestination(CapabilityId(id), aiService = "OpenRouter")
            assertTrue("«$id» назвал чужого адресата: $text", !text.contains("OpenRouter"))
        }
    }

    @Test
    fun `выкладывание по ссылке — отдельное обещание, и оно не запоминается`() {
        assertEquals(CloudScope.PUBLIC_LINK, cloudScopeOf(CapabilityId("drop-link")))
        assertEquals(CloudScope.MODELS, cloudScopeOf(CapabilityId("ai")))
        assertEquals(CloudScope.MODELS, cloudScopeOf(CapabilityId("cloud-ocr")))

        assertTrue("про модели спрашивают один раз", remembersConsent(CloudScope.MODELS))
        assertTrue(
            "разрешение выложить один файл не отвечает за следующий",
            !remembersConsent(CloudScope.PUBLIC_LINK),
        )
    }

    @Test
    fun `вопрос про открытую ссылку звучит не как вопрос про облако`() {
        val open = cloudAskTitle(CloudScope.PUBLIC_LINK)
        assertTrue(open, open.contains("ссылке"))
        assertTrue("а кнопка обещает ровно то, что произойдёт", cloudAskConfirm(CloudScope.PUBLIC_LINK) == "Выложить")

        assertTrue(cloudAskTitle(CloudScope.MODELS).contains("облако"))
        assertEquals("Разрешить", cloudAskConfirm(CloudScope.MODELS))
    }
}
