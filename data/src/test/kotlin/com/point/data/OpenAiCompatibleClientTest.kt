package com.point.data

import com.point.core.flow.ObjectStore
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException

/** The OpenAI-compatible provider over a fake [HttpJson] — response parsing, errors,
 *  timeouts. Pure JVM: a TEXT object avoids android.util.Base64. */
class OpenAiCompatibleClientTest {

    private val provider = OpenAiProvider("openrouter", "https://x/v1", "sk-key", "some-model")
    private val textObj = PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))
    private val okBody = """{"choices":[{"message":{"content":"привет"}}]}"""

    private fun http(code: Int, body: String, capture: ((String) -> Unit)? = null) = object : HttpJson {
        override suspend fun post(url: String, headers: Map<String, String>, body2: String): HttpResult {
            capture?.invoke(body2)
            return HttpResult(code, body)
        }
    }

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private fun client(http: HttpJson) = OpenAiCompatibleClient(http, store, provider)

    @Test
    fun `parses the assistant content on 200 and tags the source`() = runTest {
        val res = client(http(200, okBody)).run(textObj, "hi")
        assertEquals("openrouter", res.metadata["source"])
        assertEquals("some-model", res.metadata["model"])
        assertEquals("привет", File(res.uri.value).readText())
    }

    @Test
    fun `maps a non-2xx to an error carrying the code and provider`() = runTest {
        val e = runCatching { client(http(429, "rate limit")).run(textObj, "hi") }.exceptionOrNull()
        assertTrue(e?.message?.contains("openrouter HTTP 429") == true)
    }

    @Test
    fun `errors on empty choices`() = runTest {
        val e = runCatching { client(http(200, """{"choices":[]}""")).run(textObj, "hi") }.exceptionOrNull()
        assertTrue(e?.message?.contains("пустой ответ") == true)
    }

    @Test
    fun `errors on malformed json`() = runTest {
        val e = runCatching { client(http(200, "<html>oops")).run(textObj, "hi") }.exceptionOrNull()
        assertNotNull(e)
    }

    @Test
    fun `propagates a transport timeout so the fallback chain can continue`() = runTest {
        val timeout = object : HttpJson {
            override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult =
                throw SocketTimeoutException("read timed out")
        }
        val e = runCatching { client(timeout).run(textObj, "hi") }.exceptionOrNull()
        assertTrue(e is SocketTimeoutException)
    }

    @Test
    fun `sends the model and prompt in the request body`() = runTest {
        var sent = ""
        client(http(200, okBody) { sent = it }).run(textObj, "переведи это")
        assertTrue(sent.contains("some-model"))
        assertTrue(sent.contains("переведи это"))
    }

    // --- Vision routing (#60) ---

    private val image = PointObject("i", "image/png", ScratchRef("/x.png"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `a text-only model cannot handle an image but still handles text`() {
        val textOnly = client(http(200, okBody)) // provider model "some-model" → not vision
        assertFalse(textOnly.canHandle(image))
        assertTrue(textOnly.canHandle(textObj))
    }

    @Test
    fun `a vision model can handle an image`() {
        val vision = OpenAiCompatibleClient(http(200, okBody), store, provider.copy(vision = true))
        assertTrue(vision.canHandle(image))
    }

    @Test
    fun `openAiModels infers vision from the model name`() {
        val models = openAiModels("openrouter", "https://x/v1", "k", "google/gemma-4-31b-it:free, llama-3.1-8b-instant")
        assertTrue(models.first { it.model.contains("gemma") }.vision)
        assertFalse(models.first { it.model.contains("llama-3.1") }.vision)
    }

    // --- Сильное зрение объявляется по замеру, а не по названию (#490/#493) ---

    @Test
    fun `замеренные читатели страницы объявлены сильными — цепочка на снимке ведёт ими`() {
        // До правки `strongVision` стоял ровно у трёх клиентов, признанных сильными на глаз, и
        // бесплатная цепочка на снимке шла в произвольном порядке. Файл, собранный из плохого
        // чтения, и был жалобой владельца (#493).
        listOf(
            "google/gemma-4-26b-a4b-it:free", // 15/15, шесть ответов из шести
            "gemma-4-31B-it", // 14–15/15, шесть из шести
            "qwen/qwen3.6-27b", // 15/15 на мятом фото
            "Qwen2.5-VL-72B-Instruct", // 15/15 шесть из шести
        ).forEach { assertTrue(it, isMeasuredStrongVision(it)) }
    }

    @Test
    fun `тот, кто читает хуже специальной ручки, сильным не объявлен`() {
        // Чат Mistral берёт 12–13/15 там, где его же OCR-ручка берёт 15/15. Объявить чат сильным
        // значило бы поставить его вровень с тем, кто читает лучше.
        listOf("mistral-medium-latest", "mistral-small-latest", "glm-4.6v-flash", "llama-3.3-70b-versatile")
            .forEach { assertFalse(it, isMeasuredStrongVision(it)) }
    }

    @Test
    fun `объявление доходит до клиента — иначе цепочка о нём не узнает`() {
        val strong = openAiModels("cerebras", "https://x/v1", "k", "gemma-4-31b").single()
        assertTrue(strong.strongVision)
        assertTrue(OpenAiCompatibleClient(http(200, okBody), store, strong).strongVision)

        val plain = openAiModels("groq", "https://x/v1", "k", "llama-3.1-8b-instant").single()
        assertFalse(plain.strongVision)
        assertFalse(OpenAiCompatibleClient(http(200, okBody), store, plain).strongVision)
    }

    @Test
    fun `сильное зрение не заменяет зрения — оно про качество, а не про приём картинки`() {
        // Замеренный сильный обязан быть и зрячим, иначе `canHandle` не пустит его к снимку и
        // объявление окажется украшением.
        openAiModels("x", "https://x/v1", "k", "gemma-4-31b,qwen/qwen3.6-27b,Qwen2.5-VL-72B-Instruct")
            .forEach { assertTrue(it.model, !it.strongVision || it.vision) }
    }

    @Test
    fun `a NO_IMAGE marker reply is treated as failure so the chain continues`() = runTest {
        val body = """{"choices":[{"message":{"content":"NO_IMAGE"}}]}"""
        val vision = OpenAiCompatibleClient(http(200, body), store, provider.copy(vision = true))
        val e = runCatching { vision.run(image, "опиши") }.exceptionOrNull()
        assertTrue(e?.message?.contains("не увидела изображение") == true)
    }

    @Test
    fun `an image request bakes the strict NO_IMAGE directive into the prompt`() = runTest {
        var sent = ""
        val vision = OpenAiCompatibleClient(http(200, okBody) { sent = it }, store, provider.copy(vision = true))
        vision.run(image, "опиши")
        assertTrue(sent.contains("NO_IMAGE")) // strict format is instructed, not guessed after
    }
}
