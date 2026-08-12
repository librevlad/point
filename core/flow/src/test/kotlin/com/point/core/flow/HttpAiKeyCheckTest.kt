package com.point.core.flow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class HttpAiKeyCheckTest {

    private val config = UserAiConfig(
        apiKey = "  sk-секретный-ключ-целиком  ",
        baseUrl = "https://api.example/v1/",
        model = "some-model",
    )

    private class FakeHttp(
        private val code: Int = 200,
        private val body: String = """{"choices":[{"message":{"content":"Готово"}}]}""",
        private val boom: Throwable? = null,
    ) : HttpJson {
        var url: String? = null
        var headers: Map<String, String> = emptyMap()
        var sent: String? = null

        override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            this.url = url
            this.headers = headers
            this.sent = body
            boom?.let { throw it }
            return HttpResult(code, this.body)
        }
    }

    @Test
    fun `стучится туда же, куда пойдут настоящие действия`() = runTest {
        val http = FakeHttp()
        HttpAiKeyCheck(http).check(config)

        assertEquals("https://api.example/v1/chat/completions", http.url)
        assertEquals("Bearer sk-секретный-ключ-целиком", http.headers["Authorization"])
        assertTrue("проверка должна называть выбранную модель", http.sent!!.contains("some-model"))
    }

    @Test
    fun `проверка стоит одно короткое слово, а не объект человека`() = runTest {
        val http = FakeHttp()
        HttpAiKeyCheck(http).check(config)

        val sent = http.sent!!
        assertTrue("уходит наше слово, а не чужие данные", sent.contains("готово"))
        assertTrue("ответ должен быть коротким — квота не наша", sent.contains("max_tokens"))
    }

    @Test
    fun `ответ модели доезжает до приговора`() = runTest {
        val probe = HttpAiKeyCheck(FakeHttp()).check(config)

        assertEquals(200, probe.status)
        assertEquals(KeyVerdict.Works("Готово"), keyVerdict(probe))
    }

    @Test
    fun `отказ сервиса приезжает со своим кодом, а не исключением`() = runTest {
        val probe = HttpAiKeyCheck(FakeHttp(code = 429, body = "rate limit")).check(config)

        assertEquals(429, probe.status)
        assertTrue((keyVerdict(probe) as KeyVerdict.Refused).what.contains("квота"))
    }

    @Test
    fun `оборванная связь — это отсутствие кода, а не код ноль`() = runTest {
        val probe = HttpAiKeyCheck(FakeHttp(boom = UnknownHostException("api.example"))).check(config)

        assertNull("без ответа статуса быть не может", probe.status)
        assertTrue((keyVerdict(probe) as KeyVerdict.Refused).what.contains("дозвонились"))
    }

    @Test
    fun `ключ не возвращается на экран вместе с ответом сервиса`() = runTest {

        val echo = "invalid api key: sk-секретный-ключ-целиком"
        val probe = HttpAiKeyCheck(FakeHttp(code = 401, body = echo)).check(config)

        assertFalse("секрет уехал бы на экран", probe.error!!.contains("sk-секретный-ключ-целиком"))
    }

    @Test
    fun `ответ не по форме не выдаётся за работающий ключ`() = runTest {
        val probe = HttpAiKeyCheck(FakeHttp(body = "<html>прокси съел ответ</html>")).check(config)

        assertEquals(200, probe.status)
        assertTrue(keyVerdict(probe) is KeyVerdict.Refused)
    }
}
