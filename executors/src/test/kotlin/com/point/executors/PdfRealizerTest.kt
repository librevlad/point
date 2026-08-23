package com.point.executors

import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfTextExtractor
import com.point.core.model.ActionResult
import com.point.core.model.Feature
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

    /** Текст PDF — знание самого документа, а не второй объект рядом с ним (#995). */
    @Test
    fun `pdf with text hands the text to the document itself`() = runTest {
        val realizer = PdfRealizer(store, pdfExtractor("Привет из PDF"))
        val result = realizer.perform(pdfObject())

        assertTrue("вышло: $result", result is ActionResult.Done)
        val found = (result as ActionResult.Done).findings
        assertTrue(Feature.HAS_TEXT in found!!.features)
        assertEquals(
            "Привет из PDF",
            File(found.metadata[com.point.core.flow.META_OCR_TEXT_REF]!!).readText(),
        )
    }

    /**
     * Из файла текст не достаётся — и повторять тут нечего (#1257).
     *
     * Отказ зовёт «Прочитать документ»: страницы читает то действие, которое объявляет
     * долгую работу вслух. «Попробуйте ещё раз» было бы неправдой — файл не изменится.
     */
    @Test
    fun `pdf without a usable layer names the step that exists`() = runTest {
        val realizer = PdfRealizer(store, pdfExtractor("   "))
        val result = realizer.perform(pdfObject())

        assertTrue("вышло: $result", result is ActionResult.Failure)
        val said = (result as ActionResult.Failure).reason
        assertTrue(said, ReadDocumentCapability().label(ObjectState(ObjectKind.PDF)) in said)
        assertTrue("повторять нечего — файл не изменится", !result.recoverable)
    }

    /** Офисный файл этому исполнителю не по зубам — и он честно за него не берётся (#403). */
    @Test
    fun `офисный документ телефон в PDF не превращает`() {
        val realizer = PdfRealizer(store, pdfExtractor(""))

        assertTrue(realizer.accepts(ObjectState(ObjectKind.PDF)))
        assertTrue(
            "телефон снова берётся пересказывать документ",
            !realizer.accepts(ObjectState(ObjectKind.OFFICE)),
        )
    }

    @Test
    fun `извлечение текста из PDF называет себя`() = runTest {
        val realizer = PdfRealizer(store, pdfExtractor("Привет из PDF"))

        val heard = stagesHeard { realizer.perform(pdfObject()) }

        assertEquals(listOf("Извлекаю текст из PDF"), heard)
    }
}
