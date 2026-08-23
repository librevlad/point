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

    /**
     * Запись голоса — единственная отправка файла, что есть у продукта (#1252): раньше форму
     * здесь изображало облачное чтение страницы, которого больше нет.
     *
     * Байты нарочно несут `0x0D 0x0A` и два дефиса подряд — ровно то, из чего сложена граница
     * формы: запись не смеет разъехаться на собственном содержимом.
     */
    private val oggLike = byteArrayOf(
        0x4F, 0x67, 0x67, 0x53, 0x00, 0x02, 0x0D, 0x0A,
        0x2D, 0x2D, 0x0D, 0x0A, 0xFF.toByte(), 0x00, 0x1F,
    )

    @Test
    fun `запись доезжает дословно — адрес, ключ и байты голоса не портятся`() = runTest {
        UrlConnectionHttpFiles().postMultipart(
            url = "${root()}/openai/v1/audio/transcriptions",
            headers = mapOf("Authorization" to "Bearer free-key"),
            parts = listOf(
                FormPart.Binary("file", "voice.ogg", "audio/ogg", oggLike),
                FormPart.Field("model", "whisper-large-v3-turbo"),
                FormPart.Field("response_format", "json"),
            ),
        )

        assertEquals("/openai/v1/audio/transcriptions", seenPath)
        assertEquals("Bearer free-key", seenHeaders["authorization"])
        val parts = partsOf(seenBody, boundaryOf(seenContentType))

        assertEquals("whisper-large-v3-turbo", parts.single { it.name == "model" }.text)
        assertEquals("json", parts.single { it.name == "response_format" }.text)

        val file = parts.single { it.name == "file" }
        assertEquals("voice.ogg", file.fileName)
        assertTrue(file.headers.contains("Content-Type: audio/ogg"))

        assertArrayEquals(oggLike, file.bytes)
    }

    @Test
    fun `402 приезжает ответом с телом, а не аварией — иначе очередь провайдеров не сдвинулась бы`() = runTest {
        replyCode = 402
        replyBody = """{"detail":"payment required"}"""

        val res = UrlConnectionHttpFiles().postMultipart(
            root(), emptyMap(), listOf(FormPart.Field("model", "whisper-large-v3-turbo")),
        )

        assertEquals(402, res.code)
        assertTrue(res.body.contains("payment required"))
    }

    @Test
    fun `429 тоже ответ, а не исключение`() = runTest {
        replyCode = 429
        replyBody = "slow down"

        val res = UrlConnectionHttpFiles().postMultipart(
            root(), emptyMap(), listOf(FormPart.Field("model", "whisper-large-v3-turbo")),
        )

        assertEquals(429, res.code)
        assertEquals("slow down", res.body)
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
