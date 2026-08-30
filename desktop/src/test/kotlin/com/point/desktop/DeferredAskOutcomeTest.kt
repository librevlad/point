package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.DeviceKind
import com.point.core.flow.KeptLetters
import com.point.core.flow.LinkedPc
import com.point.core.flow.PC_ANSWER_HEARD_MS
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
import com.point.core.flow.decodePcReceiveReply
import com.point.core.flow.encodePcFrame
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
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
 * работа компьютера → очередь, которую телефон и забирает. Сколько письмо пролежало, говорят
 * часы ящика: его имя начинается со времени, и тем же временем помечен ответ. Часы компьютера
 * здесь нарочно другие — на час впереди, как на машине, которая проснулась и ещё ни с кем не
 * сверялась.
 */
class DeferredAskOutcomeTest {

    @get:Rule val temp = TemporaryFolder()

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val phone = LinkedPc("phone-1", "Телефон")
    private val me = PointAccount("pc-1", "пропуск", "me@example.com", "Компьютер", DeviceKind.PC)

    /** Часы ящика. Своих часов у компьютера с ними не сходится — так оно и в жизни. */
    private val boxNowMs = System.currentTimeMillis() - PC_CLOCK_AHEAD

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
    private class Pc(
        temp: TemporaryFolder,
        id: CapabilityId,

        /**
         * Часы компьютера. Стоячие — когда проверяется сама граница: работа тогда занимает
         * ровно нуль, и возраст просьбы к готовому ответу равен тому, сколько она пролежала.
         */
        clock: Clock = Clock { System.currentTimeMillis() },
        result: () -> ActionResult,
    ) {
        val inbox = Inbox(temp.newFolder("inbox"))
        val outbox = Outbox(temp.newFolder("outbox"))

        val state = DesktopState(
            registry = DesktopRegistry(setOf(Says(id))),
            resolver = DesktopResolver(setOf(Fast(id, result))),
            clipboard = { },
            outbox = outbox,
            clock = clock,
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

    /**
     * Ящик на сервере: одно письмо, положенное столько-то назад по ЕГО часам.
     *
     * Отвечает сокетом, а не готовым сервером JDK: тому время ответа не переписать, а здесь
     * вся суть в том, что время письма и время ответа — одни часы, и это не часы компьютера.
     */
    private class Box(letter: ByteArray, private val letterId: String, private val nowMs: Long) {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val thread = Thread({ serve() }, "ящик-теста").apply { isDaemon = true }

        val posted = CopyOnWriteArrayList<ByteArray>()

        @Volatile private var waiting: ByteArray? = letter

        fun base(): String = "http://127.0.0.1:" + server.localPort

        fun start() = thread.start()

        fun stop() {
            runCatching { server.close() }
        }

        private fun serve() {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: return
                runCatching { client.use { answer(it) } }
            }
        }

        private fun answer(client: Socket) {
            val input = client.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) return
                head.append(next.toChar())
            }
            val request = head.toString()
            val length = LENGTH.find(request)?.groupValues?.get(1)?.toInt() ?: 0
            val body = ByteArray(length)
            var got = 0
            while (got < length) {
                val n = input.read(body, got, length - got)
                if (n < 0) break
                got += n
            }

            val path = request.substringAfter(' ').substringBefore(' ').substringBefore('?')
            val letter = waiting
            val out = client.getOutputStream()
            when {
                path.endsWith("/ack") -> {
                    waiting = null
                    out.write(said(200, 0))
                }

                request.startsWith("POST") -> {
                    posted += body
                    out.write(said(200, 0))
                }

                letter == null -> out.write(said(204, null))
                else -> {
                    out.write(said(200, letter.size, letterId))
                    out.write(letter)
                }
            }
            out.flush()
        }

        /** Ответ ящика всегда называет своё время — по нему получатель и судит о возрасте письма. */
        private fun said(code: Int, length: Int?, blobId: String? = null): ByteArray = buildString {
            append("HTTP/1.1 ").append(code).append(" ok\r\n")
            append("Date: ").append(HTTP_TIME.format(Instant.ofEpochMilli(nowMs))).append("\r\n")
            blobId?.let { append("X-Blob-Id: ").append(it).append("\r\n") }
            length?.let { append("Content-Length: ").append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)

        private companion object {

            val LENGTH = Regex("(?i)content-length: *(\\d+)")

            val HTTP_TIME: DateTimeFormatter =
                DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneId.of("GMT"))
        }
    }

    /** Имя письма, положенного столько-то назад: сервер начинает имя своим временем (#1321). */
    private fun letterPut(agoMs: Long) = "%020d-aa".format((boxNowMs - agoMs) * 1_000_000)

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

    private fun poller(box: Box, pc: Pc) = poller(box.base(), KeptLetters(temp.newFolder()), pc)

    private fun poller(serverUrl: String, letters: KeptLetters, pc: Pc) = RelayPoller(
        serverUrl = serverUrl,
        account = { me },
        peers = { listOf(phone) },
        secrets = { key },
        requests = pc.requests,
        letters = letters,
    )

    /** Адрес, по которому никто не отвечает — связи с ящиком в этот миг нет. */
    private fun deadBox(): String =
        ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { "http://127.0.0.1:" + it.localPort }

    private fun waitUntil(timeoutMs: Long = 5_000, what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!what() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("не дождались условия за ${timeoutMs}мс", what())
    }

    private fun <T> withBox(letter: ByteArray, letterId: String, block: (Box) -> T): T {
        val box = Box(letter, letterId, boxNowMs)
        box.start()
        return try {
            block(box)
        } finally {
            box.stop()
        }
    }

    /** Что телефон услышал срочным ответом: исход у него приезжает телом письма. */
    private fun heardBy(box: Box): PcActionOutcome? {
        val reply = decodePcFrame(RelayCrypto.open(key, box.posted.single()))
        return decodePcReceiveReply(String(reply.bytes, Charsets.UTF_8))
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
        val pc = Pc(temp, ID) { madeOf(born) }

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
        val pc = Pc(temp, ID) { madeOf(born) }

        withBox(asksFor(ID.value, "заметка.txt", "текст"), letterPut(agoMs = 0)) { box ->
            poller(box, pc).once()

            val reply = decodePcFrame(RelayCrypto.open(key, box.posted.single()))
            assertTrue("телефон не получил ответа на свою просьбу", PcResultFields.hasObject(reply.meta))
            assertTrue("тот же исход приехал бы человеку вторым разом", pc.queueAsPhoneSeesIt().isEmpty())
        }
    }

    /**
     * Часы компьютера в счёт возраста не входят вовсе (#1321).
     *
     * Они уходят вперёд сами: машина без свежей сверки, пробуждение после сна, виртуалка. Пока
     * возраст письма считался вычитанием серверного времени из своего, ушедшие вперёд часы
     * молча объявляли отложенной ЛЮБУЮ просьбу — человек, стоящий перед экраном, вместо слов
     * исхода получал «Компьютер ещё работает», а родившийся файл вместо прямого ответа уезжал
     * в список «с компьютера». Причина была невидима: на экране всё выглядело как обычно.
     */
    @Test
    fun `часы компьютера, ушедшие вперёд, не превращают живую просьбу в отложенную`() {
        val said = "В буфер компьютера — готово"
        val pc = Pc(temp, ID) { ActionResult.Done(said) }

        withBox(asksFor(ID.value, "заметка.txt", "текст"), letterPut(agoMs = 0)) { box ->
            poller(box, pc).once()

            assertEquals(
                "человек у экрана не услышал исхода — часы компьютера сочли его просьбу лежалой",
                PcActionOutcome.Done(said),
                heardBy(box),
            )
            assertTrue("исход приехал бы человеку вторым разом", pc.queueAsPhoneSeesIt().isEmpty())
        }
    }

    /**
     * Граница со стороны живого: попросивший ещё слушает — значит слышит сам исход.
     *
     * «Компьютер ещё работает» — слово честное, пока работа и правда идёт. Сказанное про
     * законченную работу, оно ложь: человек читает про работу, которой нет, и только через
     * несколько секунд очередь приносит настоящие слова.
     */
    @Test
    fun `просьба, которую ещё ждут, получает слова исхода, а не обещание работы`() {
        val said = "В буфер компьютера — готово"
        val pc = Pc(temp, ID, clock = Clock { STOPPED }) { ActionResult.Done(said) }

        withBox(asksFor(ID.value, "заметка.txt", "текст"), letterPut(PC_ANSWER_HEARD_MS - SECOND)) { box ->
            poller(box, pc).once()

            assertEquals(
                "вместо готового исхода человеку обещали работу, которая уже кончилась",
                PcActionOutcome.Done(said),
                heardBy(box),
            )
        }
    }

    /**
     * Граница со стороны ушедшего: секундой позже порога слушать уже некому — исход едет
     * очередью. Порог назван один раз и выведен из срока ожидания; сросшись с бюджетом
     * ответа, он переворачивался бы от правки чужого числа, и никто бы этого не заметил.
     */
    @Test
    fun `просьбу, которую ждать перестали, срочным ответом не догоняют`() {
        val said = "В буфер компьютера — готово"
        val pc = Pc(temp, ID, clock = Clock { STOPPED }) { ActionResult.Done(said) }

        withBox(asksFor(ID.value, "заметка.txt", "текст"), letterPut(PC_ANSWER_HEARD_MS + SECOND)) { box ->
            poller(box, pc).once()

            waitUntil { pc.queueAsPhoneSeesIt().isNotEmpty() }
            assertEquals(
                "исход не поехал очередью — попросившего уже нет, и услышать его некому",
                PcActionOutcome.Done(said),
                PcResultFields.outcomeOf(pc.queueAsPhoneSeesIt().single().meta),
            )
        }
    }

    /**
     * Возраст, которого не знает никто, — не нуль.
     *
     * Прошлый запуск успел сохранить письмо на диск и не успел его разобрать (так и задумано
     * в #680: сначала на диск, потом подтверждение). Компьютер запускается снова, а связи с
     * ящиком в этот миг нет: письмо на диске есть, а спросить, сколько оно там пролежало,
     * не у кого — часы ящика приходят его же ответом.
     *
     * Пока такое письмо считалось только что положенным, исход уходил срочным кадром — а
     * кадр на мёртвой сети не уходит никуда, и разобранное письмо тут же стиралось: от
     * исхода не оставалось ничего. Очередь ПК→телефон лежит на диске самого компьютера и
     * дождётся и телефона, и сети.
     */
    @Test
    fun `исход просьбы, чьего возраста не знает никто, ложится в очередь, а не пропадает`() {
        val said = "В буфер компьютера — готово"
        val pc = Pc(temp, ID) { ActionResult.Done(said) }

        // Так письмо и лежит после прошлого запуска: сохранено, но не разобрано. Имя его
        // говорит «положено только что» — и всё равно этого мало, пока часов ящика нет.
        val kept = KeptLetters(temp.newFolder())
        kept.keep(letterPut(agoMs = 0), asksFor(ID.value, "заметка.txt", "текст"))

        poller(deadBox(), kept, pc).once()

        waitUntil { pc.queueAsPhoneSeesIt().isNotEmpty() }
        assertEquals(
            "исход пропал совсем — его отдали срочным ответом, которому некуда ехать",
            PcActionOutcome.Done(said),
            PcResultFields.outcomeOf(pc.queueAsPhoneSeesIt().single().meta),
        )
    }

    private fun madeOf(born: File) = ActionResult.Success(
        ResultObject(
            ObjectKind.TEXT,
            "text/plain",
            ScratchRef(born.absolutePath),
            mapOf("name" to born.name),
        ),
    )

    private companion object {

        val ID = CapabilityId("в-буфер")

        /** Как объект зовётся дома, на телефоне: туда и возвращается исход. */
        const val HOME = "phone-obj"

        const val SECOND = 1_000L

        /** Компьютера не было дома час — телефон ушёл задолго до того, как он включился. */
        const val HOUR = 60L * 60 * 1000

        /** Насколько часы компьютера убежали вперёд от серверных. */
        const val PC_CLOCK_AHEAD = HOUR

        /** Стоячие часы компьютера: работа занимает ровно нуль, и граница проверяется точно. */
        const val STOPPED = 1_756_000_000_000L
    }
}
