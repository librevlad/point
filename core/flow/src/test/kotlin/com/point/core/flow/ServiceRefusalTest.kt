package com.point.core.flow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отказ чужого сервиса — одними словами и из одного места (#859).
 *
 * Правило в проекте было записано ещё `OpenAiCompatibleClientTest`: «отказ сервиса написан
 * словами — без кода, без слова HTTP и без чужого ответа». Выполнялся он у одного клиента
 * из девяти: остальные восемь объявляли свой `refusal(code)` и говорили человеку «ocr-space:
 * бесплатный лимит исчерпан (402) — покупать не идём, пробуем следующий».
 *
 * Сторож держит и слова, и то, что место одно: иначе десятый клиент напишет свой набор,
 * и разойдётся всё заново.
 */
class ServiceRefusalTest {

    private val codes = listOf(400, 401, 402, 403, 404, 413, 429, 500, 503)

    @Test
    fun `в отказе нет кода протокола`() {
        codes.forEach { code ->
            val said = serviceRefusal(code)
            assertFalse(said, said.contains(code.toString()))
            assertFalse(said, said.contains("HTTP", ignoreCase = true))
        }
    }

    @Test
    fun `в отказе нет нашей кухни — ни кассы, ни перебора`() {
        val ours = listOf("покупать", "платить", "пробуем следующий", "квота", "лимит")

        codes.forEach { code ->
            val said = serviceRefusal(code)
            ours.forEach { word ->
                assertFalse("$said содержит «$word»", said.contains(word, ignoreCase = true))
            }
        }
    }

    @Test
    fun `упирание в бесплатный предел узнаётся по словам, а не по скобкам с числом`() {
        listOf(402, 429).forEach { code ->
            assertTrue(serviceRefusal(code), looksLikeQuotaFailure(serviceRefusal(code)))
        }
    }

    @Test
    fun `подсказка добавляется, только когда человеку есть что сделать`() {
        assertFalse(serviceRefusal(500).contains("—"))
        assertTrue(serviceRefusal(401, KEY_SETTINGS_CALL).contains(KEY_SETTINGS_CALL))
    }

    /**
     * Сторож шва: слова живут в одном месте, а не переписываются в каждом клиенте заново.
     * Проверяются облачные читалки и расшифровка — те, кто ходит к чужому сервису за
     * содержимым объекта.
     */
    @Test
    fun `облачные читатели не заводят свой набор слов`() {
        val repo = File("../..")
        val guilty = listOf(
            "data/src/main/kotlin/com/point/data/OcrSpaceReader.kt",
            "data/src/main/kotlin/com/point/data/MistralOcrReader.kt",
            "data/src/main/kotlin/com/point/data/OvhVisionReader.kt",
            "data/src/main/kotlin/com/point/data/UnstructuredAtomRecognizer.kt",
            "data/src/main/kotlin/com/point/data/LlamaParseAtomRecognizer.kt",
            "data/src/main/kotlin/com/point/data/GroqWhisperSpeechToText.kt",
            "desktop/src/main/kotlin/com/point/desktop/OcrActions.kt",
            "desktop/src/main/kotlin/com/point/desktop/SpeechActions.kt",
        ).filter { path ->
            val file = File(repo, path)
            file.isFile && !file.readText().contains("serviceRefusal(")
        }

        assertTrue("свой набор слов на отказ: $guilty", guilty.isEmpty())
    }
}
