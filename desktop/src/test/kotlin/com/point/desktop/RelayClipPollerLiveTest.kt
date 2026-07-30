package com.point.desktop

import com.point.core.flow.ClipRelay
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayTls
import com.point.core.flow.decodeClipFrame
import com.point.core.flow.encodeClipFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Live e2e for the shared clipboard over the relay (#161). Drives a real [RelayClipPoller] against the
 * REAL relay, simulating the phone with the same core:flow crypto/frame the phone client uses — so a
 * green run proves the desktop half and, by construction-symmetry, phone↔PC interop. Skipped in CI
 * (no relay secret from local.properties); run locally to verify against the live server.
 */
class RelayClipPollerLiveTest {

    @Test
    fun `a push frame sets the PC clipboard through the live relay`() {
        assumeRelay()
        val token = "point-clip-push-${System.nanoTime()}"
        val payload = ClipboardPayload("image/png", "e2e.png", byteArrayOf(9, 8, 7, 0, -5, 120))

        // Phone side: seal a PUSH and drop it in the phone→PC clipboard mailbox.
        postBlob(RelayCrypto.mailboxId(token, ClipRelay.TO_PC), RelayCrypto.seal(token, encodeClipFrame(ClipRelay.PUSH, payload)))

        val latch = CountDownLatch(1)
        var applied: ClipboardPayload? = null
        val poller = RelayClipPoller(
            RelayEnv.URL, RelayEnv.APP_SECRET, token,
            clipboardGet = { null },
            clipboardSet = { applied = it; latch.countDown() },
        )
        poller.start()
        val got = latch.await(20, TimeUnit.SECONDS)
        poller.stop()

        assertTrue("poller applied a pushed clip within 20s", got)
        assertEquals("image/png", applied?.mime)
        assertEquals("e2e.png", applied?.name)
        assertArrayEquals(byteArrayOf(9, 8, 7, 0, -5, 120), applied?.bytes)
    }

    @Test
    fun `a pull request is answered with the PC clipboard through the live relay`() {
        assumeRelay()
        val token = "point-clip-pull-${System.nanoTime()}"
        val pcClip = ClipboardPayload.ofText("pc-clipboard-e2e-привет")

        val poller = RelayClipPoller(
            RelayEnv.URL, RelayEnv.APP_SECRET, token,
            clipboardGet = { pcClip },
            clipboardSet = {},
        )
        poller.start()
        try {
            // Phone side: deposit a PULL request, then poll the PC→phone mailbox for the reply.
            postBlob(RelayCrypto.mailboxId(token, ClipRelay.TO_PC), RelayCrypto.seal(token, encodeClipFrame(ClipRelay.PULL, null, reqId = "live-req-1")))
            val reply = pollBlob(RelayCrypto.mailboxId(token, ClipRelay.TO_PHONE), 20)

            assertNotNull("desktop answered the pull within 20s", reply)
            val frame = decodeClipFrame(RelayCrypto.open(token, reply!!))
            assertEquals(ClipRelay.REPLY, frame.kind)
            assertEquals("the desktop echoes the pull's reqId (#272)", "live-req-1", frame.reqId)
            assertTrue(frame.payload!!.isText)
            assertEquals("pc-clipboard-e2e-привет", frame.payload!!.text())
        } finally {
            poller.stop()
        }
    }

    /**
     * #271: блоб, который не расшифровывается, раньше вставал головой очереди навсегда — relay отдаёт
     * старейший блоб, удаляет только ack, а исключение из RelayCrypto.open вылетало до ack'а. Кладём
     * в ящик мусор, за ним валидный PUSH: старый поллер зависал на мусоре и никогда не доходил до
     * PUSH; новый ack'ает мусор до расшифровки и применяет PUSH следом.
     */
    @Test
    fun `an undecodable blob is dropped and does not starve the queue`() {
        assumeRelay()
        val token = "point-clip-poison-${System.nanoTime()}"
        val toPc = RelayCrypto.mailboxId(token, ClipRelay.TO_PC)
        val good = ClipboardPayload.ofText("после мусора")

        postBlob(toPc, byteArrayOf(1, 2, 3)) // «blob too short» — не расшифруется никогда
        postBlob(toPc, RelayCrypto.seal(token, encodeClipFrame(ClipRelay.PUSH, good)))

        val latch = CountDownLatch(1)
        var applied: ClipboardPayload? = null
        val poller = RelayClipPoller(
            RelayEnv.URL, RelayEnv.APP_SECRET, token,
            clipboardGet = { null },
            clipboardSet = { applied = it; latch.countDown() },
        )
        poller.start()
        val got = latch.await(20, TimeUnit.SECONDS)
        poller.stop()

        assertTrue("the valid PUSH behind the poison blob was applied within 20s", got)
        assertEquals("после мусора", applied?.text())
    }

    private fun assumeRelay() {
        assumeTrue("no relay secret (CI / no local.properties)", RelayEnv.APP_SECRET.isNotBlank())
        assumeTrue("relay unreachable (offline run)", relayUp)
    }

    private fun postBlob(mailbox: String, blob: ByteArray) {
        val c = conn("${RelayEnv.URL.trimEnd('/')}/mbx/$mailbox")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(blob.size)
        c.outputStream.use { it.write(blob) }
        check(c.responseCode == 200) { "relay POST failed: ${c.responseCode}" }
        c.disconnect()
    }

    /** GET the mailbox with a long-poll; the raw blob (acked — leave the prod relay clean), or null
     *  after the wait (204). */
    private fun pollBlob(mailbox: String, waitSeconds: Int): ByteArray? {
        val c = conn("${RelayEnv.URL.trimEnd('/')}/mbx/$mailbox?wait=$waitSeconds")
        c.readTimeout = (waitSeconds + 10) * 1000
        val blob = if (c.responseCode == 200) c.inputStream.readBytes() else null
        val blobId = c.getHeaderField("X-Blob-Id")
        c.disconnect()
        if (blob != null && blobId != null) {
            val ackC = conn("${RelayEnv.URL.trimEnd('/')}/mbx/$mailbox/ack")
            ackC.requestMethod = "POST"
            ackC.setRequestProperty("X-Blob-Id", blobId)
            ackC.doOutput = true
            ackC.outputStream.use { it.write(ByteArray(0)) }
            ackC.responseCode
            ackC.disconnect()
        }
        return blob
    }

    private fun conn(url: String): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = RelayTls.socketFactory
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("X-Point-App", RelayEnv.APP_SECRET)
        }

    private companion object {
        /** One probe per JVM: is the live relay reachable at all? Offline runs skip, not fail. */
        val relayUp: Boolean by lazy {
            runCatching {
                val c = (URL("${RelayEnv.URL.trimEnd('/')}/health").openConnection() as HttpsURLConnection)
                c.sslSocketFactory = RelayTls.socketFactory
                c.connectTimeout = 4_000
                c.readTimeout = 4_000
                val ok = c.responseCode == 200
                c.disconnect()
                ok
            }.getOrDefault(false)
        }
    }
}
