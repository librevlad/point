package com.point.executors

import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfTextExtractor
import com.point.core.model.ExecutorResult
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

/**
 * The PDF->text path is pure JVM (extractor + scratch file IO), so it is tested
 * directly with fakes — no device, no real PdfBox.
 */
class PdfExecutorTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private fun extractorReturning(text: String) = object : PdfTextExtractor {
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
        val executor = PdfExecutor(store, extractorReturning("Привет из PDF"))
        val result = executor.execute(pdfObject())

        assertTrue(result is ExecutorResult.Success)
        val out = (result as ExecutorResult.Success).result
        assertEquals(ObjectKind.TEXT, out.type)
        assertEquals("Привет из PDF", File(out.uri.value).readText())
    }

    @Test
    fun `scanned pdf with no text is a recoverable failure`() = runTest {
        val executor = PdfExecutor(store, extractorReturning("   "))
        val result = executor.execute(pdfObject())

        assertTrue(result is ExecutorResult.Failure)
        assertTrue((result as ExecutorResult.Failure).recoverable)
    }
}
