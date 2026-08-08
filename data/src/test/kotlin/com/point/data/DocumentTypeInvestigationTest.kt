package com.point.data

import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.TYPE_PARCEL
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocumentTypeInvestigationTest {

    @get:Rule val tmp = TemporaryFolder()

    private val enricher = DocumentTypeInvestigationRealizer()

    private fun textObject(content: String): PointObject {
        val f = File(tmp.root, "t.txt").apply { writeText(content) }
        return PointObject("src", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    @Test
    fun `a parcel notification is tagged as one`() = runTest {
        val delta = enricher.look(textObject("Посилка прибула у відділення №9\n20 4514 9154 9395"))

        assertEquals(TYPE_PARCEL, delta.metadata[META_SEMANTIC_TYPE])
    }

    @Test
    fun `it flags nothing and invents no object`() = runTest {

        val delta = enricher.look(textObject("Посилка прибула у відділення №9"))

        assertTrue(delta.features.isEmpty())
        assertTrue(delta.objects.isEmpty())
        assertTrue(delta.relations.isEmpty())
    }

    @Test
    fun `ordinary text is left unnamed`() = runTest {
        val delta = enricher.look(textObject("Купить молоко и позвонить маме"))

        assertTrue(delta.metadata.isEmpty())
    }

    @Test
    fun `applies to text only — an image is handled after OCR reads it`() {
        assertTrue(DocumentTypeInvestigation().accepts(ObjectState(ObjectKind.TEXT)))
        assertTrue(!DocumentTypeInvestigation().accepts(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `a missing file is a failure, not an empty answer — and still not a crash`() = runTest {
        val ghost = PointObject("x", "text/plain", ScratchRef("/nowhere/gone.txt"), ObjectState(ObjectKind.TEXT))

        val result = enricher.perform(ghost, null)

        assertTrue("нечитаемый payload обязан быть неудачей операции, получено-" + result,
            result is ActionResult.Failure)
    }
}
