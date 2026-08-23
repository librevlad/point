package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GeminiLlmClientTest {

    private val textObj = PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))
    private val okBody = """{"candidates":[{"content":{"parts":[{"text":"готово"}]}}]}"""

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private fun modelOf(url: String) = url.substringAfter("/models/").substringBefore(":generateContent")

    @Test
    fun `falls back to the next model when the first is rate-limited`() = runTest {
        val tried = mutableListOf<String>()
        val http = object : HttpJson {
            override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
                tried += modelOf(url)
                return if (tried.size == 1) HttpResult(429, "quota") else HttpResult(200, okBody)
            }
        }
        val res = GeminiLlmClient(http, store, "key", listOf("gemini-2.0-flash", "gemini-flash-latest")).run(textObj, "hi")
        assertEquals(listOf("gemini-2.0-flash", "gemini-flash-latest"), tried)
        assertEquals("gemini-flash-latest", res.metadata["model"])
        assertEquals("готово", File(res.uri.value).readText())
    }

    /**
     * Отказ уходит словами, а перебор моделей остаётся нашей механикой (#1236): раньше на
     * баннере стояло «Gemini недоступен — m1: Gemini HTTP 429: {…}; m2: …» — код протокола,
     * кусок чужого JSON и идентификаторы моделей, которых человек не заводил.
     */
    @Test
    fun `все модели отказали — человеку слова, а не склейка кодов и id моделей`() = runTest {
        val http = object : HttpJson {
            override suspend fun post(url: String, headers: Map<String, String>, body: String) =
                HttpResult(429, """{"error":{"code":429,"message":"Resource exhausted"}}""")
        }

        val e = runCatching {
            GeminiLlmClient(http, store, "key", listOf("gemini-flash-latest", "gemini-pro-latest")).run(textObj, "hi")
        }.exceptionOrNull()

        val said = e?.message.orEmpty()
        assertEquals(serviceRefusal(429), said)
        assertFalse(said, said.contains("gemini-"))
        assertFalse(said, said.contains("Resource exhausted"))
        assertTrue("сводка цепочки узнаёт исчерпанный предел", looksLikeQuotaFailure(said))
        assertEquals("код остаётся Point, а не человеку", 429, (e as? AiServiceRefusal)?.status)
    }

    private fun audio(mime: String, name: String? = null) = PointObject(
        "v", mime, ScratchRef("/x.bin"), ObjectState(ObjectKind.AUDIO),
        metadata = name?.let { mapOf("name" to it) } ?: emptyMap(),
    )

    @Test
    fun `запись едет вложением под каноническим типом`() {

        assertEquals("audio/ogg", geminiAttachmentMime(audio("audio/opus")))
        assertEquals("audio/ogg", geminiAttachmentMime(audio("application/ogg")))
        assertEquals("audio/mp4", geminiAttachmentMime(audio("application/octet-stream", "voice.m4a")))

        assertEquals("image/png", geminiAttachmentMime(PointObject("i", "image/png", ScratchRef("/x.png"), ObjectState(ObjectKind.IMAGE))))
    }

    @Test
    fun `«беру» и «прикладываю» — одна функция, поэтому глухой шаг отходит, а не молчит`() {

        val client = GeminiLlmClient(
            object : HttpJson {
                override suspend fun post(url: String, headers: Map<String, String>, body: String) =
                    HttpResult(200, okBody)
            },
            store, "key", listOf("m1"),
        )

        assertTrue(client.canHandle(audio("audio/ogg")))
        assertFalse("amr не читает никто — значит и не берём", client.canHandle(audio("audio/amr")))
        assertNull(geminiAttachmentMime(audio("audio/amr")))
        assertTrue("всё незвуковое как было", client.canHandle(textObj))
    }

    @Test
    fun `a blank key fails fast so the chain moves on`() = runTest {
        val http = object : HttpJson {
            override suspend fun post(url: String, headers: Map<String, String>, body: String) = HttpResult(200, okBody)
        }
        val e = runCatching { GeminiLlmClient(http, store, "", listOf("m1")).run(textObj, "hi") }.exceptionOrNull()

        // Имя ключа сборки человеку не адресовано (#1236): он не заводил ни GEMINI_API_KEY,
        // ни local.properties.
        val said = e?.message.orEmpty()
        assertTrue(said, said.contains(AI_KEY_HINT))
        assertFalse(said, said.contains("GEMINI_API_KEY"))
        assertFalse(said, said.contains("local.properties"))
    }
}
