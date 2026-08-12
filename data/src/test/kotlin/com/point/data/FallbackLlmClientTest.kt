package com.point.data

import com.point.core.flow.LlmClient
import com.point.core.flow.NetworkAvailability
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackLlmClientTest {

    private val obj = PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))

    private val facts = TestAiFacts()

    private fun chain(
        providers: List<LlmClient>,
        network: NetworkAvailability = NetworkAvailability { true },
        yolo: com.point.core.flow.YoloMode = com.point.core.flow.YoloMode.OFF,
    ) = FallbackLlmClient(providers, facts, network, yolo)

    private fun yoloOn() = object : com.point.core.flow.YoloMode {
        override fun enabled() = true
        override suspend fun setEnabled(enabled: Boolean) = Unit
    }

    private fun ok(tag: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String) =
            ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/out/$tag"))
    }

    private fun failing(message: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject = error(message)
    }

    @Test
    fun `returns the first provider that succeeds`() = runTest {
        val client = chain(listOf(ok("primary"), ok("secondary")))
        assertEquals("/out/primary", client.run(obj, "hi").uri.value)
    }

    @Test
    fun `falls back to the next provider when the first fails (e g 429)`() = runTest {
        val client = chain(listOf(failing("Gemini HTTP 429"), ok("openai")))
        assertEquals("/out/openai", client.run(obj, "hi").uri.value)
    }

    @Test
    fun `surfaces combined errors when all providers fail`() = runTest {
        val client = chain(listOf(failing("Gemini HTTP 429"), failing("OPENAI_API_KEY не задан")))
        val error = runCatching { client.run(obj, "hi") }.exceptionOrNull()
        assertTrue(error?.message?.contains("Gemini HTTP 429") == true)
        assertTrue(error?.message?.contains("OPENAI_API_KEY") == true)
    }

    @Test
    fun `collapses a wall of network errors into one line (issue 48)`() = runTest {
        val client = chain(
            List(8) { failing("""Unable to resolve host "api.groq.com": No address associated with hostname""") },
        )
        val error = runCatching { client.run(obj, "hi") }.exceptionOrNull()
        assertTrue(error?.message?.contains("нет подключения к интернету") == true)
        assertFalse(error?.message?.contains("resolve host") == true)
    }

    @Test
    fun `исчерпанная квота — одна фраза про бесплатное, а не склейка провайдерских строк`() = runTest {

        val client = chain(
            listOf(
                failing("openrouter: бесплатный лимит исчерпан — вернитесь позже, платить не идём"),
                failing("Gemini HTTP 429"),
            ),
        )

        val error = runCatching { client.run(obj, "hi") }.exceptionOrNull()

        assertTrue(error?.message, error?.message?.contains("вернитесь позже") == true)
        assertTrue(error?.message, error?.message?.contains("платить не идём") == true)
        assertFalse(error?.message, error?.message?.contains("HTTP") == true)
    }

    @Test
    fun `no providers asks the user to set a key`() = runTest {
        val error = runCatching { chain(emptyList()).run(obj, "hi") }.exceptionOrNull()
        assertTrue(error?.message?.contains("задайте свой ключ") == true)
    }

    private val image = PointObject("i", "image/png", ScratchRef("/x.png"), ObjectState(ObjectKind.IMAGE))

    private fun textOnly() = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject = error("must not run for an image")
        override fun canHandle(obj: PointObject) = !obj.mime.startsWith("image/")
    }

    @Test
    fun `an image skips text-only providers and reaches a vision one`() = runTest {
        val client = chain(listOf(textOnly(), ok("vision")))
        assertEquals("/out/vision", client.run(image, "опиши").uri.value)
    }

    @Test
    fun `an image with only text-only providers gives a clear no-model error, not a false answer`() = runTest {
        val error = runCatching { chain(listOf(textOnly())).run(image, "опиши") }.exceptionOrNull()
        assertTrue(error?.message?.contains("нет подходящей AI-модели") == true)
    }

    private fun strong(tag: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String) =
            ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/out/$tag"))
        override val strongVision = true
    }

    @Test
    fun `an image leads with a strong vision model, but text keeps the original order`() = runTest {
        val client = chain(listOf(ok("weak"), strong("strong")))
        assertEquals("/out/strong", client.run(image, "опиши").uri.value)
        assertEquals("/out/weak", client.run(obj, "hi").uri.value)
    }

    private val voice = PointObject("v", "audio/ogg", ScratchRef("/x.ogg"), ObjectState(ObjectKind.AUDIO))

    private fun deaf() = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject =
            error("must not run for audio")
        override fun canHandle(obj: PointObject) = !obj.mime.startsWith("audio/")
    }

    @Test
    fun `запись минует глухих провайдеров и доходит до слышащего`() = runTest {

        val client = chain(listOf(deaf(), ok("audio")))

        assertEquals("/out/audio", client.run(voice, "расшифруй").uri.value)
    }

    @Test
    fun `на записи подсказка просит ключ с поддержкой аудио, а не изображений`() = runTest {
        val error = runCatching { chain(listOf(deaf())).run(voice, "расшифруй") }.exceptionOrNull()

        assertTrue(error?.message?.contains("с поддержкой аудио") == true)
        assertFalse("картинка тут ни при чём", error?.message?.contains("изображени") == true)
    }

    private fun unconfigured() = object : LlmClient {
        override val configured = false
        override suspend fun run(obj: PointObject, prompt: String): ResultObject =
            error("задайте свой ключ — откройте «Настройки» на домашнем экране")
    }

    private fun offline() = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject =
            error("Unable to resolve host \"openrouter.ai\": No address associated with hostname")
    }

    @Test
    fun `без сети человек читает «нет подключения», а не про ключ и хвост исключения`() = runTest {
        // Живой прогон 2026-08-09 (эмулятор, offline): «AI недоступен — задайте свой
        // ключ …; Unable to resolve host…» — ненастроенный провайдер шумел в диагноз.
        val error = runCatching {
            chain(listOf(unconfigured(), offline())).run(obj, "пойми")
        }.exceptionOrNull()

        assertEquals(FallbackLlmClient.NO_NETWORK_MESSAGE, error?.message)
    }

    @Test
    fun `совсем нет настроенных провайдеров — честное «задайте ключ», без списка обломков`() = runTest {
        val error = runCatching {
            chain(listOf(unconfigured())).run(obj, "пойми")
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("задайте свой ключ") == true)
        assertFalse(error?.message?.contains("resolve host") == true)
    }

    @Test
    fun `на телефоне нет сети — ни один провайдер не получает запрос`() = runTest {
        // Решение владельца (#690, #691): «Перед выходом наружу Point спрашивает у
        // телефона, есть ли сеть. Нет — говорит сразу, не отправив ни одного запроса».
        val calls = mutableListOf<String>()
        val spies = listOf(spy("primary", calls), spy("secondary", calls))

        val error = runCatching {
            chain(spies, network = NetworkAvailability { false }).run(obj, "пойми")
        }.exceptionOrNull()

        assertEquals(FallbackLlmClient.NO_NETWORK_MESSAGE, error?.message)
        assertTrue("офлайн ни один провайдер не должен получить запрос", calls.isEmpty())
    }

    @Test
    fun `провайдер без ключа не требует сети — до неё не доходит`() = runTest {
        val error = runCatching {
            chain(listOf(unconfigured()), network = NetworkAvailability { false }).run(obj, "пойми")
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("задайте свой ключ") == true)
    }

    /**
     * Режим YOLO (#795): человек попросил лучшее, и сильная модель идёт первой даже там,
     * где обычный порядок бережёт очередь — на простом тексте.
     */
    @Test
    fun `в режиме YOLO сильная модель идёт первой и на тексте`() = runTest {
        val client = chain(listOf(ok("weak"), strong("strong")), yolo = yoloOn())

        assertEquals("/out/strong", client.run(obj, "hi").uri.value)
    }

    @Test
    fun `без режима порядок цепочки на тексте прежний`() = runTest {
        val client = chain(listOf(ok("weak"), strong("strong")))

        assertEquals("/out/weak", client.run(obj, "hi").uri.value)
    }

    private fun spy(tag: String, calls: MutableList<String>) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            calls += tag
            return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/out/$tag"))
        }
    }
}

/** Память об исходах для тестов цепочки — настоящая, но без диска. */
internal class TestAiFacts : com.point.core.flow.AiFacts {
    val seen = mutableMapOf<String, com.point.core.flow.AiOutcome>()
    override fun all(): Map<String, com.point.core.flow.AiFact> =
        seen.mapValues { com.point.core.flow.AiFact(it.value, 0L) }
    override fun remember(providerId: String, outcome: com.point.core.flow.AiOutcome) {
        seen[providerId] = outcome
    }
}
