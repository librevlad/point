package com.point.data

import com.point.core.flow.PcPairing
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayRpc
import com.point.core.flow.RelayTls
import com.point.core.flow.decodePcFrame
import com.point.core.flow.encodePcFrame
import com.point.core.flow.isOurReply
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Спросить компьютер через релей и дождаться ответа (#161).
 *
 * Релей — слепой ящик, поэтому «запрос» и «ответ» здесь два письма: телефон кладёт вопрос в ящик
 * «на ПК» и ждёт свой ответ в ящике «на телефон». Приём взят у общего буфера, где он уже год
 * работает, — вместе со всеми его уроками: дренаж протухшего, `reqId`, дедлайн, гейт размера до
 * сети.
 *
 * Зачем это нужно вообще: без него через релей ходила ровно одна операция — отправка объекта на
 * ПК. Всё остальное (что компьютер умеет, что он приготовил, забрать сделанное) требовало общей
 * локальной сети, которой у человека может не быть никогда — например, когда роутер разделяет
 * клиентов.
 */
class RelayRpcClient(
    /** Пропуск устройства в аккаунте (#473): общего пароля приложения больше нет, у каждого свой. */
    private val pass: () -> String?,
    private val waitSeconds: Int = 25,
    private val connectTimeoutMs: Int = 5_000,
) {

    /** Ответ компьютера: мета и байты (у большинства запросов байт нет). */
    class Reply(val meta: Map<String, String>, val body: ByteArray)

    /**
     * Задать вопрос и дождаться ответа; `null` — компьютер не ответил (или релея нет).
     *
     * Молчание намеренно не отличается от отказа: для вызывающего это одинаково «через релей не
     * вышло», и решение, что показать человеку, принимается выше — там, где известно, чего он
     * просил.
     */
    suspend fun ask(
        pairing: PcPairing,
        kind: String,
        meta: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): Reply? = withContext(Dispatchers.IO) {
        val base = pairing.relay?.trimEnd('/')?.takeIf { it.isNotBlank() && !pass().isNullOrBlank() }
            ?: return@withContext null

        val toPc = RelayCrypto.mailboxId(pairing.token, RelayRpc.TO_PC)
        val toPhone = RelayCrypto.mailboxId(pairing.token, RelayRpc.TO_PHONE)
        val requestId = UUID.randomUUID().toString()

        drain(base, toPhone) // чужие ответы прошлых попыток — чтобы ждать только свой

        val request = RelayCrypto.seal(
            pairing.token,
            encodePcFrame(meta + mapOf(RelayRpc.KIND to kind, RelayRpc.ID to requestId), body),
        )
        // Гейт размера ДО сети: релей режет по Content-Length, и запись в поток умирает раньше,
        // чем читается код ответа, — «слишком большой» превратился бы в «недоступен».
        if (request.size > MAX_RELAY_BLOB) return@withContext null
        if (post(base, toPc, request) != 200) return@withContext null

        val deadline = System.nanoTime() + waitSeconds * 1_000_000_000L
        while (true) {
            coroutineContext.ensureActive()
            val remaining = ((deadline - System.nanoTime()) / 1_000_000_000L).toInt()
            if (remaining <= 0) return@withContext null

            val polled = poll(base, toPhone, remaining) ?: return@withContext null
            val frame = runCatching { decodePcFrame(RelayCrypto.open(pairing.token, polled)) }.getOrNull()
                ?: continue // мусор в публичном ящике — уже подтверждён, ждём дальше
            if (!isOurReply(frame.meta, requestId)) continue
            return@withContext Reply(frame.meta, frame.bytes)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private fun post(base: String, mailbox: String, blob: ByteArray): Int = runCatching {
        val c = open("$base/mbx/$mailbox", waitSeconds + 10)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(blob.size)
        c.outputStream.use { it.write(blob) }
        val code = c.responseCode
        c.disconnect()
        code
    }.getOrElse { -1 }

    /** Один долгий опрос: запечатанный блоб или `null`. Забранное сразу подтверждается — то, чем
     *  мы не смогли воспользоваться, обязано покинуть очередь, иначе оно вернётся вечным эхом. */
    private fun poll(base: String, mailbox: String, wait: Int): ByteArray? = runCatching {
        val c = open("$base/mbx/$mailbox?wait=$wait", wait + 10)
        val code = c.responseCode
        val blob = if (code == 200) c.inputStream.readBytes() else null
        val blobId = c.getHeaderField("X-Blob-Id")
        c.disconnect()
        if (blob != null && blobId != null) ack(base, mailbox, blobId)
        blob
    }.getOrElse { if (it is SSLHandshakeException) null else null }

    private fun drain(base: String, mailbox: String) {
        repeat(MAX_DRAIN) {
            val c = runCatching { open("$base/mbx/$mailbox?wait=0", 10) }.getOrNull() ?: return
            val id = if (c.responseCode == 200) c.getHeaderField("X-Blob-Id") else null
            runCatching { c.inputStream.readBytes() }
            c.disconnect()
            if (id == null) return
            ack(base, mailbox, id)
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
            connectTimeout = connectTimeoutMs
            readTimeout = readSeconds * 1000
            pass()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    companion object {
        private const val MAX_DRAIN = 8
        private const val MAX_RELAY_BLOB = 50 * 1024 * 1024
    }
}
