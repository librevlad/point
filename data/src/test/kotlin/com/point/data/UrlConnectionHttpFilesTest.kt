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

/**
 * Транспорт облачных читателей — на **живом сокете**, а не на подделке (#280).
 *
 * Зачем отдельно. Все остальные тесты второго читателя подставляют фейковый [HttpFiles] и потому
 * проверяют, что мы *хотели* послать. Что при этом реально ушло в сокет, не проверял никто:
 * [UrlConnectionHttpFiles] — единственный кусок, который сам собирает `multipart/form-data`
 * руками, и ошибка в одном `\r\n`, в границе или в записи байтов даёт зелёный набор тестов и
 * ровный 400 от сервиса, который в отладке выглядит как «сервис нас не понял».
 *
 * Сервер — голый [ServerSocket] на 127.0.0.1, разбирающий запрос сам. Именно голый, а не
 * `com.sun.net.httpserver` (как в `HttpTelegramApiTest` у бота): юнит-тесты Android-модуля идут по
 * своему classpath, и `com.sun.*` там не разрешается — проверено этим же тестом, первая редакция
 * не собралась.
 *
 * Ниже — сперва сам транспорт, потом **оба читателя целиком через сокет**: от собранной формы до
 * координат атома в сыром кадре. Живьём остаётся непроверенным ровно одно звено — настоящий
 * сервис на том конце; всё, что по эту сторону провода, здесь работает по-настоящему.
 */
class UrlConnectionHttpFilesTest {

    private lateinit var server: ServerSocket
    private lateinit var worker: Thread

    /** Что реально приехало на сервер последним запросом. */
    @Volatile private var seenContentType: String? = null
    @Volatile private var seenBody: ByteArray = ByteArray(0)
    @Volatile private var seenHeaders: Map<String, String> = emptyMap()
    @Volatile private var seenPath: String = ""

    /** Чем сервер ответит: код и тело задаёт тест. */
    @Volatile private var replyCode = 200
    @Volatile private var replyBody = "[]"

    /** Ответ по адресу — для сервиса, у которого загрузка и опрос задачи это два разных запроса. */
    @Volatile private var route: ((String) -> String)? = null

    @Before fun start() {
        server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { serve(it) }
                } catch (_: Exception) {
                    if (server.isClosed) break // закрытие сокета — это конец теста, а не сбой
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @After fun stop() {
        server.close()
        worker.join(2_000)
    }

    private fun root() = "http://127.0.0.1:${server.localPort}"

    /** Минимальный HTTP/1.1 — ровно столько, сколько нужно, чтобы честно принять и честно ответить. */
    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val head = readHead(input).split("\r\n")
        seenPath = head.first().split(" ").getOrElse(1) { "" }
        seenHeaders = head.drop(1).filter { it.contains(':') }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        seenContentType = seenHeaders["content-type"]
        seenBody = ByteArray(seenHeaders["content-length"]?.toIntOrNull() ?: 0).also { readFully(input, it) }

        val body = (route?.invoke(seenPath) ?: replyBody).toByteArray(Charsets.UTF_8)
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
     * Байты, которые текстовое поле формы испортило бы перекодировкой: маркер JPEG, ноль и `0xFF`
     * — ровно то, чем начинается и чем набит настоящий кадр.
     */
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

        // Повторённое поле — это ДВА поля на проводе. Схлопнись оно в одно (или в «rus,eng»),
        // сервис прочитал бы страницу не тем языком, и на кириллице это было бы тихо.
        assertEquals(listOf("rus", "eng"), parts.filter { it.name == "languages" }.map { it.text })
        assertEquals("true", parts.single { it.name == "coordinates" }.text)
        assertEquals("hi_res", parts.single { it.name == "strategy" }.text)

        val file = parts.single { it.name == "files" }
        assertEquals("page.jpg", file.fileName)
        assertTrue(file.headers.contains("Content-Type: image/jpeg"))
        // Главное: кадр приехал побайтово. Тут ловится и потерянный `\r\n`, и перекодировка.
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
        assertTrue(res.body.contains("payment required")) // тело отказа доезжает целиком
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

    /**
     * Весь путь целиком через настоящий сокет: читатель собирает форму → реальный
     * [UrlConnectionHttpFiles] пишет её в соединение → сервер отвечает элементом в той форме, в
     * какой её описывает документация → координаты приезжают в сырой кадр.
     *
     * Здесь нет ни одной подделки, кроме самого сервиса и подготовки кадра. Именно этот стык —
     * «наш байт → чужой байт» — и нельзя было проверить подделкой транспорта.
     */
    @Test
    fun `путь Unstructured целиком по сокету — от формы до координат в сыром кадре`() = runTest {
        replyBody = """
            [{"type":"Table","element_id":"e1","text":"11004",
              "metadata":{"page_number":1,"detection_class_prob":0.87,
                "coordinates":{"system":"PixelSpace","layout_width":500,"layout_height":400,
                  "points":[[100,100],[100,150],[200,150],[200,100]]}}}]
        """.trimIndent()

        val layer = UnstructuredAtomRecognizer(
            http = UrlConnectionHttpFiles(),
            frames = FakeOutboundFrames(sentFrame()),
            apiKey = "free-key",
            apiUrl = "${root()}/general/v0/general",
        ).read(pageObject)

        // Форма и правда ушла формой, с двумя языками и просьбой о геометрии.
        val parts = partsOf(seenBody, boundaryOf(seenContentType))
        assertEquals(listOf("rus", "eng"), parts.filter { it.name == "languages" }.map { it.text })
        assertEquals("true", parts.single { it.name == "coordinates" }.text)
        assertEquals("page.jpg", parts.single { it.name == "files" }.fileName)

        // Отчёт 500×400 → посланная копия 1000×800 → сырой файл вдвое больше копии.
        val atom = layer.atoms.single()
        assertEquals("11004", atom.text)
        assertEquals("unstructured", atom.reader)
        assertEquals(0.87f, atom.confidence, 0.001f)
        assertEquals(400f, atom.box.left, 0.01f)
        assertEquals(800f, atom.box.right, 0.01f)
    }

    /**
     * То же для LlamaParse — но там путь длиннее на шаг: загрузка отдаёт задачу, результат
     * забирается опросом. По сокету проверяется именно связка двух запросов.
     */
    @Test
    fun `путь LlamaParse целиком по сокету — загрузка, опрос, координаты`() = runTest {
        route = { path ->
            if (path.contains("/upload")) {
                """{"id":"pjb-1","status":"PENDING"}"""
            } else {
                """
                {"job":{"id":"pjb-1","status":"COMPLETED"},
                 "items":{"pages":[{"page_number":1,"page_width":500,"page_height":400,
                   "items":[{"type":"text","value":"11006","bbox":[{"x":100,"y":100,"w":100,"h":50}]}]}]}}
                """.trimIndent()
            }
        }

        val layer = LlamaParseAtomRecognizer(
            http = UrlConnectionHttpFiles(),
            frames = FakeOutboundFrames(sentFrame()),
            apiKey = "free-key",
            baseUrl = root(),
        ).read(pageObject)

        assertEquals("/api/v2/parse/pjb-1?expand=items", seenPath) // опрос дошёл до нужной задачи
        val atom = layer.atoms.single()
        assertEquals("11006", atom.text)
        assertEquals("lp0", atom.id)
        assertEquals(400f, atom.box.left, 0.01f)
        assertEquals(600f, atom.box.bottom, 0.01f)
    }

    // --- разбор multipart на стороне сервера: по байтам, а не по строке ---

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
        val framed = "\r\n".toByteArray(Charsets.UTF_8) + body // у первой границы CRLF своего нет
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
