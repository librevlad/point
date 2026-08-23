package com.point.core.flow

import java.io.File
import org.junit.Assert.assertEquals
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

    // ---- #1259: отказ 200-с-ошибкой переводится по признакам, а не пересказывается. ----

    @Test
    fun `непринятый ключ узнаётся и в ответе с успешным кодом`() {
        assertEquals(serviceRefusal(401), serviceRefusalInAnswer("Invalid API key"))
        assertEquals(serviceRefusal(401), serviceRefusalInAnswer("E101: You may not have a valid API key"))
    }

    @Test
    fun `великоватый файл назван размером, а не общим отказом`() {
        assertEquals(serviceRefusal(413), serviceRefusalInAnswer("File size exceeds limit"))
    }

    @Test
    fun `неопознанный отказ сервиса — своё слово, а не чужая фраза`() {
        val said = serviceRefusalInAnswer("Unable to recognize the file format 42")

        assertEquals(SERVICE_DID_NOT_READ, said)
        assertFalse(said, said.any { it in 'a'..'z' || it in 'A'..'Z' })
    }

    /**
     * Сторож шва (#1236): чужой ответ и код протокола не попадают в текст исключения, а
     * значит и на экран — `FlowViewModel` показывает `message` как есть. Раньше правило
     * держалось на ручном списке файлов, и десятый клиент в него просто не попадал.
     */
    @Test
    fun `ни один клиент не кладёт код протокола и чужой ответ в текст исключения`() {
        val repo = File("../..")
        val banned = listOf("HTTP ${'$'}", "res.body.take(", "res.body.substring(")

        val guilty = listOf("core/flow/src/main", "data/src/main", "desktop/src/main")
            .map { File(repo, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .flatMap { file ->
                val text = file.readText()
                banned.filter { it in text }.map { "${file.name}: $it" }
            }

        assertTrue("чужой ответ или код протокола в словах отказа: $guilty", guilty.isEmpty())
    }
}
