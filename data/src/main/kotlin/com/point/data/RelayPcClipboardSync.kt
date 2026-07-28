package com.point.data

import com.point.core.flow.ClipPull
import com.point.core.flow.ClipRelay
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.PcClipboardSync
import com.point.core.flow.PcPairing
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayTls
import com.point.core.flow.decodeClipFrame
import com.point.core.flow.encodeClipFrame
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The always-works relay client for the shared clipboard (#161 «общий буфер» через релей). When the
 * LAN hop can't reach the PC, a clipboard [push] is sealed end-to-end and dropped into the phone→PC
 * mailbox; the desktop clip-poller applies it. A [pull] is request/response over the one-way blind
 * relay: the phone deposits a pull-request into [ClipRelay.TO_PC], then long-polls [ClipRelay.TO_PHONE]
 * for the desktop's reply. Both devices connect OUTBOUND — LTE, guest Wi-Fi, anywhere; TLS is pinned
 * ([RelayTls]) and the relay only ever holds ciphertext. Mirrors [RelayPcTransport].
 */
class RelayPcClipboardSync(
    private val appSecret: String,
    private val waitSeconds: Int = 25,
    private val connectTimeoutMs: Int = 5_000,
) : PcClipboardSync {

    override suspend fun push(pairing: PcPairing, payload: ClipboardPayload): Boolean = withContext(Dispatchers.IO) {
        val base = relayBase(pairing) ?: return@withContext false
        runCatching {
            val blob = RelayCrypto.seal(pairing.token, encodeClipFrame(ClipRelay.PUSH, payload))
            post(base, RelayCrypto.mailboxId(pairing.token, ClipRelay.TO_PC), blob) == 200
        }.getOrDefault(false)
    }

    override suspend fun pull(pairing: PcPairing): ClipPull = withContext(Dispatchers.IO) {
        val base = relayBase(pairing) ?: return@withContext ClipPull.Unreachable
        val token = pairing.token
        val toPc = RelayCrypto.mailboxId(token, ClipRelay.TO_PC)
        val toPhone = RelayCrypto.mailboxId(token, ClipRelay.TO_PHONE)
        runCatching {
            drain(base, toPhone) // clear any stale reply from a previously timed-out pull
            val request = RelayCrypto.seal(token, encodeClipFrame(ClipRelay.PULL, null))
            if (post(base, toPc, request) != 200) return@runCatching ClipPull.Unreachable
            val reply = poll(base, toPhone, waitSeconds) ?: return@runCatching ClipPull.Unreachable
            val frame = decodeClipFrame(RelayCrypto.open(token, reply)) // E2E: the relay never had plaintext
            frame.payload?.let { ClipPull.Got(it) } ?: ClipPull.Empty
        }.getOrDefault(ClipPull.Unreachable)
    }

    private fun relayBase(pairing: PcPairing): String? =
        pairing.relay?.trimEnd('/')?.takeIf { it.isNotBlank() && appSecret.isNotBlank() }

    /** POST a sealed blob; the HTTP status, or -1 on failure. */
    private fun post(base: String, mailbox: String, blob: ByteArray): Int = runCatching {
        val c = open("$base/mbx/$mailbox", waitSeconds + 10)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(blob.size)
        c.outputStream.use { it.write(blob) }
        val code = c.responseCode
        c.disconnect()
        code
    }.getOrDefault(-1)

    /** One long-poll on [mailbox]: the sealed blob, or null after the wait (204) / on error. Acks a hit. */
    private fun poll(base: String, mailbox: String, wait: Int): ByteArray? = runCatching {
        val c = open("$base/mbx/$mailbox?wait=$wait", wait + 10)
        val blob = if (c.responseCode == 200) c.inputStream.readBytes() else null
        val blobId = c.getHeaderField("X-Blob-Id")
        c.disconnect()
        if (blob != null && blobId != null) ack(base, mailbox, blobId)
        blob
    }.getOrNull()

    /** Empty [mailbox] of leftover blobs (non-blocking wait=0), so a pull reads only its own reply. */
    private fun drain(base: String, mailbox: String) {
        repeat(MAX_DRAIN) { // bounded: never loop forever against a misbehaving relay
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
            setRequestProperty("X-Point-App", appSecret)
        }

    private companion object {
        const val MAX_DRAIN = 8
    }
}
