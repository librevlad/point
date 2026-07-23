package com.point.data

import com.point.core.flow.ObjectStore
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Gemini's multi-model fallback over a fake [HttpJson] — key/model list injected, so
 *  this is deterministic regardless of the build's BuildConfig keys. */
class GeminiLlmClientTest {

    private val textObj = PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))
    private val okBody = """{"candidates":[{"content":{"parts":[{"text":"готово"}]}}]}"""

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject) = error("unused")
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
        assertEquals(listOf("gemini-2.0-flash", "gemini-flash-latest"), tried) // in order, first 429'd
        assertEquals("gemini-flash-latest", res.metadata["model"])
        assertEquals("готово", File(res.uri.value).readText())
    }

    @Test
    fun `surfaces a combined error when every model fails`() = runTest {
        val http = object : HttpJson {
            override suspend fun post(url: String, headers: Map<String, String>, body: String) = HttpResult(429, "quota")
        }
        val e = runCatching {
            GeminiLlmClient(http, store, "key", listOf("m1", "m2")).run(textObj, "hi")
        }.exceptionOrNull()
        assertTrue(e?.message?.contains("Gemini недоступен") == true)
        assertTrue(e?.message?.contains("m1") == true && e?.message?.contains("m2") == true)
    }

    @Test
    fun `a blank key fails fast so the chain moves on`() = runTest {
        val http = object : HttpJson {
            override suspend fun post(url: String, headers: Map<String, String>, body: String) = HttpResult(200, okBody)
        }
        val e = runCatching { GeminiLlmClient(http, store, "", listOf("m1")).run(textObj, "hi") }.exceptionOrNull()
        assertTrue(e?.message?.contains("GEMINI_API_KEY не задан") == true)
    }
}
