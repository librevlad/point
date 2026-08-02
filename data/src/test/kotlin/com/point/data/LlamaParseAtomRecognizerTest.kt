package com.point.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Второй бесплатный читатель на подделках (#280). Асинхронный контракт (загрузил → опрашивай)
 * проверяется целиком, включая то, что незавершённая задача не выдаётся за пустую страницу.
 */
class LlamaParseAtomRecognizerTest {

    private val created = HttpResult(200, """{"id":"pjb-1","status":"PENDING"}""")

    private fun pending() = HttpResult(200, """{"job":{"id":"pjb-1","status":"PENDING"}}""")

    private fun completed(items: String) = HttpResult(
        200,
        """
        {"job":{"id":"pjb-1","status":"COMPLETED"},
         "items":{"pages":[{"page_number":1,"page_width":500,"page_height":400,"items":[$items]}]}}
        """.trimIndent(),
    )

    private fun reader(http: FakeHttpFiles, frames: OutboundFrames = FakeOutboundFrames(sentFrame())) =
        LlamaParseAtomRecognizer(http, frames, "free-key", "https://api.cloud.llamaindex.ai")

    @Test
    fun `сначала загрузка, потом опрос — пока задача не готова`() = runTest {
        var polls = 0
        val http = FakeHttpFiles(
            onPost = { created },
            onGet = { if (++polls < 2) pending() else completed("""{"type":"text","value":"11004","bbox":{"x":100,"y":100,"w":100,"h":50}}""") },
        )
        val layer = reader(http).read(pageObject)

        assertEquals("https://api.cloud.llamaindex.ai/api/v2/parse/upload", http.posts.single().url)
        assertEquals("Bearer free-key", http.posts.single().headers["Authorization"])
        assertEquals("page.jpg", http.posts.single().file("file")?.fileName)
        assertTrue(http.posts.single().field("configuration")?.contains("cost_effective") == true)
        assertEquals(2, http.gets.size) // первый ответ был PENDING — ждём, а не сдаёмся
        assertTrue(http.gets.all { it.contains("/api/v2/parse/pjb-1") && it.contains("expand=items") })
        assertEquals(listOf("11004"), layer.atoms.map { it.text })
    }

    @Test
    fun `координаты приводятся к сырому кадру`() = runTest {
        val http = FakeHttpFiles(
            onPost = { created },
            onGet = { completed("""{"type":"text","value":"11004","bbox":{"x":100,"y":100,"w":100,"h":50}}""") },
        )
        val box = reader(http).read(pageObject).atoms.single().box

        // Отчёт 500×400 → посланная копия 1000×800 (×2) → сырой файл (×2 по sample).
        assertEquals(400f, box.left, 0.01f)
        assertEquals(400f, box.top, 0.01f)
        assertEquals(800f, box.right, 0.01f)
        assertEquals(600f, box.bottom, 0.01f)
    }

    @Test
    fun `рамка понимается и объектом, и массивом спанов, и старым написанием bBox`() = runTest {
        val items = listOf(
            """{"type":"text","value":"один","bbox":{"x":100,"y":100,"w":100,"h":50}}""",
            """{"type":"text","value":"два","bbox":[{"x":0,"y":0,"w":10,"h":10},{"x":20,"y":0,"w":10,"h":10}]}""",
            """{"type":"text","value":"три","bBox":{"x":10,"y":10,"w":10,"h":10}}""",
        ).joinToString(",")
        val http = FakeHttpFiles(onPost = { created }, onGet = { completed(items) })
        val layer = reader(http).read(pageObject)

        assertEquals(listOf("один", "два", "три"), layer.atoms.map { it.text })
        assertEquals(listOf("lp0", "lp1", "lp2"), layer.atoms.map { it.id })
        // Несколько спанов схлопываются в накрывающий прямоугольник, а не теряются.
        val second = layer.atoms[1].box
        assertEquals(0f, second.left, 0.01f)
        assertEquals(120f, second.right, 0.01f)
    }

    @Test
    fun `таблица без value берёт текст из разметки`() = runTest {
        val http = FakeHttpFiles(
            onPost = { created },
            onGet = { completed("""{"type":"table","md":"| 11004 | 12 |","bbox":{"x":0,"y":0,"w":10,"h":10}}""") },
        )
        assertEquals("| 11004 | 12 |", reader(http).read(pageObject).atoms.single().text)
    }

    @Test
    fun `элемент без рамки не становится атомом, но его текст не теряется`() = runTest {
        val items = """{"type":"text","value":"ВЕДОМОСТЬ"},{"type":"text","value":"11004","bbox":{"x":0,"y":0,"w":10,"h":10}}"""
        val http = FakeHttpFiles(onPost = { created }, onGet = { completed(items) })
        val layer = reader(http).read(pageObject)

        assertEquals(listOf("11004"), layer.atoms.map { it.text })
        assertTrue(layer.text.contains("ВЕДОМОСТЬ"))
    }

    @Test
    fun `429 при опросе — отказ, а не пустая страница`() = runTest {
        val http = FakeHttpFiles(onPost = { created }, onGet = { HttpResult(429, "slow down") })
        val error = runCatching { reader(http).read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains("429") == true)
    }

    @Test
    fun `402 не ведёт в кассу`() = runTest {
        val http = FakeHttpFiles(onPost = { HttpResult(402, "add a card") })
        val error = runCatching { reader(http).read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains("покупать не идём") == true)
    }

    @Test
    fun `сорвавшаяся задача говорит об этом вслух`() = runTest {
        val failed = HttpResult(200, """{"job":{"id":"pjb-1","status":"ERROR","error_message":"bad page"}}""")
        val http = FakeHttpFiles(onPost = { created }, onGet = { failed })
        val error = runCatching { reader(http).read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains("задача не выполнена") == true)
        assertTrue(error?.message?.contains("bad page") == true)
    }

    @Test
    fun `пустой ключ выключает слой`() = runTest {
        assertFalse(LlamaParseAtomRecognizer(FakeHttpFiles(), FakeOutboundFrames(sentFrame()), "", "").configured)
    }
}
