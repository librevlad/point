package com.point.data

import com.point.core.flow.KIND_ORGANIZATION
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ScratchRef
import com.point.core.model.ValueRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classified roles becoming graph nodes (#222, шаг 6) — the half of the pipeline the model has
 * no say in. It contributed a pointer at a line; kind, relation and id are decided here.
 */
class GraphMetadataEnricherTest {

    private val enricher = GraphMetadataEnricher()

    private fun doc(vararg roles: Pair<String, String>, kind: ObjectKind = ObjectKind.TEXT) =
        PointObject(
            "cmr", "text/plain", ScratchRef("/tmp/cmr.txt"), ObjectState(kind),
            metadata = roles.associate { (k, v) -> META_GRAPH_ROLE_PREFIX + k to v },
        )

    @Test
    fun `a classified sender becomes an Organization with the sender relation`() = runTest {
        val delta = enricher.enrich(doc("sender" to "ТОВ «Агротрейд»"))

        val org = delta.objects.single()
        assertEquals(KIND_ORGANIZATION, org.state.kind)
        assertEquals(ValueRef("ТОВ «Агротрейд»"), org.uri)
        assertEquals(listOf(Relation(org.id, RelationType.SENDER, "cmr")), delta.relations)
    }

    @Test
    fun `a model's reading is marked less certain than a rule's`() = runTest {
        val org = enricher.enrich(doc("carrier" to "Нова Пошта")).objects.single()

        assertTrue("нужно честно сказать, что это прочтение модели", org.confidence < 1f)
        assertEquals("classifier", org.creatorAction)
        assertEquals(listOf("cmr"), org.sourceObjects)
    }

    @Test
    fun `one organisation in two roles is one node with two relations`() = runTest {
        // A carrier that also issued the waybill is one company, and the graph should say so.
        val delta = enricher.enrich(doc("carrier" to "Нова Пошта", "issuer" to "Нова Пошта"))

        assertEquals(1, delta.objects.size)
        assertEquals(
            setOf(RelationType.CARRIER, RelationType.ISSUED_BY),
            delta.relations.mapTo(mutableSetOf()) { it.type },
        )
        assertTrue(delta.relations.all { it.fromId == delta.objects.single().id })
    }

    @Test
    fun `spacing and case do not split one company into two`() = runTest {
        val a = enricher.enrich(doc("sender" to "Нова  Пошта")).objects.single()
        val b = enricher.enrich(doc("carrier" to "нова пошта")).objects.single()

        assertEquals(a.id, b.id)
    }

    @Test
    fun `different documents keep their own nodes`() = runTest {
        val a = enricher.enrich(doc("sender" to "Нова Пошта")).objects.single()
        val b = enricher.enrich(
            doc("sender" to "Нова Пошта").copy(id = "other"),
        ).objects.single()

        assertTrue(a.id != b.id)
    }

    @Test
    fun `an object with no classification yields nothing`() = runTest {
        val delta = enricher.enrich(
            PointObject("x", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT)),
        )

        assertTrue(delta.objects.isEmpty())
        assertTrue(delta.relations.isEmpty())
        assertTrue(delta.features.isEmpty())
    }

    @Test
    fun `a blank role value is not a node`() = runTest {
        assertTrue(enricher.enrich(doc("sender" to "   ")).objects.isEmpty())
    }

    @Test
    fun `an organisation is not classified out of again`() = runTest {
        val org = doc("sender" to "Нова Пошта", kind = KIND_ORGANIZATION)

        assertTrue(enricher.enrich(org).objects.isEmpty())
    }

    @Test
    fun `re-running after a restart rebuilds the same graph, asking the model nothing`() = runTest {
        // The point of writing findings to metadata: the journal keeps them, so a restored flow
        // gets its nodes back with the same ids and the paid call is paid for once.
        val first = enricher.enrich(doc("receiver" to "ТОВ «Агротрейд»"))
        val again = enricher.enrich(doc("receiver" to "ТОВ «Агротрейд»"))

        assertEquals(first.objects.map { it.id }, again.objects.map { it.id })
        assertEquals(first.relations, again.relations)
    }
}
