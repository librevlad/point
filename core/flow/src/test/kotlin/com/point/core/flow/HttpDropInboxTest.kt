package com.point.core.flow

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Приём файла — один код на обе стороны (#727). Телефон и компьютер разговаривают с сервером
 * одинаково, поэтому и правка в приёме чинит обе стороны сразу.
 */
class HttpDropInboxTest {

    // Адрес нарочно недостижим (порт 1): без сети до него не должно дойти вообще.
    private fun inbox(network: NetworkAvailability, pass: String? = "pass") =
        HttpDropInbox({ "https://127.0.0.1:1" }, { pass }, network)

    /** Сервер Point в миниатюре: открывает ящик, отвечает «пусто» на ожидание, закрывает. */
    private class Probe {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = mutableListOf<String>()
        val tokens = mutableListOf<String?>()

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
            ex.requestBody.readBytes()
            val (status, body) = when {
                path == "/u/open" -> 200 to """{"box":"$BOX","url":"${base()}/u/$BOX"}"""
                path == "/u/$BOX/take" -> 204 to ""
                path == "/u/$BOX/close" || path == "/u/$BOX/ack" -> 200 to ""
                else -> 404 to """{"error":"no","message":"нет такой ручки"}"""
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (status == 204) {
                ex.sendResponseHeaders(status, -1)
            } else {
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
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

    @Test
    fun `нет сети — ссылку не готовим, и отказ говорит именно про сеть`() = runTest {
        val outcome = inbox(NetworkAvailability { false }).open()

        assertEquals(DropOpen.Refused(NO_NETWORK_TEXT), outcome)
    }

    @Test
    fun `нет сети — ожидание файла говорит об этом честно`() = runTest {
        val outcome = inbox(NetworkAvailability { false })
            .await(DropInboxBox("box", "https://x/u/box")) { "unused" }

        assertEquals(DropWait.Failed(NO_NETWORK_TEXT), outcome)
    }

    @Test
    fun `устройство не в круге — наружу не ходим вовсе`() = runTest {
        val outcome = inbox(NetworkAvailability { true }, pass = null).open()

        assertTrue("отказ обязан назвать причину, а не молчать", outcome is DropOpen.Refused)
    }

    /** Живой сервер открывает ящик — и телефон получает ссылку, а не отказ (#1077). */
    @Test
    fun `живой сервер — ящик открыт, ссылка его, пропуск показан серверу`() = runTest {
        withProbe { probe ->
            val opened = HttpDropInbox({ probe.base() }, { "pass" }).open()

            assertTrue("сервер ответил ящиком — это ссылка, а не отказ: $opened", opened is DropOpen.Opened)
            assertEquals(probe.base() + "/u/" + BOX, (opened as DropOpen.Opened).box.link)
            assertTrue("ящик открывают ручкой /u/open", "POST /u/open" in probe.hits)
            assertEquals("Bearer pass", probe.tokens.single())
        }
    }

    /**
     * Разговор с сервером не идёт на потоке, который позвал (#1077).
     *
     * Телефон зовёт приём с главного потока, а Android на нём сеть запрещает: вызов падал до
     * выхода наружу, и падение звалось «Сервер Point не ответил» — при живом сервере. Пропуск
     * спрашивается и до выхода, и в самом запросе — по нему видно, на каком потоке шёл запрос.
     */
    @Test
    fun `запрос к серверу уходит не с потока, который позвал, — ни открыть, ни ждать, ни закрыть`() {
        withProbe { probe ->
            val caller = Executors.newSingleThreadExecutor { r -> Thread(r, CALLER) }.asCoroutineDispatcher()
            val asked = mutableListOf<String>()
            val inbox = HttpDropInbox({ probe.base() }, { asked += Thread.currentThread().name; "pass" })
            val box = DropInboxBox(BOX, probe.base() + "/u/" + BOX)

            val steps = mapOf<String, suspend () -> Unit>(
                "открыть" to { inbox.open() },
                "ждать" to { inbox.await(box) { "unused" } },
                "подтвердить" to { inbox.ack(box, "file-1") },
                "закрыть" to { inbox.close(box) },
            )
            // Имя потока сравнивается по началу: отладка корутин дописывает к нему « @coroutine#N».
            for ((name, step) in steps) {
                asked.clear()
                probe.hits.clear()
                runBlocking(caller) { step() }

                assertTrue("шаг «$name» обязан дойти до сервера: ${probe.hits}", probe.hits.isNotEmpty())
                assertTrue("шаг «$name» спрашивал пропуск на потоке $CALLER: $asked", asked.any { it.startsWith(CALLER) })
                assertTrue(
                    "шаг «$name» ходил в сеть с потока $CALLER — на телефоне это падение: $asked",
                    asked.any { !it.startsWith(CALLER) },
                )
            }
            caller.close()
        }
    }

    /** Сервера на месте нет — вот это и есть «сервер не ответил», своим именем (#1077). */
    @Test
    fun `сервер молчит — отказ зовётся молчанием сервера, а не сбоем устройства`() = runTest {
        val outcome = inbox(NetworkAvailability { true }).open()

        assertEquals(DropOpen.Refused(NO_SERVER_TEXT), outcome)
    }

    /**
     * Ящик закрывается тем же адресом, каким сервер его закрывает (#729). Разъедутся —
     * дверь останется открытой, и предел в пять ссылок выберется обычным приёмом.
     */
    @Test
    fun `закрытие ящика есть в контракте приёма`() {
        assertTrue(
            "без закрытия ящик убирает только суточная уборка",
            "close" in HttpDropInbox::class.java.methods.map { it.name },
        )
    }

    @Test
    fun `приезд файла сам по себе не подтверждает приём — сервер держит его до объекта`() {
        // Живой прогон 2026-08-10: ящик опустел, а объекта не появилось — файл исчез навсегда.
        // Прислал его чужой человек, и прислать заново он не может (#726).
        assertTrue(
            "подтверждение обязано быть отдельным шагом контракта",
            "ack" in HttpDropInbox::class.java.methods.map { it.name },
        )
    }

    private companion object {
        const val BOX = "aaaaaaaaaaaaaaaaaaaaaa"
        const val CALLER = "caller"
    }
}
