package com.point.data

import com.point.core.model.Feature
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextUrlInvestigationTest {

    private val enricher = TextUrlInvestigationRealizer()

    private fun textObject(content: String): PointObject {
        val file = File.createTempFile("point-", ".txt").apply {
            writeText(content)
            deleteOnExit()
        }
        return PointObject(
            id = "id",
            mime = "text/plain",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.TEXT),
        )
    }

    @Test
    fun `flags HAS_URL when the text contains a link`() = runTest {
        val delta = enricher.look(textObject("смотри тут https://example.com дальше"))
        val features = delta.features
        assertEquals("https://example.com", delta.metadata[com.point.core.flow.META_ENTITY_PREFIX + "url"])
        assertTrue(Feature.HAS_URL in features)
    }

    @Test
    fun `no flag when there is no link`() = runTest {
        val features = enricher.look(textObject("просто текст без ссылок")).features
        assertFalse(Feature.HAS_URL in features)
    }

    /**
     * Ссылка, переданная файлом (`text/uri-list`), получала вид `URL` по MIME двери — и на
     * этом всё: адрес не читался, «Открыть ссылку» отвечало «Ссылка не найдена», а самого
     * адреса человек не видел нигде (#999).
     */
    @Test
    fun `ссылка, переданная файлом, знает свой адрес`() = runTest {
        val file = File.createTempFile("link-", ".txt").apply {
            writeText("https://example.com/pointtest?a=1")
            deleteOnExit()
        }
        val asFile = PointObject(
            id = "link",
            mime = "text/uri-list",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.URL),
        )

        assertTrue("дверь не зовёт чтение адреса", TextUrlInvestigation().accepts(asFile.state))

        val delta = enricher.look(asFile)

        assertEquals("https://example.com/pointtest?a=1", delta.metadata[com.point.core.flow.META_ENTITY_PREFIX + "url"])
        assertTrue(Feature.HAS_URL in delta.features)
    }

    /** У знания названо происхождение: вычитано из текста объекта, а не с кадра (#1024). */
    @Test
    fun `у прочитанного адреса названо происхождение`() = runTest {
        val delta = enricher.look(textObject("смотри тут https://example.com дальше"))

        assertEquals(
            com.point.core.model.Provenance.TEXT,
            com.point.core.flow.provenanceOf(delta.metadata, com.point.core.flow.META_ENTITY_PREFIX + "url"),
        )
    }

    @Test
    fun `a missing payload is a failure, not a text without links`() = runTest {
        val ghost = PointObject("x", "text/plain", ScratchRef("/nowhere/gone.txt"), ObjectState(ObjectKind.TEXT))

        val result = enricher.perform(ghost, null)

        assertTrue("нечитаемый payload обязан быть неудачей-" + result, result is ActionResult.Failure)
    }
}
