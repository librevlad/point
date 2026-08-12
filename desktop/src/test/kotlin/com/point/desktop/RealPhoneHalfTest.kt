package com.point.desktop

import com.point.core.flow.DeviceKeyStore
import com.point.core.flow.DeviceKeys
import com.point.core.flow.KeyStoreSecrets
import com.point.core.flow.LinkedPc
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PointAccount
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayPcTransport
import com.point.core.flow.RelayRpc
import com.point.core.flow.RelayRpcClient
import com.point.core.flow.decodePcFrame
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Телефонная половина связки — настоящая, а не собранная в тесте из отдельных функций (#819).
 *
 * #599 просил один сквозной тест, поднимающий оба ядра в одном процессе, и обосновывал это
 * скобкой владельца: «оба ядра — чистый Kotlin, железа не нужно». Скобка была права, а мешало
 * только место: разбор протокола лежал в `:data` (Android), хотя ни одного Android-импорта в
 * нём нет. После переезда в `:core:flow` телефон собирается там же, где компьютер.
 *
 * Здесь письмо строит и отправляет настоящий `RelayPcTransport` поверх настоящего
 * `RelayRpcClient` — тот самый код, которым пользуется телефон. Сквозной путь целиком, с
 * исполнением просьбы телефоном, остаётся за #817: телефон просьб компьютера пока не
 * исполняет.
 */
class RealPhoneHalfTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: ServerSocket
    private val letters = mutableListOf<ByteArray>()

    @Before
    fun up() {
        server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                runCatching { server.accept().use(::serve) }.onFailure { if (server.isClosed) return@Thread }
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun down() {
        runCatching { server.close() }
    }

    private val phoneKeys = DeviceKeys.generate()
    private val pcKeys = DeviceKeys.generate()

    private val phoneAccount = PointAccount(
        "d-phone", "pass-phone", "me@example.com", "Pixel", com.point.core.flow.DeviceKind.PHONE,
    )

    private val pc get() = LinkedPc("d-pc", "Домашний ПК", pcKeys.publicKey)

    /** Ровно та половина, которую собирает настоящий телефон. */
    private fun phone() = RelayPcTransport(
        RelayRpcClient(
            serverUrl = "http://127.0.0.1:${server.localPort}",
            account = { phoneAccount },
            secrets = KeyStoreSecrets(object : DeviceKeyStore { override fun keys() = phoneKeys }),
            waitSeconds = 1,
            pollMillis = 20,
        ),
    )

    @Test
    fun `письмо строит настоящая телефонная половина, и компьютер читает его своим ключом`() {
        // Сверяется тождество «что положили, то и доехало», а не конкретные слова: текст
        // принадлежит человеку, а не Point (#584).
        val name = "договор.txt"
        val text = "текст договора"
        val file = temp.newFile("dogovor.txt").apply { writeText(text) }
        val obj = PointObject(
            "o1", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT),
        )

        val outcome = runBlocking {
            phone().send(pc, obj, fileName = name, meta = emptyMap(), action = null)
        }

        // Компьютера на том конце нет — ответа телефон не дождался, и письмо ждёт в ящике.
        assertTrue("письмо не ушло: $outcome", outcome is PcSendOutcome.Parked)

        val letter = letters.lastOrNull()
        assertNotNull("сервер не получил письма от телефона", letter)

        // Читает его компьютер — своим ключом, своим разбором.
        val shared = DeviceKeys.sharedSecret(pcKeys.privateKey, phoneKeys.publicKey)!!
        val frame = decodePcFrame(RelayCrypto.open(shared, letter!!))

        assertEquals(RelayRpc.OBJECT, frame.meta[RelayRpc.KIND])
        assertEquals(name, frame.meta["name"])
        assertEquals(text, String(frame.bytes, Charsets.UTF_8))
    }

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
        answer(socket, method, target, body)
    }

    @Synchronized
    private fun answer(socket: Socket, method: String, target: String, body: ByteArray) {
        when {
            target.contains("/ack") -> socket.reply(200)
            method == "POST" -> {
                letters += body
                socket.reply(200, "b-${letters.size}".toByteArray())
            }

            // Ящик телефона пуст: отвечать некому — компьютер в этом тесте только читает.
            else -> socket.reply(204)
        }
    }

    private fun Socket.reply(code: Int, payload: ByteArray = ByteArray(0)) {
        val head = "HTTP/1.1 $code ok\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
        getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            if (payload.isNotEmpty()) write(payload)
            flush()
        }
    }
}
