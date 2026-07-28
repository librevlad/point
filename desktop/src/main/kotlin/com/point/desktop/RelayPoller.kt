package com.point.desktop

import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayTls
import com.point.core.flow.decodePcFrame
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The always-works relay client (#161 v2, desktop half — P4). The PC connects OUTBOUND to the blind
 * relay and long-polls the phone→PC mailbox; when the phone couldn't reach the LAN and fell back to
 * the relay, the object arrives here, sealed end-to-end. It is decrypted ([RelayCrypto.open]),
 * un-framed, and handed to [onObject] exactly like a LAN `/receive`. No inbound port, NAT, or shared
 * network needed — the same reason the phone side works. TLS is pinned ([RelayTls]).
 */
class RelayPoller(
    private val relayUrl: String,
    private val appSecret: String,
    private val token: String,
    /** name, mime, understanding-metadata, raw bytes, optional action id. */
    private val onObject: (String, String, Map<String, String>, ByteArray, String?) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (relayUrl.isBlank() || appSecret.isBlank() || running) return
        running = true
        thread = Thread({ loop() }, "point-relay-poll").apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun loop() {
        val base = relayUrl.trimEnd('/')
        val mailbox = RelayCrypto.mailboxId(token, TO_PC)
        while (running) {
            val delivered = runCatching { pollOnce(base, mailbox) }.getOrDefault(false)
            // On an error (or a network blip) back off; an empty long-poll returned false too but
            // cost ~WAIT_SECONDS already, so loop straight back.
            if (!delivered) runCatching { Thread.sleep(if (running) BACKOFF_MS else 0) }
        }
    }

    /** One long-poll: returns true if a blob was received (so we loop immediately for the next). */
    private fun pollOnce(base: String, mailbox: String): Boolean {
        val c = URL("$base/mbx/$mailbox?wait=$WAIT_SECONDS").openConnection() as HttpsURLConnection
        c.sslSocketFactory = RelayTls.socketFactory
        c.connectTimeout = CONNECT_MS
        c.readTimeout = (WAIT_SECONDS + 10) * 1000
        c.setRequestProperty("X-Point-App", appSecret)
        val (blob, blobId) = if (c.responseCode == 200) {
            c.inputStream.readBytes() to c.getHeaderField("X-Blob-Id")
        } else {
            null to null // 204 = empty after the wait; anything else = transient
        }
        c.disconnect()
        if (blob == null) return false

        val frame = decodePcFrame(RelayCrypto.open(token, blob)) // E2E: the relay never had the plaintext
        val meta = frame.meta
        val name = meta["name"]?.takeIf { it.isNotBlank() } ?: "объект"
        val mime = meta["mime"] ?: "application/octet-stream"
        val understanding = meta - setOf("name", "mime", "action")
        onObject(name, mime, understanding, frame.bytes, meta["action"])
        blobId?.let { ack(base, mailbox, it) }
        return true
    }

    private fun ack(base: String, mailbox: String, blobId: String) {
        runCatching {
            val c = URL("$base/mbx/$mailbox/ack").openConnection() as HttpsURLConnection
            c.sslSocketFactory = RelayTls.socketFactory
            c.requestMethod = "POST"
            c.connectTimeout = CONNECT_MS
            c.readTimeout = CONNECT_MS
            c.setRequestProperty("X-Point-App", appSecret)
            c.setRequestProperty("X-Blob-Id", blobId)
            c.doOutput = true
            c.outputStream.use { it.write(ByteArray(0)) }
            c.responseCode
            c.disconnect()
        }
    }

    private companion object {
        const val TO_PC = "to-pc"
        const val WAIT_SECONDS = 25
        const val CONNECT_MS = 5_000
        const val BACKOFF_MS = 3_000L
    }
}
