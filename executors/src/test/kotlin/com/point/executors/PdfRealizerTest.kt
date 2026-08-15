package com.point.executors

import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfTextExtractor
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfRealizerTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private fun pdfExtractor(text: String) = object : PdfTextExtractor {
        override suspend fun extractText(obj: PointObject) = text
    }

    private fun pdfObject() = PointObject(
        id = "id",
        mime = "application/pdf",
        uri = ScratchRef("/tmp/whatever.pdf"),
        state = ObjectState(ObjectKind.PDF),
    )

    /** Подменённая раскладка шрифта — тот самый случай, ради которого путь и существует (#933). */
    private val swappedFont = """
        BaxraxoorpxMyBaq:
        ToeapucrBo 3 o6MexeHop eignoeiganbHicrlo "Eniqgxtp K"
        e.qPnov 32490244
        04128, M. KrTa, eyn. Eeproeequxa,6-K
    """.trimIndent()

    @Test
    fun `pdf with text extracts to a TEXT object`() = runTest {
        val realizer = PdfRealizer(store, pdfExtractor("Привет из PDF"), NoPages)
        val result = realizer.perform(pdfObject())

        assertTrue(result is ActionResult.Success)
        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.TEXT, out.type)
        assertEquals("Привет из PDF", File(out.uri.value).readText())
    }

    @Test
    fun `scanned pdf with no text is a recoverable failure`() = runTest {
        val realizer = PdfRealizer(store, pdfExtractor("   "), NoPages)
        val result = realizer.perform(pdfObject())

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    /** Офисный файл этому исполнителю не по зубам — и он честно за него не берётся (#403). */
    @Test
    fun `офисный документ телефон в PDF не превращает`() {
        val realizer = PdfRealizer(store, pdfExtractor(""), NoPages)

        assertTrue(realizer.accepts(ObjectState(ObjectKind.PDF)))
        assertTrue(
            "телефон снова берётся пересказывать документ",
            !realizer.accepts(ObjectState(ObjectKind.OFFICE)),
        )
    }

    /**
     * Порождённый объект указывает на файл, который открывается (#995).
     *
     * Запасной путь отдавал вид IMAGE с `ref` на **папку** страниц: любой читатель честно
     * падал, и Point тут же объявлял собственный результат непригодным — «Файл не открылся».
     * Отрисованная страница при этом лежала внутри, целая.
     */
    @Test
    fun `нечитаемый слой отдаёт саму страницу, а не папку с ней`() = runTest {
        val dir = File.createTempFile("pages-", "").apply { delete(); mkdirs(); deleteOnExit() }
        val page = File(dir, "page-001.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val rasterizer = object : com.point.core.flow.PdfRasterizer {
            override suspend fun rasterize(obj: PointObject) = ScratchRef(dir.absolutePath)
            override suspend fun rasterizeFirstPage(obj: PointObject) = ScratchRef(page.absolutePath)
        }

        val result = PdfRealizer(store, pdfExtractor(swappedFont), rasterizer)
            .perform(pdfObject())

        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.IMAGE, out.type)
        assertTrue("результат указывает на папку: ${out.uri.value}", File(out.uri.value).isFile)
        assertEquals(page.absolutePath, out.uri.value)
        assertTrue("имя обещает не тот файл: ${out.metadata["name"]}", out.metadata["name"]!!.endsWith(".jpg"))
    }

    /** Страниц несколько — это набор, как у «Страниц», а не одна картинка. */
    @Test
    fun `несколько страниц становятся набором`() = runTest {
        val dir = File.createTempFile("pages2-", "").apply { delete(); mkdirs(); deleteOnExit() }
        File(dir, "page-001.jpg").writeBytes(byteArrayOf(1))
        File(dir, "page-002.jpg").writeBytes(byteArrayOf(2))
        val rasterizer = object : com.point.core.flow.PdfRasterizer {
            override suspend fun rasterize(obj: PointObject) = ScratchRef(dir.absolutePath)
            override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? = null
        }

        val out = (
            PdfRealizer(store, pdfExtractor(swappedFont), rasterizer)
                .perform(pdfObject()) as ActionResult.Success
            ).result

        assertEquals(ObjectKind.COLLECTION, out.type)
        assertEquals("2", out.metadata["count"])
    }

    @Test
    fun `извлечение текста из PDF называет себя`() = runTest {
        val realizer = PdfRealizer(store, pdfExtractor("Привет из PDF"), NoPages)

        val heard = stagesHeard { realizer.perform(pdfObject()) }

        assertEquals(listOf("Извлекаю текст из PDF"), heard)
    }
}
