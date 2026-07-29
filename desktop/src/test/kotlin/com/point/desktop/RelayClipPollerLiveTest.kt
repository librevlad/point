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
        assumeTrue("no relay secret (CI / no local.properties)", RelayEnv.APP_SECRET.isNotBlank())
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
        assumeTrue("no relay secret (CI / no local.properties)", RelayEnv.APP_SECRET.isNotBlank())
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
            postBlob(RelayCrypto.mailboxId(token, ClipRelay.TO_PC), RelayCrypto.seal(token, encodeClipFrame(ClipRelay.PULL, null)))
            val reply = pollBlob(RelayCrypto.mailboxId(token, ClipRelay.TO_PHONE), 20)

            assertNotNull("desktop answered the pull within 20s", reply)
            val frame = decodeClipFrame(RelayCrypto.open(token, reply!!))
            assertEquals(ClipRelay.REPLY, frame.kind)
            assertTrue(frame.payload!!.isText)
            assertEquals("pc-clipboard-e2e-привет", frame.payload!!.text())
        } finally {
            poller.stop()
        }
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

    /** GET the mailbox with a long-poll; the raw blob, or null after the wait (204). */
    private fun pollBlob(mailbox: String, waitSeconds: Int): ByteArray? {
        val c = conn("${RelayEnv.URL.trimEnd('/')}/mbx/$mailbox?wait=$waitSeconds")
        c.readTimeout = (waitSeconds + 10) * 1000
        val blob = if (c.responseCode == 200) c.inputStream.readBytes() else null
        c.disconnect()
        return blob
    }

    private fun conn(url: String): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = RelayTls.socketFactory
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("X-Point-App", RelayEnv.APP_SECRET)
        }
}
