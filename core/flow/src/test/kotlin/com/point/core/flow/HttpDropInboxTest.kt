package com.point.core.flow

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

        /** Что лежит в ящике: имя и байты. Пусто — сервер отвечает «пока ничего». */
        var file: Pair<String, ByteArray>? = null

        /** Запрос за файлом дошёл до сервера. */
        val asked = CountDownLatch(1)

        /** Ответ с файлом держится, пока не отпустят: так видно, кончился ли приём раньше. */
        var holds: CountDownLatch? = null

        fun base(): String = "http://127.0.0.1:" + server.address.port

        fun start() {
            server.createContext("/") { ex -> handle(ex) }
            server.executor = null
            server.start()
        }

        fun stop() {
            holds?.countDown()
            server.stop(0)
        }

        private fun handle(ex: HttpExchange) {
            val path = ex.requestURI.path
            hits += ex.requestMethod + " " + path
            tokens += ex.requestHeaders.getFirst("Authorization")
            ex.requestBody.readBytes()
            val sending = file
            if (path == "/u/$BOX/take" && sending != null) {
                asked.countDown()
                holds?.await()
                ex.responseHeaders.add("Content-Type", "application/pdf")
                ex.responseHeaders.add("X-File-Id", FILE_ID)
                ex.responseHeaders.add(
                    "X-File-Name",
                    Base64.getEncoder().encodeToString(sending.first.toByteArray(Charsets.UTF_8)),
                )
                ex.sendResponseHeaders(200, sending.second.size.toLong())
                ex.responseBody.use { it.write(sending.second) }
                return
            }
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

    /**
     * Устройство, запрещающее сеть с потока [thread] — как Android с главного потока (#1077);
     * `null` — с любого потока.
     *
     * Играет пропуск: его спрашивают дважды — на входе («устройство в аккаунте?») и при сборке
     * запроса, уже внутри вызова. Запрет стреляет при сборке: ровно так на телефоне рвался сам
     * вызов — `NetworkOnMainThreadException` из недр `HttpURLConnection`, не `IOException`.
     */
    private class NetworkForbiddenOn(private val thread: String?) : () -> String {
        private var asked = 0

        override fun invoke(): String {
            val building = asked++ > 0
            val here = Thread.currentThread().name
            if (building && (thread == null || here.startsWith(thread))) {
                throw IllegalStateException("сеть с потока $here запрещена")
            }
            return "pass"
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

    /**
     * Живая находка #1077 — та самая ветка отказа, воспроизведённая до починки.
     *
     * Владелец 18.08.2026: телефон на «Принять файл по ссылке» отвечает «Сервер Point не
     * ответил», а тот же сервер в ту же минуту открывает ящик компьютеру того же круга. Корень —
     * запрос, собранный на потоке звонившего: телефон зовёт приём с главного потока, а Android на
     * нём сеть запрещает — вызов рвётся на устройстве ещё до выхода наружу, и это не `IOException`.
     *
     * Здесь запрет играет устройство ([NetworkForbiddenOn]): собрать запрос на потоке звонившего
     * нельзя. На коде до починки тест падает ровно фразой владельца — отказ «Сервер Point не
     * ответил» при живом сервере; после — тот же сервер открывает ящик, потому что запрос ушёл
     * на поток ввода-вывода.
     */
    @Test
    fun `сеть с потока звонившего запрещена, как на телефоне, — а ящик всё равно открывается`() {
        withProbe { probe ->
            val caller = Executors.newSingleThreadExecutor { r -> Thread(r, CALLER) }.asCoroutineDispatcher()
            val inbox = HttpDropInbox({ probe.base() }, NetworkForbiddenOn(CALLER))

            val outcome = runBlocking(caller) { inbox.open() }

            assertTrue(
                "живой сервер обязан открыть ящик, а человек прочитал бы отказ: $outcome",
                outcome is DropOpen.Opened,
            )
            assertTrue("ящик открыт на сервере, а не сочинён: ${probe.hits}", "POST /u/open" in probe.hits)
            caller.close()
        }
    }

    /**
     * Сбой на самом устройстве при сборке запроса (#1077): человеку — слово из словаря, класс
     * сбоя — в журнал. «Сервер не ответил» здесь неправда — до сервера дело не дошло.
     */
    @Test
    fun `сбой на устройстве — человеку слово из словаря, класс сбоя уходит в журнал`() = runTest {
        withProbe { probe ->
            val logged = mutableListOf<Pair<String, Throwable>>()
            val inbox = HttpDropInbox(
                { probe.base() },
                NetworkForbiddenOn(thread = null),
                log = { what, e -> logged += what to e },
            )

            val refused = inbox.open()

            assertEquals(DropOpen.Refused(REQUEST_BROKE_TEXT), refused)
            assertTrue("до сервера запрос не дошёл: ${probe.hits}", probe.hits.isEmpty())
            val said = (refused as DropOpen.Refused).reason
            assertTrue("класс сбоя человеку не показывают: $said", "Exception" !in said)
            val (step, error) = logged.single()
            assertTrue("а в журнал он попадает целиком: $logged", error is IllegalStateException)
            assertTrue("и с ним — какой шаг сорвался: $logged", step.isNotBlank())
        }
    }

    /** Сервера на месте нет — вот это и есть «сервер не ответил», своим именем (#1077). */
    @Test
    fun `сервер молчит — отказ зовётся молчанием сервера, а не сбоем устройства`() = runTest {
        val outcome = inbox(NetworkAvailability { true }).open()

        assertEquals(DropOpen.Refused(NO_SERVER_TEXT), outcome)
    }

    /**
     * Ожидание файла отказывает тем же словарём (#1077). Прежде сюда уходило `e.message` — чужой
     * английский из недр сети («Failed to connect to /10.0.2.2»), и на компьютере он показывался
     * человеку как причина.
     */
    @Test
    fun `сервер молчит на ожидании — слово из словаря, а не сообщение сбоя`() = runTest {
        val outcome = inbox(NetworkAvailability { true })
            .await(DropInboxBox(BOX, "https://127.0.0.1:1/u/$BOX")) { "unused" }

        assertEquals(DropWait.Failed(NO_SERVER_TEXT), outcome)
    }

    /** Файл дошёл и лёг на устройство целиком — с тем именем и теми байтами, что прислал человек. */
    @Test
    fun `файл из ящика ложится на устройство — имя, тип и байты те самые`() = runTest {
        withProbe { probe ->
            val name = "смета за июль.pdf"
            val bytes = ByteArray(2048) { (it % 251).toByte() }
            probe.file = name to bytes
            val folder = File.createTempFile("point-drop", "-folder").apply { delete(); mkdirs() }
            val inbox = HttpDropInbox({ probe.base() }, { "pass" })

            val outcome = inbox.await(box(probe)) { asked -> File(folder, asked).absolutePath }

            val arrival = (outcome as DropWait.Arrived).arrival
            assertEquals(name, arrival.name)
            assertEquals("application/pdf", arrival.mime)
            assertEquals(FILE_ID, arrival.fileId)
            assertArrayEquals("байты те самые", bytes, File(arrival.path).readBytes())
            folder.deleteRecursively()
        }
    }

    /**
     * Файл сервер отдал, а на устройстве он не улёгся (#1077): места нет, каталога нет, диск занят.
     *
     * Отказ записи — тоже `IOException`, и под общим сторожем разговора с сервером он звался бы
     * молчанием сервера. Человек на компьютере читает эту причину прямо на экране приёма
     * (`ReceiveOnPc`: `failed = outcome.reason`) — и повторял бы приём по кругу вместо того,
     * чтобы освободить место. Это ровно та беда, ради которой заведена карточка: виновным
     * называют сервер, а указывает это не туда.
     */
    @Test
    fun `файл дошёл, а записать его некуда — беда зовётся устройством, а не молчанием сервера`() = runTest {
        withProbe { probe ->
            probe.file = "смета.pdf" to ByteArray(64) { 7 }
            val logged = mutableListOf<Pair<String, Throwable>>()
            val inbox = HttpDropInbox({ probe.base() }, { "pass" }, log = { what, e -> logged += what to e })

            // На месте каталога лежит обычный файл: положить в него что-либо не выйдет никогда.
            val busy = File.createTempFile("point-drop", ".busy").apply { deleteOnExit() }

            val outcome = inbox.await(box(probe)) { asked -> File(busy, asked).absolutePath }

            assertEquals(DropWait.Failed(SAVE_BROKE_TEXT), outcome)
            assertTrue("сервер отдал файл целиком — виноват не он: ${probe.hits}", "GET /u/$BOX/take" in probe.hits)
            val said = (outcome as DropWait.Failed).reason
            assertNotEquals("молчанием сервера это звать нельзя", NO_SERVER_TEXT, said)
            val (step, error) = logged.single()
            assertTrue("класс сбоя — в журнал, не человеку: $logged", error is java.io.IOException)
            assertTrue("и с ним — какой шаг сорвался: $step", step.isNotBlank())
        }
    }

    /**
     * Человек ушёл с экрана приёма — запрос кончается вместе с ним (#692).
     *
     * `ReceiveActivity.onDestroy` и `ReceiveOnPc.cancel` отменяют шаг приёма, а
     * `HttpURLConnection` об отмене не знает: файл продолжал качаться на чужом трафике до своего
     * предела. Здесь сервер держит ответ и сам его не отпускает — если приём кончился раньше,
     * значит соединение закрыл отказ, а не истёкший предел.
     */
    @Test
    fun `человек ушёл с экрана — приём кончается сразу, а не досиживает свой предел`() {
        withProbe { probe ->
            probe.file = "смета.pdf" to ByteArray(64) { 7 }
            val holds = CountDownLatch(1)
            probe.holds = holds
            val inbox = HttpDropInbox({ probe.base() }, { "pass" })

            runBlocking {
                val step = launch(Dispatchers.IO) { inbox.await(box(probe)) { "unused" } }

                assertTrue("сервер обязан получить запрос", probe.asked.await(5, TimeUnit.SECONDS))
                withTimeout(5_000) { step.cancelAndJoin() }

                assertEquals("приём кончился, пока сервер ещё держал ответ", 1L, holds.count)
            }
        }
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

    private fun box(probe: Probe) = DropInboxBox(BOX, probe.base() + "/u/" + BOX)

    private companion object {
        const val BOX = "aaaaaaaaaaaaaaaaaaaaaa"
        const val CALLER = "caller"
        const val FILE_ID = "file-1"
    }
}
