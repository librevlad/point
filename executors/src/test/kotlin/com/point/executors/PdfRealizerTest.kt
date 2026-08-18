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
        override suspend fun put(
            result: ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
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

    @Test
    fun `извлечение текста из PDF называет себя`() = runTest {
        val realizer = PdfRealizer(store, pdfExtractor("Привет из PDF"), NoPages)

        val heard = stagesHeard { realizer.perform(pdfObject()) }

        assertEquals(listOf("Извлекаю текст из PDF"), heard)
    }
}
