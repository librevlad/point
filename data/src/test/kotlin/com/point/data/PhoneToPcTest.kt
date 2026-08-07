package com.point.data

import com.point.core.flow.ClipPull
import com.point.core.flow.ClipPush
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.DeviceKeyStore
import com.point.core.flow.DeviceKeys
import com.point.core.flow.KeyStoreSecrets
import com.point.core.flow.LinkedPc
import com.point.core.flow.PcActionOutcome
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcUnreachable
import com.point.core.flow.PointAccount
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayRpc
import com.point.core.flow.clipMeta
import com.point.core.flow.decodePcFrame
import com.point.core.flow.encodePcFrame
import com.point.core.flow.encodePcReceiveReply
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneToPcTest {

    private lateinit var server: ServerSocket
    private lateinit var worker: Thread
    private val boxes = mutableMapOf<String, MutableList<Pair<String, ByteArray>>>()

    private val passes = mapOf("pass-phone" to "d-phone", "pass-pc" to "d-pc")

    private val known = mutableSetOf("d-phone", "d-pc")

    @Volatile private var refuseAll = false
    @Volatile private var tooBig = false

    @Before
    fun up() {
        server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        worker = Thread {
            while (!server.isClosed) {
                runCatching { server.accept().use { serve(it) } }
                    .onFailure { if (server.isClosed) return@Thread }
            }
        }.apply { isDaemon = true }.also { it.start() }
    }

    @After
    fun down() {
        runCatching { server.close() }
    }

    private fun base() = "http://127.0.0.1:${server.localPort}"

    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val request = StringBuilder()
        while (!request.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            request.append(b.toInt().toChar())
        }
        val lines = request.toString().split("\r\n")
        val (method, target) = lines[0].split(" ").let { it[0] to it[1] }
        val headers = lines.drop(1).filter { it.contains(':') }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val body = headers["content-length"]?.toIntOrNull()?.let { size ->
            ByteArray(size).also { buffer ->
                var read = 0
                while (read < size) {
                    val n = input.read(buffer, read, size - read)
                    if (n < 0) break
                    read += n
                }
            }
        } ?: ByteArray(0)
        answer(socket, method, target, headers, body)
    }

    @Synchronized
    private fun answer(socket: Socket, method: String, target: String, headers: Map<String, String>, body: ByteArray) {
        val me = passes[headers["authorization"]?.removePrefix("Bearer ")]
        if (me == null || refuseAll) return socket.reply(401)
        val path = target.substringBefore('?').removePrefix("/mbx/")
        val device = path.substringBefore('/')
        if (device !in known) return socket.reply(404)
        val box = boxes.getOrPut(device) { mutableListOf() }
        when {
            path.endsWith("/ack") -> {
                val blob = target.substringAfter("blob=", "")
                box.removeAll { it.first == blob }
                socket.reply(200)
            }
            method == "POST" -> {
                if (tooBig) return socket.reply(507)
                val id = "b-" + System.nanoTime()
                box += id to body
                socket.reply(200, id.toByteArray())
            }
            else -> {
                val letter = box.firstOrNull() ?: return socket.reply(204)
                socket.reply(200, letter.second, mapOf("X-Blob-Id" to letter.first))
            }
        }
    }

    private fun Socket.reply(code: Int, body: ByteArray = ByteArray(0), extra: Map<String, String> = emptyMap()) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(" ok\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            extra.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            if (body.isNotEmpty()) write(body)
            flush()
        }
    }

    @Synchronized
    private fun letterFor(device: String): ByteArray? = boxes[device]?.firstOrNull()?.second

    @Synchronized
    private fun takeLetterFor(device: String): ByteArray? = boxes[device]?.removeFirstOrNull()?.second

    @Synchronized
    private fun putLetterFor(device: String, blob: ByteArray) {
        boxes.getOrPut(device) { mutableListOf() } += "reply-${System.nanoTime()}" to blob
    }

    private val phoneKeys = DeviceKeys.generate()
    private val pcKeys = DeviceKeys.generate()

    private val phoneAccount = PointAccount(
        "d-phone", "pass-phone", "me@example.com", "Pixel", com.point.core.flow.DeviceKind.PHONE,
    )

    private val pc get() = LinkedPc("d-pc", "Домашний ПК", pcKeys.publicKey)

    private fun rpc(waitSeconds: Int = 5) = RelayRpcClient(
        serverUrl = base(),
        account = { phoneAccount },
        secrets = KeyStoreSecrets(object : DeviceKeyStore { override fun keys() = phoneKeys }),
        waitSeconds = waitSeconds,
        pollMillis = 50,
    )

    private fun phone(waitSeconds: Int = 5) = RelayPcTransport(rpc(waitSeconds))

    private fun clipboard(waitSeconds: Int = 5) = RelayPcClipboardSync(rpc(waitSeconds))

    private fun pcAnswers(reply: (kind: String, meta: Map<String, String>, bytes: ByteArray) -> Pair<Map<String, String>, ByteArray>?) {
        val key = DeviceKeys.sharedSecret(pcKeys.privateKey, phoneKeys.publicKey)!!
        val letter = takeLetterFor("d-pc") ?: return
        val frame = decodePcFrame(RelayCrypto.open(key, letter))
        val answer = reply(frame.meta[RelayRpc.KIND].orEmpty(), frame.meta, frame.bytes) ?: return
        putLetterFor(
            "d-phone",
            RelayCrypto.seal(
                key,
                encodePcFrame(
                    answer.first + mapOf(
                        RelayRpc.KIND to RelayRpc.REPLY,
                        RelayRpc.ID to frame.meta[RelayRpc.ID].orEmpty(),
                    ),
                    answer.second,
                ),
            ),
        )
    }

    private fun objectOnPhone(text: String = "чек"): PointObject {
        val file = File.createTempFile("point-", ".txt").apply { writeText(text); deleteOnExit() }
        return PointObject(
            "o", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT),
            mapOf("name" to "чек.txt"),
        )
    }

    private fun <T> onPhone(block: suspend () -> T): Waiting<T> {
        val holder = arrayOfNulls<Result<T>>(1)
        val thread = Thread { holder[0] = runCatching { runBlocking { block() } } }
        thread.start()
        return Waiting(thread) { holder[0]!!.getOrThrow() }
    }

    private class Waiting<T>(private val thread: Thread, private val value: () -> T) {
        fun await(): T {
            thread.join(20_000)
            return value()
        }
    }

    private fun waitForLetter(device: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (letterFor(device) == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("письмо не доехало до ящика $device", letterFor(device) != null)
    }

    @Test
    fun `объект доезжает до компьютера, и общей сети для этого не нужно`() {
        var received: Pair<String, String>? = null
        val sending = onPhone { phone().send(pc, objectOnPhone(), "чек.txt", mapOf("entity.money" to "693,40")) }
        waitForLetter("d-pc")
        pcAnswers { kind, meta, bytes ->
            assertEquals(RelayRpc.OBJECT, kind)
            received = meta["name"].orEmpty() to String(bytes, Charsets.UTF_8)
            assertEquals("понимание об объекте едет вместе с ним", "693,40", meta["entity.money"])
            emptyMap<String, String>() to encodePcReceiveReply(null).toByteArray(Charsets.UTF_8)
        }

        val outcome = sending.await()

        assertTrue(outcome is PcSendOutcome.Sent)
        assertEquals("чек.txt" to "чек", received)
    }

    @Test
    fun `сервер везёт шифротекст — ни имени файла, ни содержимого он не видит`() {
        val sending = onPhone { phone(waitSeconds = 1).send(pc, objectOnPhone("ведомость"), "ведомость.txt", emptyMap()) }
        waitForLetter("d-pc")

        val onServer = String(letterFor("d-pc")!!, Charsets.ISO_8859_1)
        assertTrue("содержимое видно серверу — обещание нарушено", "ведомость" !in onServer)

        sending.await()
    }

    @Test
    fun `компьютер назвал исход — телефон повторяет его слова, а не своё «готово»`() {
        val sending = onPhone { phone().send(pc, objectOnPhone(), "смета.txt", emptyMap(), action = "pc-print") }
        waitForLetter("d-pc")
        pcAnswers { _, meta, _ ->
            assertEquals("pc-print", meta["action"])
            val done = PcActionOutcome.Done("В очереди «HP LaserJet» · проверьте принтер")
            emptyMap<String, String>() to encodePcReceiveReply(done).toByteArray(Charsets.UTF_8)
        }

        val outcome = sending.await() as PcSendOutcome.Sent

        assertEquals(PcActionOutcome.Done("В очереди «HP LaserJet» · проверьте принтер"), outcome.action)
    }

    @Test
    fun `общий буфер едет той же дорогой, и пустой буфер — это ответ`() {
        val pushing = onPhone { clipboard().push(pc, ClipboardPayload.ofText("+380671234567")) }
        waitForLetter("d-pc")
        var crossed: String? = null
        pcAnswers { kind, _, bytes ->
            assertEquals(RelayRpc.CLIP_PUSH, kind)
            crossed = String(bytes, Charsets.UTF_8)
            emptyMap<String, String>() to ByteArray(0)
        }
        assertEquals(ClipPush.Sent, pushing.await())
        assertEquals("+380671234567", crossed)

        val pulling = onPhone { clipboard().pull(pc) }
        waitForLetter("d-pc")
        pcAnswers { _, _, _ -> emptyMap<String, String>() to ByteArray(0) }
        assertEquals("«у меня пусто» — это ответ, а не молчание", ClipPull.Empty, pulling.await())

        val again = onPhone { clipboard().pull(pc) }
        waitForLetter("d-pc")
        val onPc = ClipboardPayload.ofText("ответ компьютера")
        pcAnswers { _, _, _ -> clipMeta(onPc) to onPc.bytes }
        assertEquals("ответ компьютера", (again.await() as ClipPull.Got).payload.text())
    }

    @Test
    fun `компьютера нет в круге — сервер не знает такого ящика`() {
        known.remove("d-pc")

        val outcome = runBlocking { phone(waitSeconds = 1).send(pc, objectOnPhone(), "чек.txt", emptyMap()) }

        assertEquals(PcUnreachable.NOT_IN_CIRCLE, (outcome as PcSendOutcome.Unreachable).why)
    }

    @Test
    fun `компьютер в круге, но не запущен — письмо лежит, забирать его некому`() {

        val outcome = runBlocking { phone(waitSeconds = 1).send(pc, objectOnPhone(), "чек.txt", emptyMap()) }

        assertEquals(PcUnreachable.PC_ASLEEP, (outcome as PcSendOutcome.Unreachable).why)
        assertTrue("письмо всё-таки легло в ящик", letterFor("d-pc") != null)
    }

    @Test
    fun `сервер молчит — про компьютер мы ничего не утверждаем`() {
        server.close()

        val outcome = runBlocking { phone(waitSeconds = 1).send(pc, objectOnPhone(), "чек.txt", emptyMap()) }

        assertEquals(PcUnreachable.SERVER_SILENT, (outcome as PcSendOutcome.Unreachable).why)
    }

    @Test
    fun `место на сервере кончилось — это про размер, а не про доступность`() {
        tooBig = true

        val outcome = runBlocking { phone(waitSeconds = 1).send(pc, objectOnPhone(), "чек.txt", emptyMap()) }

        assertEquals(PcUnreachable.TOO_BIG, (outcome as PcSendOutcome.Unreachable).why)
    }

    @Test
    fun `устройство отключили от аккаунта — это отдельный ответ`() {
        refuseAll = true

        val outcome = runBlocking { phone(waitSeconds = 1).send(pc, objectOnPhone(), "чек.txt", emptyMap()) }

        assertEquals(PcSendOutcome.Rejected, outcome)
    }

    @Test
    fun `компьютер ещё не объявил ключ — писать ему нечем, и открытым текстом мы не пишем`() {
        val mute = LinkedPc("d-pc", "Домашний ПК", key = "")

        val outcome = runBlocking { phone(waitSeconds = 1).send(mute, objectOnPhone(), "чек.txt", emptyMap()) }

        assertEquals(PcUnreachable.NOT_IN_CIRCLE, (outcome as PcSendOutcome.Unreachable).why)
        assertNull("в ящик не легло ничего", letterFor("d-pc"))
    }
}
