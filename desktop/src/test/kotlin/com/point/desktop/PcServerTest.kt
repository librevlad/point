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

    private val ranActions = mutableListOf<Pair<String, InboxItem>>()

    private fun server(accept: Boolean = true, outbox: Outbox? = null, onReceived: (InboxItem) -> Unit = {}): PcServer =
        PcServer(
            inbox = Inbox(tmp.root),
            token = "secret-token",
            pcName = "TEST-PC",
            pairGate = { accept },
            onReceived = onReceived,
            outbox = outbox,
            remoteActions = listOf(
                com.point.core.flow.PcRemoteAction("pc-open", "Открыть на компьютере"),
                com.point.core.flow.PcRemoteAction("pc-copy", "В буфер компьютера"),
            ),
            runAction = { id, item -> ranActions += id to item },
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

    // --- Remote capabilities (#80): the PC advertises and runs its actions ---

    @Test
    fun `caps are served to a valid token and refused otherwise`() {
        val s = server()
        try {
            val ok = URL("http://127.0.0.1:${s.port}/caps").openConnection() as HttpURLConnection
            ok.setRequestProperty("X-Point-Token", "secret-token")
            assertEquals(200, ok.responseCode)
            val caps = com.point.core.flow.decodePcCaps(ok.inputStream.bufferedReader().readText())
            assertEquals(listOf("pc-open", "pc-copy"), caps.map { it.id })
            ok.disconnect()

            val bad = URL("http://127.0.0.1:${s.port}/caps").openConnection() as HttpURLConnection
            bad.setRequestProperty("X-Point-Token", "wrong")
            assertEquals(401, bad.responseCode)
            bad.disconnect()
        } finally { s.stop() }
    }

    @Test
    fun `a requested action runs after the receive, an unknown one is ignored`() {
        val s = server()
        try {
            val (code, _) = post(
                "http://127.0.0.1:${s.port}/receive",
                mapOf(
                    "X-Point-Token" to "secret-token",
                    "X-Point-Name" to b64("заметка.txt"),
                    "X-Point-Mime" to "text/plain",
                    "X-Point-Action" to b64("pc-copy"),
                ),
                "привет".toByteArray(),
            )
            assertEquals(200, code)
            assertEquals(listOf("pc-copy"), ranActions.map { it.first })

            val (code2, _) = post(
                "http://127.0.0.1:${s.port}/receive",
                mapOf(
                    "X-Point-Token" to "secret-token",
                    "X-Point-Name" to b64("ещё.txt"),
                    "X-Point-Mime" to "text/plain",
                    "X-Point-Action" to b64("no-such-action"),
                ),
                "тело".toByteArray(),
            )
            assertEquals(200, code2) // unknown action never fails the receive
            assertEquals(1, ranActions.size)
        } finally { s.stop() }
    }

    // --- Liquid pull (#161): list, download, ack ---

    private fun outboxWith(content: String, name: String): Outbox {
        val box = Outbox(File(tmp.root, "outbox"))
        val src = File(tmp.root, "src.bin").apply { writeText(content) }
        box.add(
            com.point.core.model.PointObject(
                "id", "text/plain", com.point.core.model.ScratchRef(src.absolutePath),
                com.point.core.model.ObjectState(com.point.core.model.ObjectKind.TEXT),
                metadata = mapOf("name" to name),
            ),
        )
        return box
    }

    @Test
    fun `outbox list needs the token and serves decodable entries`() {
        val s = server(outbox = outboxWith("тело", "чек.jpg"))
        try {
            val ok = URL("http://127.0.0.1:${s.port}/outbox").openConnection() as HttpURLConnection
            ok.setRequestProperty("X-Point-Token", "secret-token")
            assertEquals(200, ok.responseCode)
            val entries = com.point.core.flow.decodePcOutbox(ok.inputStream.bufferedReader().readText())
            assertEquals(listOf(1), entries.map { it.id })
            assertEquals("чек.jpg", entries[0].meta["name"])
            ok.disconnect()

            val bad = URL("http://127.0.0.1:${s.port}/outbox").openConnection() as HttpURLConnection
            bad.setRequestProperty("X-Point-Token", "wrong")
            assertEquals(401, bad.responseCode)
            bad.disconnect()
        } finally { s.stop() }
    }

    @Test
    fun `outbox file downloads by id and 404s on a stranger`() {
        val s = server(outbox = outboxWith("байты объекта", "a.txt"))
        try {
            val ok = URL("http://127.0.0.1:${s.port}/outbox/file").openConnection() as HttpURLConnection
            ok.setRequestProperty("X-Point-Token", "secret-token")
            ok.setRequestProperty("X-Point-Id", "1")
            assertEquals(200, ok.responseCode)
            assertEquals("байты объекта", ok.inputStream.bufferedReader().readText())
            ok.disconnect()

            val gone = URL("http://127.0.0.1:${s.port}/outbox/file").openConnection() as HttpURLConnection
            gone.setRequestProperty("X-Point-Token", "secret-token")
            gone.setRequestProperty("X-Point-Id", "77")
            assertEquals(404, gone.responseCode)
            gone.disconnect()
        } finally { s.stop() }
    }

    @Test
    fun `ack removes the entry and a repeated ack stays 200`() {
        val box = outboxWith("x", "a.txt")
        val s = server(outbox = box)
        try {
            val (code, _) = post(
                "http://127.0.0.1:${s.port}/outbox/ack",
                mapOf("X-Point-Token" to "secret-token", "X-Point-Id" to "1"),
                ByteArray(0),
            )
            assertEquals(200, code)
            assertTrue(box.entries().isEmpty())

            val (again, _) = post(
                "http://127.0.0.1:${s.port}/outbox/ack",
                mapOf("X-Point-Token" to "secret-token", "X-Point-Id" to "1"),
                ByteArray(0),
            )
            assertEquals(200, again)
        } finally { s.stop() }
    }
}
