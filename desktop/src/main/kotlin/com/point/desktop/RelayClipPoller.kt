package com.point.desktop

import com.point.core.flow.ClipRelay
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayTls
import com.point.core.flow.decodeClipFrame
import com.point.core.flow.encodeClipFrame
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The shared-clipboard relay half on the desktop (#161 «общий буфер» через релей). The PC long-polls
 * the phone→PC clipboard mailbox: a PUSH sets the PC's system clipboard; a PULL request is answered by
 * sealing the PC's current clipboard into the PC→phone mailbox, which the phone is polling. Same blind
 * relay, pinned TLS ([RelayTls]) and E2E crypto ([RelayCrypto]) as the object [RelayPoller] — but its
 * own daemon, so clipboard traffic and object receive never block each other.
 */
class RelayClipPoller(
    private val relayUrl: String,
    /** Пропуск устройства в аккаунте (#473): общего пароля приложения больше нет, у каждого свой. */
    private val pass: () -> String?,
    private val token: String,
    private val clipboardGet: () -> ClipboardPayload?,
    private val clipboardSet: (ClipboardPayload) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (relayUrl.isBlank() || pass().isNullOrBlank() || running) return
        running = true
        thread = Thread({ loop() }, "point-relay-clip").apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun loop() {
        val base = relayUrl.trimEnd('/')
        val toPc = RelayCrypto.mailboxId(token, ClipRelay.TO_PC)
        val toPhone = RelayCrypto.mailboxId(token, ClipRelay.TO_PHONE)
        var lastFailure: String? = null
        while (running) {
            val handled = runCatching { pollOnce(base, toPc, toPhone) }
                .onFailure { e ->
                    // Виден только ПЕРЕХОД в отказ, не каждый 3-секундный повтор: бесконечный
                    // молчаливый цикл противоречил цели #271, а лог каждой итерации — спам.
                    val failure = "${e.javaClass.simpleName}: ${e.message}"
                    if (failure != lastFailure) log("polling failed ($failure) — retrying every ${BACKOFF_MS / 1000}s")
                    lastFailure = failure
                }
                .onSuccess {
                    if (lastFailure != null) log("polling recovered")
                    lastFailure = null
                }
                .getOrDefault(false)
            // An empty long-poll already cost ~WAIT_SECONDS; only back off after an error.
            if (!handled) runCatching { Thread.sleep(if (running) BACKOFF_MS else 0) }
        }
    }

    /** One long-poll of the phone→PC clipboard mailbox; true if a message was handled. */
    private fun pollOnce(base: String, toPc: String, toPhone: String): Boolean {
        val c = open("$base/mbx/$toPc?wait=$WAIT_SECONDS", WAIT_SECONDS + 10)
        val (blob, blobId) = if (c.responseCode == 200) {
            c.inputStream.readBytes() to c.getHeaderField("X-Blob-Id")
        } else {
            null to null // 204 = empty after the wait; anything else = transient
        }
        c.disconnect()
        if (blob == null) return false

        // Ack FIRST, decode after — the mirror of the phone half (#271). The relay serves oldest-first
        // and only ack removes: a blob that fails to decrypt would otherwise become the permanent head
        // of the queue, re-served every backoff for its 24 h TTL, starving the whole clip channel.
        blobId?.let { ack(base, toPc, it) }
        val frame = runCatching { decodeClipFrame(RelayCrypto.open(token, blob)) } // E2E: relay held ciphertext
            .getOrElse { e ->
                log("dropped an undecodable clip blob (${e.javaClass.simpleName}: ${e.message})")
                return true // it left the queue; the channel is healthy — no backoff
            }
        when (frame.kind) {
            ClipRelay.PUSH -> frame.payload?.let(clipboardSet)
            ClipRelay.PULL -> reply(base, toPhone, frame.reqId)
        }
        return true
    }

    /** Answer a pull request: seal the PC's current clipboard (null → an empty-clipboard marker) into
     *  the phone mailbox, echoing the request's [reqId] so the phone accepts only its own answer. */
    private fun reply(base: String, toPhone: String, reqId: String?) {
        val payload = runCatching { clipboardGet() }.getOrNull()
        val blob = RelayCrypto.seal(token, encodeClipFrame(ClipRelay.REPLY, payload, reqId))
        runCatching {
            val c = open("$base/mbx/$toPhone", 15)
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(blob.size)
            c.outputStream.use { it.write(blob) }
            val code = c.responseCode
            c.disconnect()
            if (code != 200) log("relay refused the pull reply: HTTP $code")
        }.onFailure { log("pull reply failed to send (${it.javaClass.simpleName}: ${it.message})") }
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
        }.onFailure { log("ack failed for $blobId (${it.javaClass.simpleName}) — the blob will be re-served") }
    }

    /** The desktop runs headless: stderr is its only честный канал (#271 — no more silent failures). */
    private fun log(message: String) = System.err.println("[point relay-clip] $message")

    private fun open(url: String, readSeconds: Int): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = RelayTls.socketFactory
            connectTimeout = CONNECT_MS
            readTimeout = readSeconds * 1000
            pass()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private companion object {
        const val WAIT_SECONDS = 25
        const val CONNECT_MS = 5_000
        const val BACKOFF_MS = 3_000L
    }
}
