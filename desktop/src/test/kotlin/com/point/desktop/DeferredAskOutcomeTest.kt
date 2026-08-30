package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.DeviceKind
import com.point.core.flow.KeptLetters
import com.point.core.flow.LinkedPc
import com.point.core.flow.PcActionOutcome
import com.point.core.flow.PcExecFields
import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.PcResultFields
import com.point.core.flow.PointAccount
import com.point.core.flow.Realizer
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayRpc
import com.point.core.flow.decodePcFrame
import com.point.core.flow.decodePcOutbox
import com.point.core.flow.encodePcFrame
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Отложенная просьба — та, что пришла, пока компьютера не было дома (#1321).
 *
 * Человек на телефоне просит выключенный компьютер: «В буфер компьютера», «Напечатать на
 * компьютере». Телефон ждёт ответа считанные секунды и уходит со словами «Отправлено —
 * компьютер заберёт, когда включится». Компьютер включается, забирает письмо из ящика и
 * справляется за секунду — и прежде на этом всё кончалось: исход уезжал кадром ответа тому,
 * кто уже не спрашивает, а в очередь ПК→телефон не попадало ничего. Человек не узнавал ни
 * слова исхода, ни того, что вещь родилась.
 *
 * Правило #1073 — «любой исход просьбы соседу возвращается попросившему» — исключений не
 * имеет: секундомер решает, успеть ли ответить сразу, а не стоит ли говорить вообще.
 *
 * Путь проверяется целиком, от той двери, которой просьба входит: письмо из ящика → разбор →
 * работа компьютера → очередь, которую телефон и забирает. Сколько письмо пролежало, оно
 * говорит само: его имя начинается со времени, когда его положили.
 */
class DeferredAskOutcomeTest {

    @get:Rule val temp = TemporaryFolder()

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val phone = LinkedPc("phone-1", "Телефон")
    private val me = PointAccount("pc-1", "пропуск", "me@example.com", "Компьютер", DeviceKind.PC)

    private class Says(override val id: CapabilityId) : Capability {
        override val icon = "x"
        override val meta = CapabilityMeta()
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    }

    /** Быстрое умение компьютера: секунды на работу — столько же, сколько у живой просьбы. */
    private class Fast(
        override val capabilityId: CapabilityId,
        private val result: () -> ActionResult,
    ) : Realizer {
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult = result()
    }

    /** Компьютер собран так же, как в `Main.kt`: приёмная, состояние, ручка писем, очередь. */
    private class Pc(temp: TemporaryFolder, id: CapabilityId, result: () -> ActionResult) {
        val inbox = Inbox(temp.newFolder("inbox"))
        val outbox = Outbox(temp.newFolder("outbox"))

        val state = DesktopState(
            registry = DesktopRegistry(setOf(Says(id))),
            resolver = DesktopResolver(setOf(Fast(id, result))),
            clipboard = { },
            outbox = outbox,
        )

        val requests = RelayRequests(
            remoteActions = { emptyList() },
            outbox = outbox,
            onPhoneCaps = { },
            clipboardGet = { null },
            clipboardSet = { },
            onObject = { name, mime, meta, bytes, action, askedAgoMs ->
                val item = inbox.receive(name, mime, meta, bytes.inputStream())
                state.onReceived(item, ObjectSource.PHONE_RELAY)
                action?.let { state.runRemoteActionNow(it, item, askedAgoMs = askedAgoMs) }
            },
        )

        /** Очередь глазами телефона: он спрашивает её тем же письмом и читает тем же разбором. */
        fun queueAsPhoneSeesIt(): List<PcOutboxEntry> =
            decodePcOutbox(
                String(requests.answer(RelayRpc.OUTBOX, emptyMap(), ByteArray(0))!!.body, Charsets.UTF_8),
            )
    }

    /** Ящик на сервере: одно письмо, положенное столько-то назад. */
    private class Box(letter: ByteArray, private val letterId: String) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val posted = mutableListOf<ByteArray>()
        private var waiting: ByteArray? = letter

        fun base(): String = "http://127.0.0.1:" + server.address.port

        fun start() {
            server.createContext("/") { ex -> handle(ex) }
            server.executor = null
            server.start()
        }

        fun stop() = server.stop(0)

        private fun handle(ex: HttpExchange) {
            val body = ex.requestBody.readBytes()
            val inBox = waiting
            when {
                ex.requestURI.path.endsWith("/ack") -> {
                    waiting = null
                    ex.sendResponseHeaders(200, -1)
                }

                ex.requestMethod == "POST" -> {
                    posted += body
                    ex.sendResponseHeaders(200, -1)
                }

                inBox == null -> ex.sendResponseHeaders(204, -1)
                else -> {
                    ex.responseHeaders.add("X-Blob-Id", letterId)
                    ex.sendResponseHeaders(200, inBox.size.toLong())
                    ex.responseBody.use { it.write(inBox) }
                }
            }
            ex.close()
        }
    }

    /** Имя письма, положенного столько-то назад: сервер начинает имя со времени (#1321). */
    private fun letterPut(agoMs: Long) =
        "%020d-aa".format((System.currentTimeMillis() - agoMs) * 1_000_000)

    /** Просьба телефона — то же письмо, каким её присылает настоящий телефон. */
    private fun asksFor(action: String, name: String, text: String): ByteArray = RelayCrypto.seal(
        key,
        encodePcFrame(
            mapOf(
                RelayRpc.KIND to RelayRpc.OBJECT,
                RelayRpc.ID to "письмо-1",
                "name" to name,
                "mime" to "text/plain",
                "action" to action,

                // Дом объекта — на телефоне: исход обязан вернуться туда, откуда просили.
                com.point.core.flow.META_ORIGIN_ID to HOME,
            ),
            text.toByteArray(Charsets.UTF_8),
        ),
    )

    private fun poller(box: Box, pc: Pc) = RelayPoller(
        serverUrl = box.base(),
        account = { me },
        peers = { listOf(phone) },
        secrets = { key },
        requests = pc.requests,
        letters = KeptLetters(temp.newFolder()),
    )

    private fun waitUntil(timeoutMs: Long = 5_000, what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!what() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("не дождались условия за ${timeoutMs}мс", what())
    }

    private fun <T> withBox(letter: ByteArray, letterId: String, block: (Box) -> T): T {
        val box = Box(letter, letterId)
        box.start()
        return try {
            block(box)
        } finally {
            box.stop()
        }
    }

    @Test
    fun `просьба, пролежавшая в ящике, отдаёт исход очередью — телефон о нём узнаёт`() {
        val said = "В буфер компьютера — готово"
        val pc = Pc(temp, ID) { ActionResult.Done(said) }

        withBox(asksFor(ID.value, "заметка.txt", "текст"), letterPut(agoMs = HOUR)) { box ->
            poller(box, pc).once()

            waitUntil { pc.queueAsPhoneSeesIt().isNotEmpty() }
            val entry = pc.queueAsPhoneSeesIt().single()
            assertEquals(
                "исход отложенной просьбы не доехал до телефона",
                PcActionOutcome.Done(said),
                PcResultFields.outcomeOf(entry.meta),
            )
            assertEquals("исход приехал не к тому объекту", HOME, entry.meta[PcExecFields.HOME])
        }
    }

    @Test
    fun `вещь, рождённая по отложенной просьбе, ложится в очередь, а не пропадает вместе с ответом`() {
        val born = File(temp.newFolder("сделано"), "перевод.txt").apply { writeText("готово") }
        val pc = Pc(temp, ID) {
            ActionResult.Success(
                ResultObject(
                    ObjectKind.TEXT,
                    "text/plain",
                    ScratchRef(born.absolutePath),
                    mapOf("name" to born.name),
                ),
            )
        }

        withBox(asksFor(ID.value, "заметка.txt", "текст"), letterPut(agoMs = HOUR)) { box ->
            poller(box, pc).once()

            waitUntil { pc.queueAsPhoneSeesIt().isNotEmpty() }
            val entry = pc.queueAsPhoneSeesIt().single()
            assertEquals("телефон не узнал, что за вещь ему положили", born.name, entry.meta["name"])
            assertNotNull("вещь приехала без файла — забирать нечего", pc.outbox.file(entry.id))
        }
    }

    /**
     * Обратная сторона того же правила: у исхода одна дорога, а не две.
     *
     * Живую просьбу телефон ждёт, и срочный ответ — та самая дорога; положить исход ещё и в
     * очередь значило бы прислать его человеку дважды: словами и второй копией вещи.
     */
    @Test
    fun `свежая просьба отвечается сразу, и в очередь второй копией не ложится`() {
        val born = File(temp.newFolder("сделано"), "перевод.txt").apply { writeText("готово") }
        val pc = Pc(temp, ID) {
            ActionResult.Success(
                ResultObject(
                    ObjectKind.TEXT,
                    "text/plain",
                    ScratchRef(born.absolutePath),
                    mapOf("name" to born.name),
                ),
            )
        }

        withBox(asksFor(ID.value, "заметка.txt", "текст"), letterPut(agoMs = 0)) { box ->
            poller(box, pc).once()

            val reply = decodePcFrame(RelayCrypto.open(key, box.posted.single()))
            assertTrue("телефон не получил ответа на свою просьбу", PcResultFields.hasObject(reply.meta))
            assertTrue("тот же исход приехал бы человеку вторым разом", pc.queueAsPhoneSeesIt().isEmpty())
        }
    }

    private companion object {

        val ID = CapabilityId("в-буфер")

        /** Как объект зовётся дома, на телефоне: туда и возвращается исход. */
        const val HOME = "phone-obj"

        /** Компьютера не было дома час — телефон ушёл задолго до того, как он включился. */
        const val HOUR = 60L * 60 * 1000
    }
}
