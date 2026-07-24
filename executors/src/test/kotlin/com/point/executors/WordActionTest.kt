package com.point.executors

import com.point.core.flow.DocxWriter
import com.point.core.flow.PdfTextExtractor
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

    @Test
    fun `pdf text becomes a docx OFFICE object, one paragraph per line`() = runTest {
        val pdf = object : PdfTextExtractor {
            override suspend fun extractText(obj: PointObject) = "Строка 1\nСтрока 2"
        }
        var paras: List<String>? = null
        val docx = object : DocxWriter {
            override suspend fun write(paragraphs: List<String>): ScratchRef {
                paras = paragraphs
                return ScratchRef("/tmp/out.docx")
            }
        }
        val result = WordRealizer(pdf, docx).perform(pdfObj(), null)
        assertTrue(result is ActionResult.Success)
        val obj = (result as ActionResult.Success).result
        assertEquals(ObjectKind.OFFICE, obj.type)
        assertTrue(obj.mime.endsWith("wordprocessingml.document"))
        assertEquals(listOf("Строка 1", "Строка 2"), paras)
    }

    @Test
    fun `a scanned pdf with no text fails with an OCR hint`() = runTest {
        val pdf = object : PdfTextExtractor { override suspend fun extractText(obj: PointObject) = "" }
        val docx = object : DocxWriter { override suspend fun write(paragraphs: List<String>) = ScratchRef("/x") }
        val result = WordRealizer(pdf, docx).perform(pdfObj(), null)
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("распознайте"))
    }

    @Test
    fun `toParagraphs splits on lines and normalises CRLF`() {
        assertEquals(listOf("a", "b", "", "c"), toParagraphs("a\r\nb\n\nc"))
    }
}
