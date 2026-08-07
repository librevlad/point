package com.point.data

import com.point.core.flow.KIND_ORGANIZATION
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
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
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `роль присуждает модель — узел говорит это словом, а не числом 0_7`() = runTest {
        val org = enricher.enrich(doc("carrier" to "Нова Пошта")).objects.single()

        assertEquals(Provenance.MODEL, org.provenance)
        assertEquals("прочитано моделью", com.point.core.flow.provenanceLabel(org.provenance))
        assertEquals("classifier", org.creatorAction)
        assertEquals(listOf("cmr"), org.sourceObjects)
    }

    @Test
    fun `поле узла и его src не расходятся — один источник истины`() = runTest {
        val org = enricher.enrich(doc("carrier" to "Нова Пошта")).objects.single()

        assertEquals("Нова Пошта", org.metadata[META_GRAPH_ROLE_PREFIX + "carrier"])
        assertEquals(
            org.provenance,
            com.point.core.flow.provenanceOf(org.metadata, META_GRAPH_ROLE_PREFIX + "carrier"),
        )
    }

    @Test
    fun `записанное происхождение сильнее фолбэка — правку человека модель не переписывает`() = runTest {

        val srcKey = META_GRAPH_ROLE_PREFIX + "carrier" + com.point.core.flow.META_SOURCE_SUFFIX
        val base = doc("carrier" to "Нова Пошта")
        val edited = base.copy(metadata = base.metadata + (srcKey to Provenance.HUMAN.wire))

        val org = enricher.enrich(edited).objects.single()

        assertEquals(Provenance.HUMAN, org.provenance)
        assertEquals("подтверждено вами", com.point.core.flow.provenanceLabel(org.provenance))
    }

    @Test
    fun `спор об имени роли виден и на самом узле, а не только на документе`() = runTest {
        val altKey = META_GRAPH_ROLE_PREFIX + "sender" + com.point.core.flow.META_ALT_SUFFIX
        val readings = com.point.core.flow.altValue(listOf("1ваненко ван", "Зовсім Інша Людина"))
        val base = doc("sender" to "1ваненко ван")
        val disputed = base.copy(metadata = base.metadata + (altKey to readings))

        val node = enricher.enrich(disputed).objects.single()

        assertTrue(com.point.core.flow.isDoubtful(node.metadata))
    }

    @Test
    fun `one organisation in two roles is one node with two relations`() = runTest {

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

        val first = enricher.enrich(doc("receiver" to "ТОВ «Агротрейд»"))
        val again = enricher.enrich(doc("receiver" to "ТОВ «Агротрейд»"))

        assertEquals(first.objects.map { it.id }, again.objects.map { it.id })
        assertEquals(first.relations, again.relations)
    }
}
