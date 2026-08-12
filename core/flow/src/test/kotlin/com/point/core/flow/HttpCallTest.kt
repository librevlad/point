package com.point.core.flow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Отказ человека должен доходить до сети. Иначе запрос молча досиживает свой таймаут и тратит
 * бесплатный лимит уже после того, как человек нажал «Отменить» (#692).
 */
class HttpCallTest {

    @Test fun `отказ закрывает отправку и не досиживает таймаут ответа`() {
        val waited = waitedAfterCancel { url -> UrlConnectionHttpJson().post(url, emptyMap(), "{}") }
        assertTrue("отмена ждала $waited мс", waited < WAITED_TOO_LONG_MS)
    }

    @Test fun `отказ закрывает и запрос за файлом, а не только отправку`() {
        val waited = waitedAfterCancel { url -> UrlConnectionHttpFiles().get(url, emptyMap()) }
        assertTrue("отмена ждала $waited мс", waited < WAITED_TOO_LONG_MS)
    }

    @Test fun `без отказа запрос никто не рвёт- ответ доходит целиком`() = runBlocking {
        val answering = ServerSocket(0)
        val listener = Thread {
            runCatching {
                answering.accept().use { client ->
                    client.getInputStream().read(ByteArray(4096))
                    client.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: 11\r\n" +
                                    "Connection: close\r\n\r\n" +
                                    "{\"ok\":true}"
                                ).toByteArray(),
                        )
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val result = UrlConnectionHttpJson()
            .post("http://127.0.0.1:${answering.localPort}/say", emptyMap(), "{}")

        assertEquals(200, result.code)
        assertEquals("{\"ok\":true}", result.body)
        listener.join(2_000)
        answering.close()
    }

    /** Сколько запрос ещё держался после отказа. Сервер берёт трубку и молчит. */
    private fun waitedAfterCancel(request: suspend (String) -> Unit): Long = runBlocking {
        SilentServer().use { server ->
            val call = launch(Dispatchers.IO) { runCatching { request(server.url) } }
            assertTrue("запрос не дошёл до сервера", server.reached.await(20, TimeUnit.SECONDS))

            Thread.sleep(300)
            val started = System.currentTimeMillis()
            call.cancel()
            withTimeout(WAITED_TOO_LONG_MS) { call.join() }
            System.currentTimeMillis() - started
        }
    }

    /** Сервер, который берёт трубку и молчит- как связь, которой на деле нет. */
    private class SilentServer : AutoCloseable {
        private val socket = ServerSocket(0)
        private val taken = mutableListOf<Socket>()
        val reached = CountDownLatch(1)

        private val listener = Thread {
            runCatching {
                while (true) {
                    val client = socket.accept()
                    synchronized(taken) { taken += client }
                    reached.countDown()
                }
            }
        }.apply { isDaemon = true; start() }

        val url: String get() = "http://127.0.0.1:${socket.localPort}/wait"

        override fun close() {
            synchronized(taken) { taken.forEach { runCatching { it.close() } } }
            runCatching { socket.close() }
            listener.interrupt()
        }
    }

    private companion object {

        /** Заметно меньше, чем таймауты самого запроса- 30 с на связь и 60 с на ответ. */
        const val WAITED_TOO_LONG_MS = 5_000L
    }
}
