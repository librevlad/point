package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.Focus
import com.point.core.flow.GraphState
import com.point.core.flow.LlmClient
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ScratchRef
import com.point.core.model.ValueRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0001 §14 — Action ranking смотрит на состояние целиком, а не на одну форму объекта.
 *
 * Знание, отношение и Focus обязаны уметь открывать действие без правки `Feature`,
 * а Intent — менять порядок, не убирая действия из списка.
 */
class GraphStateDrivesActionsTest {

    private val photo = PointObject("photo", "image/jpeg", ScratchRef("/scratch/p.jpg"), ObjectState(ObjectKind.IMAGE))

    private open class Fake(
        override val id: CapabilityId,
        private val takes: (GraphState) -> Boolean = { false },
        private val priority: Int = 50,
        private val serves: Set<Intent> = setOf(Intent.UNDERSTAND),
    ) : Capability {
        override val icon = "icon"
        override val meta = CapabilityMeta(priority = priority)
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = false
        override fun accepts(graph: GraphState) = takes(graph)
        override fun produces(state: ObjectState) = state
        override fun yields(state: ObjectState) = ActionYield.Same()
        override fun intents(state: ObjectState) = serves
    }

    private fun registryOf(vararg caps: Capability) = DefaultCapabilityRegistry(
        capabilities = caps.toSet(),
        policy = LearningBubblePolicy(
            usage = object : CapabilityUsage {
                override fun counts(): Map<CapabilityId, Int> = emptyMap()
                override suspend fun record(id: CapabilityId) = Unit
            },
            llm = object : LlmClient {
                override val configured = true
                override suspend fun run(input: PointObject, prompt: String) =
                    com.point.core.model.ResultObject(ObjectKind.TEXT, "text/plain", ValueRef(""))
            },
        
            com.point.core.flow.RememberingLinkMonitor(),
        ),
    )

    @Test
    fun `a fact that no Feature knows about can open an action`() {
        val plate = Fake(CapabilityId("plate-lookup"), takes = { it.fact("entity.plate") != null })
        val registry = registryOf(plate)

        val blank = GraphState(photo)
        assertTrue("без факта действие не при чём", registry.bubblesFor(blank).isEmpty())

        val known = GraphState(photo.copy(metadata = mapOf("entity.plate" to "AA1234BB")))
        assertEquals(listOf(CapabilityId("plate-lookup")), registry.bubblesFor(known).map { it.capabilityId })
    }

    @Test
    fun `a found object and its relation can open an action`() {
        val pair = Fake(
            CapabilityId("compare-two"),
            takes = { graph -> graph.found.size >= 2 && graph.relatedTo(graph.obj.id).size >= 2 },
        )
        val registry = registryOf(pair)

        val one = PointObject("photo:a", "text/plain", ValueRef("A"), ObjectState(ObjectKind.of("Vehicle")))
        val two = PointObject("photo:b", "text/plain", ValueRef("B"), ObjectState(ObjectKind.of("Vehicle")))

        assertTrue(registry.bubblesFor(GraphState(photo, found = listOf(one))).isEmpty())

        val both = GraphState(
            photo,
            found = listOf(one, two),
            relations = listOf(
                Relation(one.id, RelationType.FOUND_IN, photo.id),
                Relation(two.id, RelationType.FOUND_IN, photo.id),
            ),
        )
        assertEquals(listOf(CapabilityId("compare-two")), registry.bubblesFor(both).map { it.capabilityId })
    }

    @Test
    fun `focus can make an action applicable`() {
        val onRegion = Fake(CapabilityId("read-region"), takes = { it.focus != null })
        val registry = registryOf(onRegion)

        assertTrue(registry.bubblesFor(GraphState(photo)).isEmpty())
        assertFalse(
            registry.bubblesFor(GraphState(photo, focus = Focus(photo.id, com.point.core.flow.Box(0f, 0f, 10f, 10f))))
                .isEmpty(),
        )
    }

    @Test
    fun `intent changes the order and never drops an action`() {
        val understand = Fake(CapabilityId("read"), takes = { true }, priority = 90, serves = setOf(Intent.UNDERSTAND))
        val send = Fake(CapabilityId("send"), takes = { true }, priority = 10, serves = setOf(Intent.SEND))
        val registry = registryOf(understand, send)

        val byPriority = registry.bubblesFor(GraphState(photo)).map { it.capabilityId.value }
        assertEquals(listOf("send", "read"), byPriority)

        val withIntent = registry.bubblesFor(GraphState(photo, intent = Intent.UNDERSTAND))
        assertEquals(listOf("read", "send"), withIntent.map { it.capabilityId.value })
        assertEquals("действие не должно исчезать из-за Intent", 2, withIntent.size)
    }
}
