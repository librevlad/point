package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что человек читает про свой ключ (#447).
 *
 * Жалоба владельца была не про цвета: «задан он или нет — непонятно». Ответ на этот вопрос — текст,
 * и он живёт здесь, чистой функцией, а не разметкой внутри экрана. Значит, его можно проверить.
 */
class AiKeyCheckTest {

    private val config = UserAiConfig("sk-or-v1-abcdef123456", "https://openrouter.ai/api/v1", "gemma")

    @Test fun `пустое поле называется словами, а не пустотой`() {
        val status = keyStatusLine("", "OpenRouter", saved = true, check = KeyCheck.Untested)

        assertEquals("Ключа нет", status.title)
        assertTrue("человек должен узнать, чего он лишается", status.detail.contains("не работают"))
        assertEquals(KeyTone.NEUTRAL, status.tone)
    }

    @Test fun `сохранённый ключ виден хвостом и именем провайдера — но не обещает, что работает`() {
        val status = keyStatusLine(config.apiKey, "OpenRouter", saved = true, check = KeyCheck.Untested)

        assertEquals("Ключ сохранён", status.title)
        assertTrue(status.detail.contains("3456"))
        assertTrue(status.detail.contains("OpenRouter"))
        assertTrue("непроверенный ключ не имеет права выглядеть проверенным", status.detail.contains("не проверен"))
        assertFalse("середина ключа не показывается", status.detail.contains("abcdef"))
    }

    @Test fun `введённый, но не сохранённый ключ так и называется`() {
        val status = keyStatusLine(config.apiKey, "OpenRouter", saved = false, check = KeyCheck.Untested)

        assertEquals("Ключ введён, но не сохранён", status.title)
    }

    @Test fun `рабочий ключ говорит, кто ответил, чем и за сколько`() {
        val status = keyStatusLine(
            config.apiKey, "Groq", saved = true,
            check = KeyCheck.Works("llama-3.3-70b", tookMs = 1_240, checked = 0),
        )

        assertEquals("Ключ работает", status.title)
        assertEquals(KeyTone.GOOD, status.tone)
        assertTrue(status.detail.contains("Groq"))
        assertTrue(status.detail.contains("llama-3.3-70b"))
        assertTrue("время ответа — человеческим числом", status.detail.contains("1,2 с"))
    }

    @Test fun `проверенный, но не сохранённый ключ не выдаётся за сохранённый`() {
        val status = keyStatusLine(
            config.apiKey, "Groq", saved = false,
            check = KeyCheck.Works("llama", tookMs = 900, checked = 0),
        )

        assertTrue(status.detail.contains("не сохранён"))
    }

    @Test fun `отказ показывается словами провайдера, а не «что-то пошло не так»`() {
        val reason = keyRejectionReason("Groq", 401, """{"error":{"message":"Invalid API Key"}}""")
        val status = keyStatusLine(config.apiKey, "Groq", saved = true, check = KeyCheck.Rejected(reason, 0))

        assertEquals("Ключ не принят", status.title)
        assertEquals(KeyTone.BAD, status.tone)
        assertEquals(reason, status.detail)
        assertTrue("текст провайдера прикладывается как есть", status.detail.contains("Invalid API Key"))
    }

    @Test fun `лимит и опечатка — разные новости`() {
        val typo = keyRejectionReason("Groq", 401, "")
        val limit = keyRejectionReason("Groq", 429, "")

        assertTrue(typo.contains("не принял ключ (401)"))
        assertTrue("429 — не повод чинить ключ", limit.contains("Ключ дошёл"))
        assertTrue(limit.contains("лимит, а не ключ"))
        assertNotEquals(typo, limit)
    }

    @Test fun `оплата, ненайденная модель и упавший сервис названы каждый своим`() {
        assertTrue(keyRejectionReason("OpenAI", 402, "").contains("оплату"))
        assertTrue(keyRejectionReason("OpenAI", 404, "").contains("Модель и адрес"))
        assertTrue(keyRejectionReason("OpenAI", 503, "").contains("на его стороне"))
    }

    @Test fun `хвост ответа провайдера не растёт до бесконечности`() {
        val reason = keyRejectionReason("Groq", 401, "ошибка ".repeat(200))

        assertTrue("длинное тело ответа не имеет права занять экран", reason.length < 400)
    }

    @Test fun `нет сети — это отказ сети, а не приговор ключу`() {
        val reason = keyProbeFailure("Groq", "timeout")

        assertTrue(reason.contains("Не удалось достучаться"))
        assertTrue(reason.contains("timeout"))
    }

    // --- Отпечаток: ответ принадлежит тем настройкам, на которых получен ---

    @Test fun `правка ключа гасит отметку «работает»`() {
        val works = KeyCheck.Works("gemma", 800, keyFingerprint(config))

        assertEquals(works, checkFor(works, keyFingerprint(config)))
        assertEquals(
            "зелёная отметка над изменённым ключом — ложь",
            KeyCheck.Untested,
            checkFor(works, keyFingerprint(config.copy(apiKey = config.apiKey + "7"))),
        )
    }

    @Test fun `смена модели тоже гасит отметку — проверяли другую`() {
        val works = KeyCheck.Works("gemma", 800, keyFingerprint(config))

        assertEquals(KeyCheck.Untested, checkFor(works, keyFingerprint(config.copy(model = "другая"))))
    }

    @Test fun `устаревший отказ тоже снимается — человек мог уже исправить опечатку`() {
        val rejected = KeyCheck.Rejected("не принял", keyFingerprint(config))

        assertEquals(KeyCheck.Untested, checkFor(rejected, keyFingerprint(config.copy(apiKey = "sk-другой-ключ"))))
    }

    @Test fun `ожидание и неизвестность отпечатком не гасятся`() {
        assertEquals(KeyCheck.Running, checkFor(KeyCheck.Running, 42))
        assertEquals(KeyCheck.Untested, checkFor(KeyCheck.Untested, 42))
    }

    @Test fun `пробелы по краям не делают ключ другим`() {
        assertEquals(keyFingerprint(config), keyFingerprint(config.copy(apiKey = " ${config.apiKey} ")))
    }

    // --- Маска: узнать свой ключ можно, украсть — нет ---

    @Test fun `маска показывает начало и хвост, а середину закрывает`() {
        assertEquals("sk-o…3456", maskedKey("sk-or-v1-abcdef123456"))
        assertEquals("", maskedKey(""))
        assertFalse("короткий ключ показывать нечем", maskedKey("sk-123").contains("sk"))
    }

    @Test fun `неизвестный адрес не выдаётся за провайдера`() {
        val status = keyStatusLine("sk-свой-прокси-12345", provider = null, saved = true, check = KeyCheck.Untested)

        assertTrue(status.detail.contains("свой адрес"))
    }
}
