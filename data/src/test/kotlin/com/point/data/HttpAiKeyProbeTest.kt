package com.point.data

import com.point.core.flow.KeyCheck
import com.point.core.flow.UserAiConfig
import com.point.core.flow.keyFingerprint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * «Проверить ключ» (#447): спросили провайдера по-настоящему — говорим то, что он ответил.
 *
 * Сети здесь нет: транспорт за [HttpJson], и весь смысл пробы — как она читает ответ.
 */
class HttpAiKeyProbeTest {

    private val config = UserAiConfig("sk-user-123456789", "https://api.groq.com/openai/v1", "llama-3.3-70b")
    private val answered = """{"choices":[{"message":{"content":"pong"}}]}"""

    private fun http(result: (String, Map<String, String>, String) -> HttpResult) = object : HttpJson {
        override suspend fun post(url: String, headers: Map<String, String>, body: String) =
            result(url, headers, body)
    }

    @Test fun `проба идёт той же дорогой, что настоящее действие с AI`() = runTest {
        var url = ""
        var auth = ""
        var body = ""
        val probe = HttpAiKeyProbe(http { u, h, b -> url = u; auth = h["Authorization"].orEmpty(); body = b; HttpResult(200, answered) })

        val check = probe.check(config)

        // Тот же путь и тот же заголовок, что у `UserKeyLlmClient`: проверка, ходящая куда-то ещё,
        // отвечает на другой вопрос и может сказать «всё хорошо» там, где действие откажет.
        assertEquals("https://api.groq.com/openai/v1/chat/completions", url)
        assertEquals("Bearer sk-user-123456789", auth)
        assertTrue("модель спрашивают ту самую", body.contains("llama-3.3-70b"))
        assertTrue("запрос самый дешёвый из настоящих", body.contains("\"max_tokens\":1"))
        assertTrue(check is KeyCheck.Works)
    }

    @Test fun `ответ помечен настройками, на которых получен`() = runTest {
        val probe = HttpAiKeyProbe(http { _, _, _ -> HttpResult(200, answered) }, now = { 1_000 })

        val works = probe.check(config) as KeyCheck.Works

        assertEquals(keyFingerprint(config), works.checked)
        assertEquals("llama-3.3-70b", works.model)
    }

    @Test fun `время ответа берётся с часов, а не засекается`() = runTest {
        val ticks = ArrayDeque(listOf(1_000L, 2_400L))
        val probe = HttpAiKeyProbe(http { _, _, _ -> HttpResult(200, answered) }, now = { ticks.removeFirst() })

        assertEquals(1_400L, (probe.check(config) as KeyCheck.Works).tookMs)
    }

    @Test fun `отказ провайдера доносится его кодом и его словами`() = runTest {
        val probe = HttpAiKeyProbe(http { _, _, _ -> HttpResult(401, """{"error":{"message":"Invalid API Key"}}""") })

        val rejected = probe.check(config) as KeyCheck.Rejected

        assertTrue(rejected.reason.contains("Groq"))
        assertTrue(rejected.reason.contains("401"))
        assertTrue("слова провайдера не сглаживаются", rejected.reason.contains("Invalid API Key"))
    }

    @Test fun `двести без модели — это тоже отказ, а не удача`() = runTest {
        // Часть шлюзов отвечает 200 с телом-ошибкой. Судить по коду значило бы отчитаться об
        // успехе там, где ответа нет, — тот же самый вид лжи, из-за которого затевалась проверка.
        val probe = HttpAiKeyProbe(http { _, _, _ -> HttpResult(200, """{"error":"model_decommissioned"}""") })

        val rejected = probe.check(config) as KeyCheck.Rejected

        assertTrue(rejected.reason.contains("без модели"))
        assertTrue(rejected.reason.contains("model_decommissioned"))
    }

    @Test fun `оборвавшаяся сеть — отказ сети, названный сетью`() = runTest {
        val probe = HttpAiKeyProbe(object : HttpJson {
            override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult =
                throw IOException("failed to connect")
        })

        val rejected = probe.check(config) as KeyCheck.Rejected

        assertTrue(rejected.reason.contains("Не удалось достучаться"))
        assertTrue(rejected.reason.contains("failed to connect"))
    }

    @Test fun `пустой ключ и пустой адрес не тревожат сеть вовсе`() = runTest {
        var calls = 0
        val probe = HttpAiKeyProbe(http { _, _, _ -> calls++; HttpResult(200, answered) })

        assertTrue(probe.check(config.copy(apiKey = "  ")) is KeyCheck.Rejected)
        assertTrue(probe.check(config.copy(baseUrl = "")) is KeyCheck.Rejected)
        assertEquals(0, calls)
    }

    @Test fun `незнакомый адрес не выдаётся за провайдера из списка`() = runTest {
        val probe = HttpAiKeyProbe(http { _, _, _ -> HttpResult(403, "nope") })

        val rejected = probe.check(config.copy(baseUrl = "https://my.proxy/v1")) as KeyCheck.Rejected

        assertTrue(rejected.reason.startsWith("Сервис"))
    }
}
