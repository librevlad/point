package com.point.data

import com.point.core.flow.FrameTransform
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Второй читатель страницы на подделках (#280): проверяется запрос, разбор ответа и — главное —
 * приведение координат к сырому кадру. Живого ключа нет и не нужно.
 */
class UnstructuredAtomRecognizerTest {

    private fun answer(vararg elements: String) = HttpResult(200, "[${elements.joinToString(",")}]")

    /** Элемент с рамкой в системе отчёта 500×400. */
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
        assertEquals("true", sent.field("coordinates")) // без этого геометрии не будет вовсе
        assertEquals("hi_res", sent.field("strategy"))
        assertEquals(listOf("rus", "eng"), sent.fields("languages"))
    }

    @Test
    fun `координаты приводятся к сырому кадру — через масштаб отчёта и преобразование кадра`() = runTest {
        // Отчёт в системе 500×400, послали мы копию 1000×800, сырой файл вдвое больше копии.
        val http = FakeHttpFiles(onPost = { answer(element("11004", 100, 100, 200, 150)) })
        val layer = reader(http).read(pageObject)

        val box = layer.atoms.single().box
        assertEquals(400f, box.left, 0.01f)
        assertEquals(400f, box.top, 0.01f)
        assertEquals(800f, box.right, 0.01f)
        assertEquals(600f, box.bottom, 0.01f)
        // Дорога назад сохранена: без неё кроп сомнительной ячейки уехал бы мимо.
        assertEquals(2, layer.transform?.sample)
    }

    @Test
    fun `довёрнутый кадр возвращает координаты в исходный файл, а не в выпрямленную копию`() = runTest {
        val frames = FakeOutboundFrames(
            sentFrame(FrameTransform(sample = 1, rotationDegrees = 90, uprightWidth = 500, uprightHeight = 400)),
        )
        val http = FakeHttpFiles(onPost = { answer(element("11006", 100, 100, 200, 150)) })
        val layer = reader(http, frames).read(pageObject)

        // Отчёт уже в системе посланной копии (500×400), остаётся снять доворот на 90°.
        val box = layer.atoms.single().box
        assertEquals(100f, box.left, 0.01f)
        assertEquals(300f, box.top, 0.01f)
        assertEquals(150f, box.right, 0.01f)
        assertEquals(400f, box.bottom, 0.01f)
    }

    @Test
    fun `id атомов живут в своём пространстве и помнят своего ридера`() = runTest {
        val http = FakeHttpFiles(
            onPost = { answer(element("11004", 0, 0, 10, 10), element("11006", 0, 20, 10, 30)) },
        )
        val layer = reader(http).read(pageObject)

        // Пословные атомы движка — w0, w1; облачные обязаны не пересечься с ними.
        assertEquals(listOf("un0", "un1"), layer.atoms.map { it.id })
        assertTrue(layer.atoms.all { it.reader == "unstructured" })
        assertTrue(layer.atoms.all { it.readerVersion == "general/v0" })
        assertTrue(layer.atoms.all { it.page == 0 }) // сервис считает с единицы, атом — с нуля
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

        assertEquals(listOf("11004"), layer.atoms.map { it.text }) // адрес не выдумываем
        assertTrue(layer.text.contains("ВЕДОМОСТЬ")) // но прочитанное остаётся видимым
    }

    @Test
    fun `402 не покупает, а становится отказом`() = runTest {
        val http = FakeHttpFiles(onPost = { HttpResult(402, "payment required") })
        val error = runCatching { reader(http).read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains("402") == true)
        assertTrue(error?.message?.contains("покупать не идём") == true)
    }

    @Test
    fun `429 тоже отказ, а не пустая страница`() = runTest {
        val http = FakeHttpFiles(onPost = { HttpResult(429, "slow down") })
        val error = runCatching { reader(http).read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains("429") == true)
    }

    @Test
    fun `пустой ключ выключает слой, а не роняет сборку`() = runTest {
        val silent = UnstructuredAtomRecognizer(FakeHttpFiles(), FakeOutboundFrames(sentFrame()), "", "")
        assertFalse(silent.configured) // в раздаваемой сборке ключа нет — и слоя нет
        assertNotNull(runCatching { silent.read(pageObject) }.exceptionOrNull())
    }

    @Test
    fun `кадр, который нечего отправить, — отказ, а не молчаливый пустой слой`() = runTest {
        val http = FakeHttpFiles()
        val error = runCatching { reader(http, FakeOutboundFrames(null)).read(pageObject) }.exceptionOrNull()

        assertTrue(error?.message?.contains("кадр не подготовлен") == true)
        assertNull(http.posts.firstOrNull()) // и в сеть при этом не ходили
    }
}
