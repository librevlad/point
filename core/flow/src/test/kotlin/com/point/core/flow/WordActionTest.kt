package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordActionTest {

    private fun pdfObj() =
        PointObject("id", "application/pdf", ScratchRef("/tmp/x.pdf"), ObjectState(ObjectKind.PDF))

    private fun imageObj() =
        PointObject("id", "image/jpeg", ScratchRef("/tmp/photo.jpg"), ObjectState(ObjectKind.IMAGE))

    private val noOcr = object : TextRecognizer {
        override suspend fun recognize(obj: PointObject) = ""
    }

    @Test
    fun `now accepts an image (OCR then Word)`() {
        assertTrue(WordCapability().accepts(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `pdf text becomes a docx OFFICE object, one paragraph per line`() = runTest {
        val pdf = object : PdfTextExtractor {
            override suspend fun extractText(obj: PointObject, atMost: Int?) = "Строка 1\nСтрока 2"
        }
        var paras: List<String>? = null
        val docx = object : DocxWriter {
            override suspend fun write(paragraphs: List<String>): ScratchRef {
                paras = paragraphs
                return ScratchRef("/tmp/out.docx")
            }
        }
        val result = WordRealizer(testKnowledge(pdf), docx, noOcr).perform(pdfObj(), null)
        assertTrue(result is ActionResult.Success)
        val obj = (result as ActionResult.Success).result
        assertEquals(ObjectKind.OFFICE, obj.type)
        assertTrue(obj.mime.endsWith("wordprocessingml.document"))
        assertEquals(listOf("Строка 1", "Строка 2"), paras)
    }

    @Test
    fun `an image is OCR'd into a docx`() = runTest {
        val pdf = object : PdfTextExtractor { override suspend fun extractText(obj: PointObject, atMost: Int?) = "" }
        var paras: List<String>? = null
        val docx = object : DocxWriter {
            override suspend fun write(paragraphs: List<String>): ScratchRef {
                paras = paragraphs
                return ScratchRef("/tmp/out.docx")
            }
        }
        val ocr = object : TextRecognizer {
            override suspend fun recognize(obj: PointObject) = "Чек\nИТОГО 693,40"
        }
        val result = WordRealizer(testKnowledge(pdf), docx, ocr).perform(imageObj(), null)
        assertTrue(result is ActionResult.Success)
        assertEquals(ObjectKind.OFFICE, (result as ActionResult.Success).result.type)
        assertEquals(listOf("Чек", "ИТОГО 693,40"), paras)
    }

    @Test
    fun `a scanned pdf with no text fails with an OCR hint`() = runTest {
        val pdf = object : PdfTextExtractor { override suspend fun extractText(obj: PointObject, atMost: Int?) = "" }
        val docx = object : DocxWriter { override suspend fun write(paragraphs: List<String>) = ScratchRef("/x") }
        val result = WordRealizer(testKnowledge(pdf), docx, noOcr).perform(pdfObj(), null)
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("распознайте"))
    }

    private fun docxTo(path: String) = object : DocxWriter {
        override suspend fun write(paragraphs: List<String>) = ScratchRef(path)
    }

    @Test
    fun `на PDF слышно чтение текста и сборку документа`() = runTest {
        val pdf = object : PdfTextExtractor {
            override suspend fun extractText(obj: PointObject, atMost: Int?) = "Строка 1"
        }

        val heard = stagesHeard { WordRealizer(testKnowledge(pdf), docxTo("/tmp/out.docx"), noOcr).perform(pdfObj(), null) }

        assertEquals(listOf("Читаю текст PDF", "Собираю документ"), heard)
    }

    @Test
    fun `фото сперва распознаётся — и это сказано`() = runTest {
        val pdf = object : PdfTextExtractor { override suspend fun extractText(obj: PointObject, atMost: Int?) = "" }
        val ocr = object : TextRecognizer {
            override suspend fun recognize(obj: PointObject) = "Чек"
        }

        val heard = stagesHeard { WordRealizer(testKnowledge(pdf), docxTo("/tmp/out.docx"), ocr).perform(imageObj(), null) }

        assertEquals(listOf("Распознаю текст на фото", "Собираю документ"), heard)
    }

    @Test
    fun `у скана без текста сборки не было — и слова о ней нет`() = runTest {
        val pdf = object : PdfTextExtractor { override suspend fun extractText(obj: PointObject, atMost: Int?) = "" }

        val heard = stagesHeard { WordRealizer(testKnowledge(pdf), docxTo("/x"), noOcr).perform(pdfObj(), null) }

        assertEquals(listOf("Читаю текст PDF"), heard)
    }

    @Test
    fun `toParagraphs splits on lines and normalises CRLF`() {
        assertEquals(listOf("a", "b", "", "c"), toParagraphs("a\r\nb\n\nc"))
    }
}
