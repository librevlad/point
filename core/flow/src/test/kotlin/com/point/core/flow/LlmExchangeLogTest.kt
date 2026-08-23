package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Журнал обменов с моделью (просьба владельца 2026-08-09): запрос и сырой ответ
 * читаются с диска после прогона, ошибки тоже след оставляют, старые записи уходят.
 */
class LlmExchangeLogTest {

    private fun obj() = PointObject(
        "doc", "text/plain", ScratchRef("/tmp/x.txt"), ObjectState(ObjectKind.TEXT),
    )

    private fun answering(text: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            val f = File.createTempFile("ans", ".txt").apply { deleteOnExit(); writeText(text) }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
        }
    }

    private fun tempDir(): File = File.createTempFile("llmlog", "").run {
        delete(); mkdirs(); deleteOnExit(); this
    }

    @Test
    fun `запрос и сырой ответ ложатся файлом`() = runTest {
        val dir = tempDir()

        LoggingLlmClient(answering("PHONE=+380671234567"), dir, enabled = true)
            .run(obj(), "Найди контакты")

        val entry = dir.listFiles()!!.single().readText()
        assertTrue(entry.contains("Найди контакты"))
        assertTrue(entry.contains("PHONE=+380671234567"))
    }

    @Test
    fun `ошибка провайдера пробрасывается и оставляет след`() = runTest {
        val dir = tempDir()
        val broken = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject =
                error("квота кончилась")
        }

        val thrown = runCatching {
            LoggingLlmClient(broken, dir, enabled = true).run(obj(), "промпт")
        }.exceptionOrNull()

        assertEquals("квота кончилась", thrown?.message)
        assertTrue(dir.listFiles()!!.single().readText().contains("квота кончилась"))
    }

    @Test
    fun `живут последние тридцать обменов, выключенный журнал молчит`() = runTest {
        val dir = tempDir()
        var tick = 0L
        val log = LoggingLlmClient(answering("ok"), dir, enabled = true, now = { tick })
        repeat(35) { tick = it.toLong(); log.run(obj(), "запрос $it") }

        assertEquals(30, dir.listFiles()!!.size)

        val silentDir = tempDir()
        LoggingLlmClient(answering("ok"), silentDir, enabled = false).run(obj(), "тихо")
        assertEquals(null, silentDir.listFiles()?.takeIf { it.isNotEmpty() })
    }

    /**
     * Чужой ответ разрезан на два канала (#1236).
     *
     * Пока тело ответа стояло в тексте исключения, оно ехало человеку на баннер — и оно же
     * было единственным, что попадало в журнал. Убрать его из слов человека было половиной
     * дела: без второго канала отладочный стенд остался бы без единого следа, чем сервис
     * ответил на 400 и 500. Проверяется на той сборке, что живёт в приложении: клиент внутри
     * цепочки, цепочка внутри журнала.
     */
    @Test
    fun `чужой ответ доезжает до журнала, а человеку остаются наши слова`() = runTest {
        val dir = tempDir()
        val answered = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}"""
        val gemini = GeminiLlmClient(
            object : HttpJson {
                override suspend fun post(url: String, headers: Map<String, String>, body: String) =
                    HttpResult(400, answered)
            },
            noStore(), apiKey = "ключ", models = listOf("m1"),
        )
        val chain = FallbackLlmClient(listOf(gemini), TestAiFacts(), NetworkAvailability { true })

        val said = runCatching {
            LoggingLlmClient(chain, dir, enabled = true).run(obj(), "пойми")
        }.exceptionOrNull()?.message.orEmpty()

        val entry = dir.listFiles()!!.single().readText()
        assertTrue("журналу нечем объяснить отказ сервиса: $entry", entry.contains("API key not valid"))
        assertFalse("чужой ответ у человека: $said", said.contains("API key not valid"))
        assertFalse("код протокола у человека: $said", said.contains("400"))
    }

    private fun noStore() = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) = error("unused")
        override suspend fun clear() = Unit
    }

    /** #1176: журнал — прозрачная стенка, список уже отвечавших едет дальше нетронутым. */
    @Test
    fun `виток проходит сквозь журнал со списком уже отвечавших`() = kotlinx.coroutines.runBlocking {
        var avoided: Set<String>? = null
        val inner = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String) =
                ResultObject(com.point.core.model.ObjectKind.TEXT, "text/plain", ScratchRef("/tmp/a.txt"))
            override suspend fun run(obj: PointObject, prompt: String, avoidServices: Set<String>): ResultObject {
                avoided = avoidServices
                return run(obj, prompt)
            }
        }

        LoggingLlmClient(inner, tempDir(), enabled = false).run(obj(), "prompt", setOf("groq"))

        org.junit.Assert.assertEquals(setOf("groq"), avoided)
    }
}
