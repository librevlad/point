package com.point.desktop

import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayTls
import com.point.core.flow.encodePcFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Live e2e (#161 v2, P4): seals a frame like the phone would, posts it to the REAL relay, and proves
 * the [RelayPoller] polls it out, decrypts it, and hands back the object. Skipped in CI (no relay
 * secret from local.properties); run locally to verify the desktop half against the live server.
 */
class RelayPollerLiveTest {

    @Test
    fun `a sealed frame round-trips through the live relay into the poller`() {
        assumeTrue("no relay secret (CI / no local.properties)", RelayEnv.APP_SECRET.isNotBlank())
        assumeTrue("relay unreachable (offline run)", relayUp)
        val token = "point-e2e-${System.nanoTime()}"
        val payload = "relay-e2e-payload".toByteArray()

        // Phone side: seal a frame and drop it in the phone→PC mailbox.
        val frame = encodePcFrame(mapOf("name" to "e2e.txt", "mime" to "text/plain"), payload)
        postBlob(RelayCrypto.mailboxId(token, "to-pc"), RelayCrypto.seal(token, frame))

        // Desktop side: the poller must pull it out, decrypt, and reconstruct the object.
        val latch = CountDownLatch(1)
        var name: String? = null
        var bytes: ByteArray? = null
        val poller = RelayPoller(RelayEnv.URL, RelayEnv.APP_SECRET, token) { n, _, _, b, _ ->
            name = n; bytes = b; latch.countDown()
        }
        poller.start()
        val received = latch.await(20, TimeUnit.SECONDS)
        poller.stop()

        assertTrue("poller received a relay object within 20s", received)
        assertEquals("e2e.txt", name)
        assertEquals("relay-e2e-payload", bytes?.let { String(it) })
    }

    private fun postBlob(mailbox: String, blob: ByteArray) {
        val c = URL("${RelayEnv.URL.trimEnd('/')}/mbx/$mailbox").openConnection() as HttpsURLConnection
        c.sslSocketFactory = RelayTls.socketFactory
        c.requestMethod = "POST"
        c.connectTimeout = 5_000
        c.readTimeout = 10_000
        c.setRequestProperty("X-Point-App", RelayEnv.APP_SECRET)
        c.doOutput = true
        c.setFixedLengthStreamingMode(blob.size)
        c.outputStream.use { it.write(blob) }
        check(c.responseCode == 200) { "relay POST failed: ${c.responseCode}" }
        c.disconnect()
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
