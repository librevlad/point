package com.point.desktop

import com.point.core.flow.PcRemoteAction
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayRpc
import com.point.core.flow.RelayTls
import com.point.core.flow.decodePcFrame
import com.point.core.flow.decodePcCaps
import com.point.core.flow.encodePcCaps
import com.point.core.flow.encodePcFrame
import com.point.core.flow.encodePcOutbox
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Компьютер отвечает телефону через релей (#161).
 *
 * Раньше через релей ходило одно направление — объект с телефона на ПК. Спросить компьютер (что
 * умеешь? что для меня приготовил? отдай сделанное) можно было только по локальной сети, а её
 * между устройствами может не быть никогда: роутер с изоляцией клиентов разводит их даже дома.
 *
 * Слушает **свой** ящик (`rpc-to-pc`), чтобы не отбирать письма у поллера объектов: релей отдаёт
 * старейшее письмо любому спросившему, и разделение по адресу — единственное, что держит каналы
 * врозь.
 *
 * Ответы формируются тем же кодеком, что и ответы по локальной сети: второй правды о том, что
 * умеет компьютер, в проекте не заводится.
 */
class RelayRequestPoller(
    private val relayUrl: String,
    private val appSecret: String,
    private val token: String,
    private val remoteActions: () -> List<PcRemoteAction>,
    private val outbox: Outbox,
    private val onPhoneCaps: (List<PcRemoteAction>) -> Unit,
    private val runAction: (String, InboxItem) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (relayUrl.isBlank() || appSecret.isBlank() || running) return
        running = true
        thread = Thread({ loop() }, "point-relay-rpc").apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun loop() {
        val base = relayUrl.trimEnd('/')
        val toPc = RelayCrypto.mailboxId(token, RelayRpc.TO_PC)
        val toPhone = RelayCrypto.mailboxId(token, RelayRpc.TO_PHONE)
        var lastFailure: String? = null
        while (running) {
            val handled = runCatching { pollOnce(base, toPc, toPhone) }
                .onFailure { e ->
                    // Виден переход в отказ, а не каждый повтор: молчание хуже, спам тоже (#271).
                    val failure = "${e.javaClass.simpleName}: ${e.message}"
                    if (failure != lastFailure) log("relay rpc: сбой ($failure), повтор через ${BACKOFF_MS / 1000} с")
                    lastFailure = failure
                }
                .onSuccess {
                    if (lastFailure != null) log("relay rpc: связь восстановилась")
                    lastFailure = null
                }
                .getOrDefault(false)
            if (!handled) runCatching { Thread.sleep(if (running) BACKOFF_MS else 0) }
        }
    }

    private fun pollOnce(base: String, toPc: String, toPhone: String): Boolean {
        val c = open("$base/mbx/$toPc?wait=$WAIT_SECONDS", WAIT_SECONDS + 10)
        val (blob, blobId) = if (c.responseCode == 200) {
            c.inputStream.readBytes() to c.getHeaderField("X-Blob-Id")
        } else {
            null to null
        }
        c.disconnect()
        if (blob == null) return false

        // Подтверждаем ДО разбора: релей отдаёт старейшее письмо, и нерасшифрованный блоб иначе
        // навсегда встанет головой очереди и заморозит весь канал (урок #271).
        blobId?.let { ack(base, toPc, it) }

        val frame = runCatching { decodePcFrame(RelayCrypto.open(token, blob)) }.getOrNull() ?: return true
        val kind = frame.meta[RelayRpc.KIND] ?: return true
        val id = frame.meta[RelayRpc.ID].orEmpty()

        when (kind) {
            RelayRpc.CAPS -> reply(base, toPhone, id, encodePcCaps(remoteActions()).toByteArray())

            RelayRpc.OUTBOX -> reply(base, toPhone, id, encodePcOutbox(outbox.entries()).toByteArray())

            RelayRpc.FETCH -> {
                val entryId = frame.meta["id"]?.toIntOrNull()
                val file = entryId?.let { outbox.file(it) }
                if (file == null) {
                    reply(base, toPhone, id, ByteArray(0), mapOf("error" to "нет такого объекта"))
                } else {
                    reply(base, toPhone, id, file.readBytes(), mapOf("name" to file.name))
                }
            }

            RelayRpc.ACK -> {
                frame.meta["id"]?.toIntOrNull()?.let { runCatching { outbox.remove(it) } }
                reply(base, toPhone, id, ByteArray(0))
            }

            RelayRpc.PHONE_CAPS -> {
                runCatching { onPhoneCaps(decodePcCaps(String(frame.bytes, Charsets.UTF_8))) }
                reply(base, toPhone, id, ByteArray(0))
            }

            // Связать устройства, когда локальной сети нет: телефон уже знает токен из QR, и
            // спрашивает лишь «ты там?». Отдельного подтверждения человеком здесь нет намеренно —
            // токен из QR и есть согласие, показанное на экране компьютера.
            RelayRpc.PAIR -> reply(base, toPhone, id, ByteArray(0), mapOf("name" to hostLabel()))

            else -> Unit // чужой вид запроса: молчим, письмо уже подтверждено
        }
        return true
    }

    private fun hostLabel(): String = runCatching { java.net.InetAddress.getLocalHost().hostName }
        .getOrDefault("компьютер")

    private fun reply(
        base: String,
        toPhone: String,
        requestId: String,
        body: ByteArray,
        meta: Map<String, String> = emptyMap(),
    ) {
        val frame = encodePcFrame(
            meta + mapOf(RelayRpc.KIND to RelayRpc.REPLY, RelayRpc.ID to requestId),
            body,
        )
        val blob = RelayCrypto.seal(token, frame)
        runCatching {
            val c = open("$base/mbx/$toPhone", 30)
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(blob.size)
            c.outputStream.use { it.write(blob) }
            c.responseCode
            c.disconnect()
        }
    }

    private fun ack(base: String, mailbox: String, blobId: String) {
        runCatching {
            val c = open("$base/mbx/$mailbox/ack", 10)
            c.requestMethod = "POST"
            c.setRequestProperty("X-Blob-Id", blobId)
            c.doOutput = true
            c.outputStream.use { it.write(ByteArray(0)) }
            c.responseCode
            c.disconnect()
        }
    }

    private fun open(url: String, readSeconds: Int): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = RelayTls.socketFactory
            connectTimeout = CONNECT_MS
            readTimeout = readSeconds * 1000
            setRequestProperty("X-Point-App", appSecret)
        }

    private companion object {
        const val WAIT_SECONDS = 25
        const val BACKOFF_MS = 3_000L
        const val CONNECT_MS = 5_000
    }
}
