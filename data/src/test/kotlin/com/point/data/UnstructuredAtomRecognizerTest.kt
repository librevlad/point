package com.point.data

import com.point.core.flow.FrameTransform
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.point.core.flow.HttpResult

class UnstructuredAtomRecognizerTest {

    private fun answer(vararg elements: String) = HttpResult(200, "[${elements.joinToString(",")}]")

    private fun element(text: String, left: Int, top: Int, right: Int, bottom: Int, prob: Double? = null): String {
        val probability = if (prob == null) "" else ""","detection_class_prob":$prob"""
        return """
            {"type":"Table","element_id":"e-$text","text":"$text",
             "metadata":{"page_number":1$probability,
               "coordinates":{"system":"PixelSpace","layout_width":500,"layout_height":400,
                 "points":[[$left,$top],[$left,$bottom],[$right,$bottom],[$right,$top]]}}}
        """.trimIndent()
    }

    private fun reader(http: FakeHttpFiles, frames: OutboundFrames = FakeOutboundFrames(sentFrame())) =
        UnstructuredAtomRecognizer(http, frames, "free-key", "https://api.unstructuredapp.io/general/v0/general")

    @Test
    fun `запрос несёт кадр файлом и просит геометрию`() = runTest {
        val http = FakeHttpFiles(onPost = { answer(element("11004", 0, 0, 10, 10)) })
        reader(http).read(pageObject)

        val sent = http.posts.single()
        assertEquals("free-key", sent.headers["unstructured-api-key"])
        assertEquals("page.jpg", sent.file("files")?.fileName)
        assertEquals("true", sent.field("coordinates"))
        assertEquals("hi_res", sent.field("strategy"))
        assertEquals(listOf("rus", "eng"), sent.fields("languages"))
    }

    @Test
    fun `координаты приводятся к сырому кадру — через масштаб отчёта и преобразование кадра`() = runTest {

        val http = FakeHttpFiles(onPost = { answer(element("11004", 100, 100, 200, 150)) })
        val layer = reader(http).read(pageObject)

        val box = layer.atoms.single().box
        assertEquals(400f, box.left, 0.01f)
        assertEquals(400f, box.top, 0.01f)
        assertEquals(800f, box.right, 0.01f)
        assertEquals(600f, box.bottom, 0.01f)

        assertEquals(2, layer.transform?.sample)
    }

    @Test
    fun `довёрнутый кадр возвращает координаты в исходный файл, а не в выпрямленную копию`() = runTest {
        val frames = FakeOutboundFrames(
            sentFrame(FrameTransform(sample = 1, rotationDegrees = 90, uprightWidth = 500, uprightHeight = 400)),
        )
        val http = FakeHttpFiles(onPost = { answer(element("11006", 100, 100, 200, 150)) })
        val layer = reader(http, frames).read(pageObject)

        val box = layer.atoms.single().box
        assertEquals(100f, box.left, 0.01f)
        assertEquals(300f, box.top, 0.01f)
        assertEquals(150f, box.right, 0.01f)
        assertEquals(400f, box.bottom, 0.01f)
    }

    @Test
    fun `объявлена половина системы отчёта — не растягиваем кадр по одной оси`() = runTest {

        val halfDeclared = """
            {"type":"Table","text":"11004",
             "metadata":{"page_number":1,
               "coordinates":{"system":"PixelSpace","layout_width":500,
                 "points":[[100,100],[100,150],[200,150],[200,100]]}}}
        """.trimIndent()
        val http = FakeHttpFiles(onPost = { answer(halfDeclared) })
        val box = reader(http).read(pageObject).atoms.single().box

        assertEquals(200f, box.left, 0.01f)
        assertEquals(200f, box.top, 0.01f)
        assertEquals(400f, box.right, 0.01f)
        assertEquals(300f, box.bottom, 0.01f)
        assertEquals(box.right - box.left, (box.bottom - box.top) * 2f, 0.01f)
    }

    @Test
    fun `id атомов живут в своём пространстве и помнят своего ридера`() = runTest {
        val http = FakeHttpFiles(
            onPost = { answer(element("11004", 0, 0, 10, 10), element("11006", 0, 20, 10, 30)) },
        )
        val layer = reader(http).read(pageObject)

        assertEquals(listOf("un0", "un1"), layer.atoms.map { it.id })
        assertTrue(layer.atoms.all { it.reader == "unstructured" })
        assertTrue(layer.atoms.all { it.readerVersion == "general/v0" })
        assertTrue(layer.atoms.all { it.page == 0 })
    }

    @Test
    fun `уверенность берётся у модели разметки, а без неё остаётся единицей`() = runTest {
        val http = FakeHttpFiles(
            onPost = { answer(element("11004", 0, 0, 10, 10, prob = 0.42), element("11006", 0, 20, 10, 30)) },
        )
        val layer = reader(http).read(pageObject)

        assertEquals(0.42f, layer.atoms[0].confidence, 0.001f)
        assertEquals(1f, layer.atoms[1].confidence, 0.001f)
    }

    @Test
    fun `элемент без координат не становится атомом, но его текст не теряется`() = runTest {
        val noBox = """{"type":"Title","element_id":"t","text":"ВЕДОМОСТЬ","metadata":{"page_number":1}}"""
        val http = FakeHttpFiles(onPost = { answer(noBox, element("11004", 0, 0, 10, 10)) })
        val layer = reader(http).read(pageObject)

        assertEquals(listOf("11004"), layer.atoms.map { it.text })
        assertTrue(layer.text.contains("ВЕДОМОСТЬ"))
    }

    @Test
    fun `упёрлись в предел — это отказ, а не касса`() = runTest {
        val http = FakeHttpFiles(onPost = { HttpResult(402, "payment required") })
        val said = runCatching { reader(http).read(pageObject) }.exceptionOrNull()!!.message!!

        assertTrue(said, com.point.core.flow.looksLikeQuotaFailure(said))
        assertFalse("код протокола человеку ни о чём не говорит: $said", said.contains("402"))
        assertFalse("наша кухня: $said", said.contains("покупать"))
    }

    @Test
    fun `слишком часто — тоже отказ, а не пустая страница`() = runTest {
        val http = FakeHttpFiles(onPost = { HttpResult(429, "slow down") })
        val said = runCatching { reader(http).read(pageObject) }.exceptionOrNull()!!.message!!

        assertTrue(said, com.point.core.flow.looksLikeQuotaFailure(said))
        assertFalse("код протокола человеку ни о чём не говорит: $said", said.contains("429"))
        assertFalse("наша кухня: $said", said.contains("покупать"))
    }

    @Test
    fun `пустой ключ выключает слой, а не роняет сборку`() = runTest {
        val silent = UnstructuredAtomRecognizer(FakeHttpFiles(), FakeOutboundFrames(sentFrame()), "", "")
        assertFalse(silent.configured)
        assertNotNull(runCatching { silent.read(pageObject) }.exceptionOrNull())
    }

    @Test
    fun `кадр, который нечего отправить, — отказ, а не молчаливый пустой слой`() = runTest {
        val http = FakeHttpFiles()
        val error = runCatching { reader(http, FakeOutboundFrames(null)).read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains(com.point.core.flow.FRAME_NOT_READY) == true)
        assertNull(http.posts.firstOrNull())
    }
}
