package com.point.core.flow

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class HttpAccountClientTest {

    private class Probe {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = mutableListOf<String>()
        val tokens = mutableListOf<String?>()
        val bodies = mutableListOf<String>()
        var pendingUntil = 0

        var handoff = false

        var omitHandoff = false

        fun base(): String = "http://127.0.0.1:" + server.address.port

        fun start() {
            server.createContext("/") { ex -> handle(ex) }
            server.executor = null
            server.start()
        }

        fun stop() = server.stop(0)

        private fun handle(ex: HttpExchange) {
            val path = ex.requestURI.path
            hits += ex.requestMethod + " " + path
            tokens += ex.requestHeaders.getFirst("Authorization")
            bodies += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val (status, body) = when {
                path == "/auth/start" -> 200 to
                    """{"login_id":"lg-1","claim_token":"cl-1","user_code":"K7-42Q",""" +
                    """"login_url":"$${'$'}","expires_in":300,"interval":2""" +
                    (if (omitHandoff) "}" else ""","handoff":$handoff}""")
                        .replace("$${'$'}", base() + "/login?d=lg-1")

                path.startsWith("/auth/session/") && path != "/auth/session/lg-1" ->
                    404 to """{"error":"no_login","message":"Вход не найден или уже завершён. Начните заново."}"""
                path == "/auth/session/lg-1" && pendingUntil-- > 0 ->
                    202 to """{"status":"pending","user_code":"K7-42Q","interval":2}"""
                path == "/auth/session/lg-1" -> 200 to
                    """{"status":"ready","device_id":"dev-1","device_token":"tok-1","kind":"PHONE",""" +
                    """"name":"Pixel","account":{"email":"me@example.com","name":"Я"}}"""
                else -> 404 to """{"error":"no","message":"нет такой ручки"}"""
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private inline fun <T> withProbe(block: (Probe) -> T): T {
        val probe = Probe()
        probe.start()
        return try {
            block(probe)
        } finally {
            probe.stop()
        }
    }

    @Test fun `опрос сессии действительно уходит на сервер`() = runTest {
        withProbe { probe ->
            val client = HttpAccountClient(probe.base())
            val start = client.start("Pixel", DeviceKind.PHONE)
            assertEquals("lg-1", start?.loginId)

            val poll = client.poll(start!!.loginId, start.claimToken)

            assertTrue("сервер обязан УВИДЕТЬ вопрос о сессии", probe.hits.contains("GET /auth/session/lg-1"))
            assertEquals("Bearer cl-1", probe.tokens.last())
            assertTrue(poll is LoginPoll.Ready)
            assertEquals("dev-1", (poll as LoginPoll.Ready).account.deviceId)
            assertEquals("me@example.com", poll.account.email)
        }
    }

    @Test fun `телефон просит вход одним шагом настоящим признаком, а не строкой`() = runTest {
        withProbe { probe ->
            probe.handoff = true

            val start = HttpAccountClient(probe.base(), handoff = true).start("Pixel", DeviceKind.PHONE)

            assertTrue("признак уехал строкой: " + probe.bodies.first(), probe.bodies.first().contains("\"handoff\":true"))
            assertEquals(true, start?.handoff)
        }
    }

    @Test fun `каким вышел вход, говорит сервер, а не устройство`() = runTest {
        withProbe { probe ->
            probe.handoff = false

            val start = HttpAccountClient(probe.base(), handoff = true).start("Pixel", DeviceKind.PHONE)

            assertEquals(false, start?.handoff)
            assertEquals("K7-42Q", start?.code)
        }
    }

    @Test fun `старый сервер без этого поля — это вход со сверкой кода`() = runTest {
        withProbe { probe ->
            probe.omitHandoff = true

            val start = HttpAccountClient(probe.base(), handoff = true).start("Pixel", DeviceKind.PHONE)

            assertEquals(false, start?.handoff)
        }
    }

    @Test fun `«ещё не подтвердил» — это ожидание, а не отказ`() = runTest {
        withProbe { probe ->
            probe.pendingUntil = 1
            val client = HttpAccountClient(probe.base())
            val start = client.start("Pixel", DeviceKind.PHONE)!!

            assertEquals(LoginPoll.Pending, client.poll(start.loginId, start.claimToken))
            assertTrue(client.poll(start.loginId, start.claimToken) is LoginPoll.Ready)
        }
    }

    @Test fun `молчание сети отличается от отказа сервера`() = runTest {
        val dead = HttpAccountClient("http://127.0.0.1:1", connectTimeoutMs = 200, readTimeoutMs = 200)
        assertEquals(
            "молчание сети приехало отказом входа — а отказ терминален: один сбой обрывает вход",
            LoginPoll.Silent,
            dead.poll("lg-1", "cl-1"),
        )

        withProbe { probe ->

            val refused = HttpAccountClient(probe.base()).poll("нет-такого", "cl-1")
            assertTrue(refused is LoginPoll.Refused)
            assertTrue(
                "отказ доносится словами сервера",
                (refused as LoginPoll.Refused).what.contains("не найден"),
            )
        }
    }
}
