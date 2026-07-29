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
    private val appSecret: String,
    private val token: String,
    private val clipboardGet: () -> ClipboardPayload?,
    private val clipboardSet: (ClipboardPayload) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (relayUrl.isBlank() || appSecret.isBlank() || running) return
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
        while (running) {
            val handled = runCatching { pollOnce(base, toPc, toPhone) }.getOrDefault(false)
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

        val frame = decodeClipFrame(RelayCrypto.open(token, blob)) // E2E: the relay only held ciphertext
        when (frame.kind) {
            ClipRelay.PUSH -> frame.payload?.let(clipboardSet)
            ClipRelay.PULL -> reply(base, toPhone)
        }
        blobId?.let { ack(base, toPc, it) }
        return true
    }

    /** Answer a pull request: seal the PC's current clipboard (null → an empty-clipboard marker) into
     *  the phone mailbox. */
    private fun reply(base: String, toPhone: String) {
        val payload = runCatching { clipboardGet() }.getOrNull()
        val blob = RelayCrypto.seal(token, encodeClipFrame(ClipRelay.REPLY, payload))
        runCatching {
            val c = open("$base/mbx/$toPhone", 15)
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
        const val CONNECT_MS = 5_000
        const val BACKOFF_MS = 3_000L
    }
}
