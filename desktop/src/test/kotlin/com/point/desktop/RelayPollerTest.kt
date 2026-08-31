package com.point.desktop

import com.point.core.flow.DeviceKind
import com.point.core.flow.KeptLetters
import com.point.core.flow.LinkedPc
import com.point.core.flow.PointAccount
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayRpc
import com.point.core.flow.encodePcFrame
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Живой прогон 2026-08-09: приложение на компьютере упало, пока разбирало письмо.
 * Приём был подтверждён сразу после скачивания, поэтому объект человека исчез с
 * сервера навсегда, а телефон сказал «компьютер не отвечает» (#680).
 */
class RelayPollerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val phone = LinkedPc("phone-1", "Телефон")
    private val me = PointAccount("pc-1", "пропуск", "me@example.com", "Компьютер", DeviceKind.PC)

    private val received = mutableListOf<String>()
    private lateinit var lettersDir: File

    private class Box(letter: ByteArray) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val letterId = "00000000000000000001-aa"

        var waiting: ByteArray? = letter
        var acked = false

        fun base(): String = "http://127.0.0.1:" + server.address.port

        fun start() {
            server.createContext("/") { ex -> handle(ex) }
            server.executor = null
            server.start()
        }

        fun stop() = server.stop(0)

        private fun handle(ex: HttpExchange) {
            ex.requestBody.readBytes()
            val letter = waiting
            when {
                ex.requestURI.path.endsWith("/ack") -> {
                    acked = true
                    waiting = null
                    ex.sendResponseHeaders(200, -1)
                }

                ex.requestMethod == "POST" -> ex.sendResponseHeaders(200, -1)
                letter == null -> ex.sendResponseHeaders(204, -1)
                else -> {
                    ex.responseHeaders.add("X-Blob-Id", letterId)
                    ex.sendResponseHeaders(200, letter.size.toLong())
                    ex.responseBody.use { it.write(letter) }
                }
            }
            ex.close()
        }
    }

    private fun sealedObject(): ByteArray = RelayCrypto.seal(
        key,
        encodePcFrame(
            mapOf(
                RelayRpc.KIND to RelayRpc.OBJECT,
                RelayRpc.ID to "письмо-1",
                "name" to "чек.txt",
                "mime" to "text/plain",
            ),
            "чек".toByteArray(Charsets.UTF_8),
        ),
    )

    private fun poller(box: Box, onContact: (String) -> Unit): RelayPoller = RelayPoller(
        serverUrl = box.base(),
        account = { me },
        peers = { listOf(phone) },
        secrets = { key },
        requests = RelayRequests(
            remoteActions = { emptyList() },
            outbox = Outbox(tmp.newFolder()),
            onPhoneCaps = {},
            clipboardGet = { null },
            clipboardSet = {},
            onObject = { name, _, _, _, _, _ ->
                received += name
                null
            },
        ),

        // Новый разбор поверх той же папки — это и есть следующий запуск приложения.
        letters = KeptLetters(lettersDir),
        onContact = onContact,
    )

    @Test
    fun `сорвавшийся разбор не стоит человеку объекта`() {
        lettersDir = tmp.newFolder()
        val box = Box(sealedObject())
        box.start()
        try {
            val fell = runCatching { poller(box) { error("приложение упало на разборе") }.once() }

            assertTrue("разбор не сорвался — проверять нечего", fell.isFailure)
            assertEquals("объект принят вопреки падению", emptyList<String>(), received)
            assertTrue("сервер всё ещё держит письмо — придёт второй раз", box.acked)

            poller(box) { }.once()

            assertEquals("объект пропал вместе с падением", listOf("чек.txt"), received)
        } finally {
            box.stop()
        }
    }

    /**
     * Кто отозвался, названо по имени (#1108): по этому голосу компьютер решает, проснулся
     * ли телефон, в который он стучал. Безымянное «кто-то из круга» поднимал и второй
     * компьютер — и досмотр стука замолкал, ничего про телефон не узнав.
     */
    @Test
    fun `отозвавшееся устройство названо по имени`() {
        lettersDir = tmp.newFolder()
        val box = Box(sealedObject())
        box.start()
        try {
            val spoke = mutableListOf<String>()
            poller(box) { spoke += it }.once()

            assertEquals("компьютер не знает, кто именно к нему пришёл", listOf(phone.deviceId), spoke)
        } finally {
            box.stop()
        }
    }

    @Test
    fun `разобранное письмо не разбирается второй раз`() {
        lettersDir = tmp.newFolder()
        val box = Box(sealedObject())
        box.start()
        try {
            poller(box) { }.once()
            poller(box) { }.once()

            assertEquals(listOf("чек.txt"), received)
            assertEquals("письмо осталось ждать разбора", emptyList<String>(), KeptLetters(lettersDir).waiting())
        } finally {
            box.stop()
        }
    }
}
