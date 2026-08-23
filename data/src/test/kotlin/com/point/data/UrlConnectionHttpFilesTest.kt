package com.point.data

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import com.point.core.flow.UrlConnectionHttpFiles
import com.point.core.flow.FormPart

class UrlConnectionHttpFilesTest {

    private lateinit var server: ServerSocket
    private lateinit var worker: Thread

    @Volatile private var seenContentType: String? = null
    @Volatile private var seenBody: ByteArray = ByteArray(0)
    @Volatile private var seenHeaders: Map<String, String> = emptyMap()
    @Volatile private var seenPath: String = ""

    @Volatile private var replyCode = 200
    @Volatile private var replyBody = "[]"

    @Before fun start() {
        server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { serve(it) }
                } catch (_: Exception) {
                    if (server.isClosed) break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @After fun stop() {
        server.close()
        worker.join(2_000)
    }

    private fun root() = "http://127.0.0.1:${server.localPort}"

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val head = readHead(input).split("\r\n")
        seenPath = head.first().split(" ").getOrElse(1) { "" }
        seenHeaders = head.drop(1).filter { it.contains(':') }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        seenContentType = seenHeaders["content-type"]
        seenBody = ByteArray(seenHeaders["content-length"]?.toIntOrNull() ?: 0).also { readFully(input, it) }

        val body = replyBody.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $replyCode ${reason(replyCode)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(header.toByteArray(Charsets.UTF_8))
            write(body)
            flush()
        }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        402 -> "Payment Required"
        429 -> "Too Many Requests"
        else -> "Status"
    }

    private fun readHead(input: InputStream): String {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < CRLF_CRLF.size) {
            val byte = input.read()
            if (byte < 0) break
            head.write(byte)
            matched = if (byte == CRLF_CRLF[matched].toInt()) matched + 1 else if (byte == '\r'.code) 1 else 0
        }
        return String(head.toByteArray(), Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var got = 0
        while (got < into.size) {
            val n = input.read(into, got, into.size - got)
            if (n < 0) break
            got += n
        }
    }

    private val jpegLike = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0xFF.toByte(), 0xD9.toByte(),
    )

    @Test
    fun `форма доезжает дословно — повторённое поле остаётся двумя, а байты кадра не портятся`() = runTest {
        UrlConnectionHttpFiles().postMultipart(
            url = "${root()}/general/v0/general",
            headers = mapOf("unstructured-api-key" to "free-key"),
            parts = listOf(
                FormPart.Binary("files", "page.jpg", "image/jpeg", jpegLike),
                FormPart.Field("coordinates", "true"),
                FormPart.Field("strategy", "hi_res"),
                FormPart.Field("languages", "rus"),
                FormPart.Field("languages", "eng"),
            ),
        )

        assertEquals("free-key", seenHeaders["unstructured-api-key"])
        val parts = partsOf(seenBody, boundaryOf(seenContentType))

        assertEquals(listOf("rus", "eng"), parts.filter { it.name == "languages" }.map { it.text })
        assertEquals("true", parts.single { it.name == "coordinates" }.text)
        assertEquals("hi_res", parts.single { it.name == "strategy" }.text)

        val file = parts.single { it.name == "files" }
        assertEquals("page.jpg", file.fileName)
        assertTrue(file.headers.contains("Content-Type: image/jpeg"))

        assertArrayEquals(jpegLike, file.bytes)
    }

    @Test
    fun `402 приезжает ответом с телом, а не аварией — иначе очередь провайдеров не сдвинулась бы`() = runTest {
        replyCode = 402
        replyBody = """{"detail":"payment required"}"""

        val res = UrlConnectionHttpFiles().postMultipart(
            root(), emptyMap(), listOf(FormPart.Field("strategy", "hi_res")),
        )

        assertEquals(402, res.code)
        assertTrue(res.body.contains("payment required"))
    }

    @Test
    fun `429 тоже ответ, а не исключение`() = runTest {
        replyCode = 429
        replyBody = "slow down"

        val res = UrlConnectionHttpFiles().postMultipart(
            root(), emptyMap(), listOf(FormPart.Field("strategy", "hi_res")),
        )

        assertEquals(429, res.code)
        assertEquals("slow down", res.body)
    }

    @Test
    fun `опрос задачи несёт заголовок и запрошенный путь целиком`() = runTest {
        replyBody = """{"job":{"status":"COMPLETED"}}"""

        val res = UrlConnectionHttpFiles().get(
            "${root()}/api/v2/parse/pjb-1?expand=items",
            mapOf("Authorization" to "Bearer free-key"),
        )

        assertEquals("/api/v2/parse/pjb-1?expand=items", seenPath)
        assertEquals("Bearer free-key", seenHeaders["authorization"])
        assertTrue(res.body.contains("COMPLETED"))
    }

    private class Part(val headers: String, val bytes: ByteArray) {
        val name: String = Regex("""name="([^"]*)"""").find(headers)?.groupValues?.get(1).orEmpty()
        val fileName: String? = Regex("""filename="([^"]*)"""").find(headers)?.groupValues?.get(1)
        val text: String get() = String(bytes, Charsets.UTF_8)
    }

    private fun boundaryOf(contentType: String?): String {
        val type = requireNotNull(contentType) { "запрос ушёл без Content-Type" }
        assertTrue("не multipart-запрос — $type", type.startsWith("multipart/form-data"))
        return type.substringAfter("boundary=").trim().trim('"')
    }

    private fun partsOf(body: ByteArray, boundary: String): List<Part> {
        val separator = "\r\n--$boundary".toByteArray(Charsets.UTF_8)
        val framed = "\r\n".toByteArray(Charsets.UTF_8) + body
        val parts = mutableListOf<Part>()
        var at = indexOf(framed, separator, 0)
        while (at >= 0) {
            val start = at + separator.size
            if (start + 2 <= framed.size && framed[start] == DASH && framed[start + 1] == DASH) break
            val next = indexOf(framed, separator, start)
            if (next < 0) break
            val headEnd = indexOf(framed, CRLF_CRLF, start)
            require(headEnd in 0 until next) { "часть формы без заголовков" }
            parts += Part(
                headers = String(framed.copyOfRange(start + 2, headEnd), Charsets.UTF_8),
                bytes = framed.copyOfRange(headEnd + CRLF_CRLF.size, next),
            )
            at = next
        }
        return parts
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        const val DASH = '-'.code.toByte()
        val CRLF_CRLF = "\r\n\r\n".toByteArray(Charsets.UTF_8)
    }
}
