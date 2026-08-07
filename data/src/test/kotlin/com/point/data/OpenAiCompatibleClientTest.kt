package com.point.data

import com.point.core.flow.ObjectStore
import com.point.core.flow.refusalNeedsKey
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

private const val RATE_LIMIT_JSON =
    """{"error":{"message":"Rate limit reached for model gemma-4-31b-it:free","type":"rate_limit"}}"""

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

    private suspend fun refusalFor(code: Int, body: String = RATE_LIMIT_JSON): String =
        runCatching { client(http(code, body)).run(textObj, "hi") }.exceptionOrNull()?.message.orEmpty()

    @Test
    fun `отказ сервиса написан словами — без кода, без слова HTTP и без чужого ответа`() = runTest {

        listOf(401, 402, 429, 500).forEach { code ->
            val said = refusalFor(code)
            assertFalse(said, said.contains("{"))
            assertFalse(said, said.contains("}"))
            assertFalse(said, said.contains("HTTP", ignoreCase = true))
            assertFalse(said, said.contains(code.toString()))
            assertFalse(said, said.contains("Rate limit reached"))

            assertTrue(said, said.contains("openrouter"))
        }
    }

    @Test
    fun `неверный ключ ведёт в настройки, а не в тупик`() = runTest {

        assertTrue(refusalNeedsKey(refusalFor(401)))
        assertTrue(refusalNeedsKey(refusalFor(403)))
    }

    @Test
    fun `исчерпанный лимит ключом не чинится — в настройки не зовут`() = runTest {

        assertFalse(refusalNeedsKey(refusalFor(429)))
    }

    @Test
    fun `отказ по лимиту узнаётся сводкой цепочки как исчерпанная квота`() = runTest {

        assertTrue(refusalFor(429).isQuotaError())
    }

    @Test
    fun `ключ человека не попадает в текст отказа`() = runTest {

        val echoed = """{"error":{"message":"invalid api key sk-key"}}"""
        assertFalse(refusalFor(401, echoed).contains("sk-key"))
    }

    @Test
    fun `в раздаваемой сборке под объектом стоит фраза, а не кусок чужого JSON`() = runTest {

        val mine = OpenAiProvider("свой ключ", "https://x/v1", "sk-key", "some-model")
        suspend fun said(code: Int): String? {
            val chain = FallbackLlmClient(listOf(OpenAiCompatibleClient(http(code, RATE_LIMIT_JSON), store, mine)))
            return runCatching { chain.run(textObj, "hi") }.exceptionOrNull()?.message
        }

        assertEquals("AI недоступен — свой ключ: ключ не принят — задайте свой ключ в настройках", said(401))
        assertEquals("Бесплатные лимиты AI исчерпаны — вернитесь позже, платить не идём", said(429))
        assertEquals(
            "AI недоступен — свой ключ: сервис просит оплату — у этого ключа нет бесплатного доступа",
            said(402),
        )
        assertEquals("AI недоступен — свой ключ: сервис сейчас не отвечает", said(500))
    }

    @Test
    fun `сервис, который не отвечает, назван поломкой сервиса, а не ключа`() = runTest {
        assertTrue(refusalFor(503).contains("не отвечает"))
        assertFalse(refusalNeedsKey(refusalFor(503)))
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

    private val image = PointObject("i", "image/png", ScratchRef("/x.png"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `a text-only model cannot handle an image but still handles text`() {
        val textOnly = client(http(200, okBody))
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

    @Test
    fun `замеренные читатели страницы объявлены сильными — цепочка на снимке ведёт ими`() {

        listOf(
            "google/gemma-4-26b-a4b-it:free",
            "gemma-4-31B-it",
            "qwen/qwen3.6-27b",
            "Qwen2.5-VL-72B-Instruct",
        ).forEach { assertTrue(it, isMeasuredStrongVision(it)) }
    }

    @Test
    fun `тот, кто читает хуже специальной ручки, сильным не объявлен`() {

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
        assertTrue(sent.contains("NO_IMAGE"))
    }
}
