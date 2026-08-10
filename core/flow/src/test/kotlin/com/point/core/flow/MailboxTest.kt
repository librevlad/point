package com.point.core.flow

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Живой прогон 2026-08-09: компьютер упал между «скачал» и «сохранил», письмо уже
 * было подтверждено — объект человека исчез навсегда, а телефон сказал «компьютер
 * не отвечает». Сначала на диск, потом подтверждение (#680).
 */
class MailboxTest {

    private class Box {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = mutableListOf<String>()
        val letterId = "00000000000000000001-aa"

        var letter: ByteArray? = "запечатанное письмо".toByteArray()

        fun base(): String = "http://127.0.0.1:" + server.address.port

        fun acked(): Boolean = hits.any { it.contains("/ack") }

        fun start() {
            server.createContext("/") { ex -> handle(ex) }
            server.executor = null
            server.start()
        }

        fun stop() = server.stop(0)

        private fun handle(ex: HttpExchange) {
            hits += ex.requestMethod + " " + ex.requestURI.toString()
            ex.requestBody.readBytes()
            val waiting = letter
            when {
                ex.requestURI.path.endsWith("/ack") -> {
                    if (ex.requestURI.query == "blob=$letterId") letter = null
                    ex.sendResponseHeaders(200, -1)
                }

                waiting == null -> ex.sendResponseHeaders(204, -1)
                else -> {
                    ex.responseHeaders.add("X-Blob-Id", letterId)
                    ex.sendResponseHeaders(200, waiting.size.toLong())
                    ex.responseBody.use { it.write(waiting) }
                }
            }
            ex.close()
        }
    }

    private inline fun <T> withBox(block: (Box) -> T): T {
        val box = Box()
        box.start()
        return try {
            block(box)
        } finally {
            box.stop()
        }
    }

    @Test
    fun `приём подтверждается только после того, как письмо сохранено`() = withBox { box ->
        val sent = box.letter!!
        var ackedWhileSaving = true

        val letter = Mailbox(box.base(), { "пропуск" }).take("dev-1") { ackedWhileSaving = box.acked() }

        assertArrayEquals(sent, letter.blob)
        assertEquals(box.letterId, letter.id)
        assertTrue("сервер отпустил письмо раньше, чем оно сохранено", !ackedWhileSaving)
        assertTrue("сервер держит уже сохранённое письмо — оно приедет снова", box.acked())
        assertNull("ящик не освободился", box.letter)
    }

    @Test
    fun `сорвавшееся сохранение оставляет письмо на сервере`() = withBox { box ->
        val sent = box.letter!!
        val mailbox = Mailbox(box.base(), { "пропуск" })

        val saving = runCatching { mailbox.take("dev-1") { error("на диске нет места") } }

        assertTrue("сбой сохранения проглочен молча", saving.isFailure)
        assertTrue("несохранённое письмо подтверждено — объект пропал", !box.acked())

        var second: ByteArray? = null
        mailbox.take("dev-1") { second = it.blob }
        assertArrayEquals("письмо не приехало снова", sent, second)
    }

    @Test
    fun `пустой ящик нечего сохранять и нечего подтверждать`() = withBox { box ->
        box.letter = null
        var saves = 0

        val letter = Mailbox(box.base(), { "пропуск" }).take("dev-1") { saves++ }

        assertEquals(204, letter.code)
        assertNull(letter.blob)
        assertEquals(0, saves)
        assertTrue(!box.acked())
    }

    @Test
    fun `молчание сети не выдаёт себя за пустой ящик`() {
        var saves = 0

        val letter = Mailbox("http://127.0.0.1:1", { null }, connectTimeoutMs = 200, readTimeoutMs = 200)
            .take("dev-1") { saves++ }

        assertEquals(Mailbox.NETWORK, letter.code)
        assertEquals(0, saves)
    }
}
