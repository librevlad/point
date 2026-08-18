package com.point.executors

import com.point.core.flow.ObjectStore
import com.point.core.flow.OfficeTextExtractor
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

class OfficeActionTest {

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

    private fun extractor(text: String) = object : OfficeTextExtractor {
        override suspend fun extractText(obj: PointObject) = text
    }

    private val docx = PointObject(
        id = "d",
        mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        uri = ScratchRef("/tmp/акт.docx"),
        state = ObjectState(ObjectKind.OFFICE),
    )

    @Test
    fun `разбор документа называет себя теми же словами, что и «В PDF»`() = runTest {
        val heard = stagesHeard { OfficeRealizer(store, extractor("Акт выполненных работ")).perform(docx, null) }

        assertEquals(listOf(OFFICE_READ_STAGE), heard)
    }

    @Test
    fun `извлечённый текст становится текстовым объектом`() = runTest {
        val result = OfficeRealizer(store, extractor("Акт выполненных работ")).perform(docx, null)

        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.TEXT, out.type)
        assertEquals("Акт выполненных работ", File(out.uri.value).readText())
    }

    @Test
    fun `старый формат отдаёт пустоту — отказ с причиной, а не тихий пустой объект`() = runTest {
        val result = OfficeRealizer(store, extractor("")).perform(docx, null)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
        assertTrue(result.reason.contains(".doc"))
    }
}
