package com.point.data

import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.RelationType
import com.point.core.model.ScratchRef
import com.point.core.model.ValueRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IdentifierInvestigationTest {

    @get:Rule val tmp = TemporaryFolder()

    private val enricher = IdentifierInvestigationRealizer()

    private fun textObject(content: String, id: String = "src"): PointObject {
        val f = File(tmp.root, "$id.txt").apply { writeText(content) }
        return PointObject(id, "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    @Test
    fun `a waybill on a parcel screenshot becomes an object of kind Identifier`() = runTest {
        val obj = textObject("Прибула в пункт 1\n20 4514 9154 9395\nзберігання до 29.07")

        val delta = enricher.look(obj)

        assertEquals(1, delta.objects.size)
        assertEquals(KIND_IDENTIFIER, delta.objects.single().state.kind)
    }

    @Test
    fun `the value is the content — no file is invented for it`() = runTest {
        val obj = textObject("ТТН 20 4514 9154 9395")

        val found = enricher.look(obj).objects.single()

        assertEquals(ValueRef("20 4514 9154 9395"), found.uri)
        assertEquals("20 4514 9154 9395", found.uri.value)
    }

    @Test
    fun `provenance points back at the page it was read from`() = runTest {
        val obj = textObject("ТТН 20 4514 9154 9395", id = "page1")

        val delta = enricher.look(obj)
        val found = delta.objects.single()

        assertEquals(listOf("page1"), found.sourceObjects)
        assertEquals("identifier-enricher", found.creatorAction)
        assertEquals(
            listOf(com.point.core.model.Relation(found.id, RelationType.FOUND_IN, "page1")),
            delta.relations,
        )
    }

    @Test
    fun `узел говорит «прочитано» — правило нашло цифры дословно на странице`() = runTest {

        val found = enricher.look(textObject("20 4514 9154 9395")).objects.single()

        assertEquals(Provenance.OCR, found.provenance)
        assertEquals(
            found.provenance,
            com.point.core.flow.provenanceOf(found.metadata, com.point.core.flow.META_ENTITY_TRACK),
        )
    }

    @Test
    fun `улика одна — форма, и узел показывает это как «возможно»`() = runTest {

        val found = enricher.look(textObject("20 4514 9154 9395")).objects.single()

        assertEquals("semantic", found.metadata["entity.track.ev"])
        assertTrue("одна улика — предположение", com.point.core.flow.isDoubtful(found.metadata))
    }

    @Test
    fun `второй настоящий номер — свой узел со своим значением, улики те же`() = runTest {

        val found = enricher.look(textObject("20 4514 9154 9395 та 20451491549396")).objects

        assertEquals(
            listOf("20 4514 9154 9395", "20451491549396"),
            found.map { it.metadata[com.point.core.flow.META_ENTITY_TRACK] },
        )
        assertTrue(found.all { it.provenance == Provenance.OCR })
    }

    @Test
    fun `the id is deterministic, so re-running enrichment does not double the graph`() = runTest {
        val obj = textObject("ТТН 20 4514 9154 9395")

        val first = enricher.look(obj).objects.single().id
        val again = enricher.look(obj).objects.single().id

        assertEquals(first, again)
    }

    @Test
    fun `the same number written with and without spaces is one object, not two`() = runTest {

        val spaced = enricher.look(textObject("20 4514 9154 9395", id = "a")).objects.single()
        val plain = enricher.look(textObject("20451491549395", id = "a")).objects.single()

        assertEquals(spaced.id, plain.id)
    }

    @Test
    fun `ids from different pages do not collide`() = runTest {
        val a = enricher.look(textObject("20 4514 9154 9395", id = "pageA")).objects.single()
        val b = enricher.look(textObject("20 4514 9154 9395", id = "pageB")).objects.single()

        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `a text with no identifiers yields nothing at all`() = runTest {
        val delta = enricher.look(textObject("Позвони мне на +380671234567 завтра"))

        assertTrue(delta.objects.isEmpty())
        assertTrue(delta.relations.isEmpty())
        assertTrue("must not flag features either", delta.features.isEmpty())
        assertTrue("and no metadata keys", delta.metadata.isEmpty())
    }

    @Test
    fun `трек уходит и в метаданные — схема «Отследить» читает факт, не граф`() = runTest {

        val delta = enricher.look(textObject("ТТН 20 4514 9154 9395"))

        assertEquals("20 4514 9154 9395", delta.metadata[com.point.core.flow.META_ENTITY_TRACK])
    }

    @Test
    fun `показание счётчика и координаты — факты того же дешёвого прохода`() = runTest {

        val delta = enricher.look(textObject("Вода 154 м³\nТочка 50.4501, 30.5234"))

        assertEquals("154", delta.metadata["entity.meter"])
        assertEquals("50.4501, 30.5234", delta.metadata["entity.geo"])
        assertTrue("узлов графа у них ещё нет", delta.objects.isEmpty())
    }

    @Test
    fun `applies to text only — an image is handled after OCR turns it into text`() {
        assertTrue(IdentifierInvestigation().accepts(ObjectState(ObjectKind.TEXT)))
        assertTrue(!IdentifierInvestigation().accepts(ObjectState(ObjectKind.IMAGE)))
        assertTrue(!IdentifierInvestigation().accepts(ObjectState(ObjectKind.PDF)))
    }

    @Test
    fun `declares the kind it may yield, so the slow gate can reason about it`() {
        assertEquals(setOf(KIND_IDENTIFIER), IdentifierInvestigation().meta.mayYieldKinds)
    }

    @Test
    fun `a missing file is a failure, not an empty answer — and still not a crash`() = runTest {
        val ghost = PointObject("x", "text/plain", ScratchRef("/nowhere/gone.txt"), ObjectState(ObjectKind.TEXT))

        val result = enricher.perform(ghost, null)

        assertTrue("нечитаемый payload обязан быть неудачей операции, получено-" + result,
            result is ActionResult.Failure)
    }
}
