package com.point.data

import com.point.core.flow.ClipFail
import com.point.core.flow.ClipPull
import com.point.core.flow.ClipPush
import com.point.core.flow.ClipRelay
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.PcClipboardSync
import com.point.core.flow.PcPairing
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayTls
import com.point.core.flow.decodeClipFrame
import com.point.core.flow.encodeClipFrame
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The always-works relay client for the shared clipboard (#161 «общий буфер» через релей). When the
 * LAN hop can't reach the PC, a clipboard [push] is sealed end-to-end and dropped into the phone→PC
 * mailbox; the desktop clip-poller applies it. A [pull] is request/response over the one-way blind
 * relay: the phone deposits a pull-request into [ClipRelay.TO_PC], then long-polls [ClipRelay.TO_PHONE]
 * for the desktop's reply. Both devices connect OUTBOUND — LTE, guest Wi-Fi, anywhere; TLS is pinned
 * ([RelayTls]) and the relay only ever holds ciphertext. Mirrors [RelayPcTransport].
 *
 * Failure classes are kept distinct (#272): a 413 (blob over the relay cap), a 401 (rotated app
 * secret) and a pinning miss each surface as their own [ClipFail] instead of a catch-all
 * «недоступен». Only genuine network trouble stays [ClipPull.Unreachable] — that's the one case
 * where trying another transport can help.
 */
class RelayPcClipboardSync(
    private val appSecret: String,
    private val waitSeconds: Int = 25,
    private val connectTimeoutMs: Int = 5_000,
) : PcClipboardSync {

    override suspend fun push(pairing: PcPairing, payload: ClipboardPayload): ClipPush = withContext(Dispatchers.IO) {
        val base = relayBase(pairing) ?: return@withContext ClipPush.Unreachable
        val blob = RelayCrypto.seal(pairing.token, encodeClipFrame(ClipRelay.PUSH, payload))
        when (post(base, RelayCrypto.mailboxId(pairing.token, ClipRelay.TO_PC), blob)) {
            200 -> ClipPush.Sent
            CODE_TLS -> ClipPush.Failed(ClipFail.TAMPERED)
            401, 403 -> ClipPush.Failed(ClipFail.AUTH)
            413 -> ClipPush.Failed(ClipFail.TOO_BIG)
            else -> ClipPush.Unreachable // any other code / -1: network-class, a fallback may help
        }
    }

    override suspend fun pull(pairing: PcPairing): ClipPull = withContext(Dispatchers.IO) {
        val base = relayBase(pairing) ?: return@withContext ClipPull.Unreachable
        val token = pairing.token
        val toPc = RelayCrypto.mailboxId(token, ClipRelay.TO_PC)
        val toPhone = RelayCrypto.mailboxId(token, ClipRelay.TO_PHONE)

        drain(base, toPhone) // clear stale replies from previously timed-out pulls
        val reqId = UUID.randomUUID().toString()
        val request = RelayCrypto.seal(token, encodeClipFrame(ClipRelay.PULL, null, reqId))
        when (post(base, toPc, request)) {
            200 -> Unit
            CODE_TLS -> return@withContext ClipPull.Failed(ClipFail.TAMPERED)
            401, 403 -> return@withContext ClipPull.Failed(ClipFail.AUTH)
            413 -> return@withContext ClipPull.Failed(ClipFail.TOO_BIG)
            else -> return@withContext ClipPull.Unreachable
        }

        // Await OUR reply. A foreign blob (stale reply, garbage) is already acked by poll() — skip it
        // and keep waiting within the deadline instead of trusting whatever came first (#272, minor:
        // the bounded drain can miss a 9th stale blob; the reqId echo is the real guarantee).
        val deadline = System.nanoTime() + waitSeconds * 1_000_000_000L
        while (true) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000_000L).toInt()
            if (remaining <= 0) return@withContext ClipPull.Unreachable
            val polled = poll(base, toPhone, remaining)
            if (polled.code == 401 || polled.code == 403) return@withContext ClipPull.Failed(ClipFail.AUTH)
            if (polled.code == CODE_TLS) return@withContext ClipPull.Failed(ClipFail.TAMPERED)
            val blob = polled.blob ?: return@withContext ClipPull.Unreachable
            val frame = runCatching { decodeClipFrame(RelayCrypto.open(token, blob)) }.getOrNull()
                ?: continue // undecodable garbage in a public mailbox — acked, gone, keep waiting
            if (frame.kind != ClipRelay.REPLY || frame.reqId != reqId) continue // someone else's / stale
            return@withContext frame.payload?.let { ClipPull.Got(it) } ?: ClipPull.Empty
        }
        @Suppress("UNREACHABLE_CODE")
        ClipPull.Unreachable // while(true) always returns; the compiler still wants an expression here
    }

    private fun relayBase(pairing: PcPairing): String? =
        pairing.relay?.trimEnd('/')?.takeIf { it.isNotBlank() && appSecret.isNotBlank() }

    /** POST a sealed blob; the HTTP status, [CODE_TLS] on a pinning miss, or -1 on network failure. */
    private fun post(base: String, mailbox: String, blob: ByteArray): Int = runCatching {
        val c = open("$base/mbx/$mailbox", waitSeconds + 10)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(blob.size)
        c.outputStream.use { it.write(blob) }
        val code = c.responseCode
        c.disconnect()
        code
    }.getOrElse { if (it is SSLHandshakeException) CODE_TLS else -1 }

    private class Polled(val code: Int, val blob: ByteArray?)

    /** One long-poll on [mailbox]: the sealed blob + status ([CODE_TLS] on a pinning miss, -1 on
     *  network failure). Acks a hit immediately — a blob we can't use must still leave the queue. */
    private fun poll(base: String, mailbox: String, wait: Int): Polled = runCatching {
        val c = open("$base/mbx/$mailbox?wait=$wait", wait + 10)
        val code = c.responseCode
        val blob = if (code == 200) c.inputStream.readBytes() else null
        val blobId = c.getHeaderField("X-Blob-Id")
        c.disconnect()
        if (blob != null && blobId != null) ack(base, mailbox, blobId)
        Polled(code, blob)
    }.getOrElse { Polled(if (it is SSLHandshakeException) CODE_TLS else -1, null) }

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
        /** A sentinel «status» for an [SSLHandshakeException]: the pinned handshake failed. */
        const val CODE_TLS = -2
    }
}
