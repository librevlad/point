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

    /** Текст остаётся у самого документа, а не уезжает во второй объект (#995). */
    @Test
    fun `извлечённый текст становится знанием документа`() = runTest {
        val result = OfficeRealizer(store, extractor("Акт выполненных работ")).perform(docx, null)

        val found = (result as ActionResult.Done).findings
        assertTrue(com.point.core.model.Feature.HAS_TEXT in found!!.features)
        assertEquals(
            "Акт выполненных работ",
            File(found.metadata[com.point.core.flow.META_OCR_TEXT_REF]!!).readText(),
        )
    }

    /**
     * Причина названа про этот файл, а не про чужой формат (#997).
     *
     * Раньше современная .docx слышала «старые .doc и .xls компьютер не открывает» — причину,
     * которая к ней не относится, и человек искал несуществующую проблему формата.
     */
    @Test
    fun `пустой документ отказывает с причиной, а не тихим пустым объектом`() = runTest {
        val result = OfficeRealizer(store, extractor("")).perform(docx, null)

        assertTrue("вышло: $result", result is ActionResult.Failure)
        val said = (result as ActionResult.Failure).reason
        assertTrue("причина свалена на чужой формат: $said", ".doc " !in said && ".xls" !in said)
        assertTrue("сказано, что случилось, но не что дальше: $said", said.split(" — ", ". ").size >= 2)
    }
}
