package com.point.desktop

import com.point.core.flow.encodePcMeta
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The receiver over the REAL JDK HttpServer on an ephemeral port — the exact stack
 * production runs, so routing, auth, base64 headers and file landing are covered
 * end-to-end without AWT or Compose.
 */
class PcServerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun server(accept: Boolean = true, onReceived: (InboxItem) -> Unit = {}): PcServer =
        PcServer(
            inbox = Inbox(tmp.root),
            token = "secret-token",
            pcName = "TEST-PC",
            pairGate = { accept },
            onReceived = onReceived,
        ).also { it.start(preferredPort = 0) }

    private fun post(url: String, headers: Map<String, String>, body: ByteArray): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.doOutput = true
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        val text = runCatching {
            (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        }.getOrDefault("")
        c.disconnect()
        return code to text
    }

    @Test
    fun `receive lands the file, decodes base64 headers and fires the callback`() {
        var got: InboxItem? = null
        val s = server { got = it }
        try {
            val (code, _) = post(
                "http://127.0.0.1:${s.port}/receive",
                mapOf(
                    "X-Point-Token" to "secret-token",
                    "X-Point-Name" to b64("чек.jpg"),
                    "X-Point-Mime" to "image/jpeg",
                    "X-Point-Meta" to b64(encodePcMeta(mapOf("entity.phone" to "+380671234567"))),
                ),
                byteArrayOf(1, 2, 3),
            )
            assertEquals(200, code)
            assertEquals("чек.jpg", got!!.obj.metadata["name"])
            assertEquals("+380671234567", got!!.obj.metadata["entity.phone"])
            assertEquals(3, File(got!!.obj.uri.value).length())
            assertTrue(File(got!!.obj.uri.value).absolutePath.startsWith(tmp.root.absolutePath))
        } finally {
            s.stop()
        }
    }

    @Test
    fun `a wrong token is 401 and nothing lands`() {
        var called = false
        val s = server { called = true }
        try {
            val (code, _) = post(
                "http://127.0.0.1:${s.port}/receive",
                mapOf("X-Point-Token" to "WRONG", "X-Point-Name" to b64("x.txt")),
                byteArrayOf(1),
            )
            assertEquals(401, code)
            assertEquals(false, called)
            assertEquals(0, tmp.root.listFiles()!!.size)
        } finally {
            s.stop()
        }
    }

    @Test
    fun `pair returns the token on accept and 403 on decline`() {
        val yes = server(accept = true)
        try {
            val (code, body) = post(
                "http://127.0.0.1:${yes.port}/pair",
                mapOf("X-Point-Name" to b64("Emulator")),
                ByteArray(0),
            )
            assertEquals(200, code)
            assertEquals("secret-token", body.trim())
        } finally {
            yes.stop()
        }

        val no = server(accept = false)
        try {
            val (code, _) = post(
                "http://127.0.0.1:${no.port}/pair",
                mapOf("X-Point-Name" to b64("Emulator")),
                ByteArray(0),
            )
            assertEquals(403, code)
        } finally {
            no.stop()
        }
    }

    @Test
    fun `ping identifies the pc by name`() {
        val s = server()
        try {
            val c = URL("http://127.0.0.1:${s.port}/ping").openConnection() as HttpURLConnection
            assertEquals(200, c.responseCode)
            assertTrue(c.inputStream.bufferedReader().readText().contains("TEST-PC"))
            c.disconnect()
        } finally {
            s.stop()
        }
    }
}
