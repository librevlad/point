package com.point.data

import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
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

/**
 * The first extractor that returns objects instead of flags (#222). What it proves is not the
 * regex — that is covered in `IdentifiersTest` — but the shape of the contract: a value becomes
 * a graph node with provenance, a confidence and a stable id.
 */
class IdentifierEnricherTest {

    @get:Rule val tmp = TemporaryFolder()

    private val enricher = IdentifierEnricher()

    private fun textObject(content: String, id: String = "src"): PointObject {
        val f = File(tmp.root, "$id.txt").apply { writeText(content) }
        return PointObject(id, "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    @Test
    fun `a waybill on a parcel screenshot becomes an object of kind Identifier`() = runTest {
        val obj = textObject("Прибула в пункт 1\n20 4514 9154 9395\nзберігання до 29.07")

        val delta = enricher.enrich(obj)

        assertEquals(1, delta.objects.size)
        assertEquals(KIND_IDENTIFIER, delta.objects.single().state.kind)
    }

    @Test
    fun `the value is the content — no file is invented for it`() = runTest {
        val obj = textObject("ТТН 20 4514 9154 9395")

        val found = enricher.enrich(obj).objects.single()

        assertEquals(ValueRef("20 4514 9154 9395"), found.uri)
        assertEquals("20 4514 9154 9395", found.uri.value)
    }

    @Test
    fun `provenance points back at the page it was read from`() = runTest {
        val obj = textObject("ТТН 20 4514 9154 9395", id = "page1")

        val delta = enricher.enrich(obj)
        val found = delta.objects.single()

        assertEquals(listOf("page1"), found.sourceObjects)
        assertEquals("identifier-enricher", found.creatorAction)
        assertEquals(
            listOf(com.point.core.model.Relation(found.id, RelationType.FOUND_IN, "page1")),
            delta.relations,
        )
    }

    @Test
    fun `the reading is marked structural, not certain`() = runTest {
        // No published check-digit algorithm went into the rule, and the graph must carry that.
        val found = enricher.enrich(textObject("20 4514 9154 9395")).objects.single()

        assertTrue("structural match must stay below certainty", found.confidence < 1f)
    }

    @Test
    fun `the id is deterministic, so re-running enrichment does not double the graph`() = runTest {
        val obj = textObject("ТТН 20 4514 9154 9395")

        val first = enricher.enrich(obj).objects.single().id
        val again = enricher.enrich(obj).objects.single().id

        assertEquals(first, again)
    }

    @Test
    fun `the same number written with and without spaces is one object, not two`() = runTest {
        // The id keys on digits alone: «20 4514 9154 9395» and «20451491549395» are one thing.
        val spaced = enricher.enrich(textObject("20 4514 9154 9395", id = "a")).objects.single()
        val plain = enricher.enrich(textObject("20451491549395", id = "a")).objects.single()

        assertEquals(spaced.id, plain.id)
    }

    @Test
    fun `ids from different pages do not collide`() = runTest {
        val a = enricher.enrich(textObject("20 4514 9154 9395", id = "pageA")).objects.single()
        val b = enricher.enrich(textObject("20 4514 9154 9395", id = "pageB")).objects.single()

        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `a text with no identifiers yields nothing at all`() = runTest {
        val delta = enricher.enrich(textObject("Позвони мне на +380671234567 завтра"))

        assertTrue(delta.objects.isEmpty())
        assertTrue(delta.relations.isEmpty())
        assertTrue("must not flag features either", delta.features.isEmpty())
    }

    @Test
    fun `applies to text only — an image is handled after OCR turns it into text`() {
        assertTrue(enricher.appliesTo(ObjectState(ObjectKind.TEXT)))
        assertTrue(!enricher.appliesTo(ObjectState(ObjectKind.IMAGE)))
        assertTrue(!enricher.appliesTo(ObjectState(ObjectKind.PDF)))
    }

    @Test
    fun `declares the kind it may yield, so the slow gate can reason about it`() {
        assertEquals(setOf(KIND_IDENTIFIER), enricher.meta.mayYieldKinds)
    }

    @Test
    fun `a missing file is not a crash`() = runTest {
        val ghost = PointObject("x", "text/plain", ScratchRef("/nowhere/gone.txt"), ObjectState(ObjectKind.TEXT))

        assertTrue(enricher.enrich(ghost).objects.isEmpty())
    }
}
