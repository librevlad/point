package com.point.data

import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ScratchRef
import com.point.core.model.ValueRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityObjectsTest {

    private fun source(
        id: String = "src",
        kind: ObjectKind = ObjectKind.TEXT,
    ) = PointObject(id, "text/plain", ScratchRef("/tmp/$id"), ObjectState(kind))

    private fun facts(vararg pairs: Pair<String, String>) =
        pairs.associate { (k, v) -> META_ENTITY_PREFIX + k to v }

    @Test
    fun `an address fact becomes an Address that carries HAS_ADDRESS`() {
        val (objects, _) = entityObjects(source(), facts("address" to "Відділення №9, Київ"), "t")

        val address = objects.single()
        assertEquals(KIND_ADDRESS, address.state.kind)

        assertTrue(address.state.has(Feature.HAS_ADDRESS))
    }

    @Test
    fun `the value is the content — no file is invented for it`() {
        val (objects, _) = entityObjects(source(), facts("date" to "29.07 до 18:00"), "t")

        val date = objects.single()
        assertEquals(KIND_DATE, date.state.kind)
        assertEquals(ValueRef("29.07 до 18:00"), date.uri)
    }

    @Test
    fun `provenance points back at the object it was read from`() {
        val (objects, relations) = entityObjects(source(id = "page1"), facts("phone" to "+380671112233"), "t")

        val phone = objects.single()
        assertEquals(listOf("page1"), phone.sourceObjects)
        assertEquals("t", phone.creatorAction)
        assertEquals(listOf(Relation(phone.id, RelationType.FOUND_IN, "page1")), relations)
    }

    @Test
    fun `a card number deliberately does not become an object`() {

        val (objects, _) = entityObjects(source(), facts("card" to "5375 4141 1234 5678"), "t")

        assertTrue(objects.isEmpty())
    }

    @Test
    fun `the same fact from the extractor and from stored metadata is one node`() {
        val live = entityObjects(source(id = "p"), facts("address" to "Київ"), "entity").first.single()
        val stored = entityObjects(source(id = "p"), facts("address" to "Київ"), "metadata").first.single()

        assertEquals(live.id, stored.id)
    }

    @Test
    fun `an address does not contain itself`() {

        val address = source(id = "a", kind = KIND_ADDRESS)
            .copy(metadata = facts("address" to "Київ"))

        val (objects, relations) = entityObjects(address, address.metadata, "t")

        assertTrue(objects.isEmpty())
        assertTrue(relations.isEmpty())
    }

    @Test
    fun `an object keeps its own value in metadata, so its screen can show it`() {
        val phone = entityObjects(source(), facts("phone" to "+380671112233"), "t").first.single()

        assertEquals("+380671112233", phone.metadata[META_ENTITY_PREFIX + "phone"])
    }

    @Test
    fun `several facts become several things, none invented`() {
        val (objects, relations) = entityObjects(
            source(),
            facts("address" to "Київ", "date" to "29.07", "email" to "  "),
            "t",
        )

        assertEquals(setOf(KIND_ADDRESS, KIND_DATE), objects.mapTo(mutableSetOf()) { it.state.kind })
        assertEquals(2, relations.size)
        assertNull(objects.firstOrNull { it.uri.value.isBlank() })
    }

    @Test
    fun `stored metadata alone rebuilds the graph after a restart`() = runTest {

        val restored = source(id = "restored").copy(metadata = facts("address" to "Київ"))

        val delta = MetadataEntityInvestigationRealizer().look(restored)

        assertEquals(KIND_ADDRESS, delta.objects.single().state.kind)
        assertEquals("restored", delta.objects.single().sourceObjects.single())
    }

    @Test
    fun `a disputed fact becomes an object that says it is disputed`() {
        val disputed = com.point.core.flow.mergeFacts(
            facts("address" to "вул. Хрещатик, 1"),
            facts("address" to "вул. Хрещатик, 7"),
        )

        val obj = entityObjects(source(), disputed, "t").first.single()

        assertEquals("вул. Хрещатик, 1", obj.uri.value)

        assertTrue(
            "спорное значение не должно выглядеть решённым",
            com.point.core.flow.isDoubtful(obj.metadata),
        )
        assertEquals(
            listOf("вул. Хрещатик, 1", "вул. Хрещатик, 7"),
            com.point.core.flow.alternativesOf(obj.metadata, "entity.address"),
        )
    }

    @Test
    fun `an agreed fact stays certain and carries no alternatives`() {
        val agreed = com.point.core.flow.mergeFacts(
            facts("address" to "вул. Хрещатик, 1"),
            facts("address" to "вул.  Хрещатик 1"),
        )

        val obj = entityObjects(source(), agreed, "t").first.single()

        assertTrue(!com.point.core.flow.isDoubtful(obj.metadata))
        assertTrue(com.point.core.flow.alternativesOf(obj.metadata, "entity.address").isEmpty())
    }

    @Test
    fun `узел наследует происхождение и улики своего факта, а не выдумывает их`() {
        val judged = facts("address" to "вул. Хрещатик, 1") + mapOf(
            "entity.address" + com.point.core.flow.META_SOURCE_SUFFIX to Provenance.OCR.wire,
            "entity.address" + com.point.core.flow.META_EVIDENCE_SUFFIX to "semantic,geometric",
        )

        val obj = entityObjects(source(), judged, "t").first.single()

        assertEquals(Provenance.OCR, obj.provenance)
        assertEquals("semantic,geometric", obj.metadata["entity.address.ev"])

        assertTrue(!com.point.core.flow.isDoubtful(obj.metadata))
    }

    @Test
    fun `факт без происхождения не получает выдуманного — молчим, а не врём`() {
        val obj = entityObjects(source(), facts("phone" to "+380671112233"), "t").first.single()

        assertEquals(Provenance.GIVEN, obj.provenance)
        assertNull(obj.metadata["entity.phone.src"])
    }

    @Test
    fun `правку человека узел не понижает до чтения модели`() {

        val edited = facts("address" to "вул. Хрещатик, 1") +
            mapOf("entity.address" + com.point.core.flow.META_SOURCE_SUFFIX to Provenance.HUMAN.wire)

        val obj = entityObjects(source(), edited, "metadata-entity-enricher").first.single()

        assertEquals(Provenance.HUMAN, obj.provenance)
    }

    @Test
    fun `поле узла и его src не расходятся — один источник истины`() {
        listOf(Provenance.OCR, Provenance.MODEL, Provenance.RULE, Provenance.HUMAN).forEach { p ->
            val stamped = facts("address" to "Київ") +
                mapOf("entity.address" + com.point.core.flow.META_SOURCE_SUFFIX to p.wire)

            val obj = entityObjects(source(), stamped, "t").first.single()

            assertEquals(p, obj.provenance)
            assertEquals(
                obj.provenance,
                com.point.core.flow.provenanceOf(obj.metadata, "entity.address"),
            )
        }
    }
}
