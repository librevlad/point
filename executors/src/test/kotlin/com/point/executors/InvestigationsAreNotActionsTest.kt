package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.GraphState
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0001 §11 — исследование объявлено как Capability, но человеку действием не предлагается:
 * его выбирает Discovery. При этом для Discovery и Resolver оно остаётся обычной Capability.
 */
class InvestigationsAreNotActionsTest {

    private class Declared(
        id: String,
        override val meta: CapabilityMeta,
    ) : Capability {
        override val id = CapabilityId(id)
        override val icon = ""
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
    }

    private val look = Declared("ocr-look", CapabilityMeta(investigation = true))
    private val action = Declared("share-it", CapabilityMeta())

    private val registry = DefaultCapabilityRegistry(setOf(look, action), DefaultBubblePolicy())

    private val image = ObjectState(ObjectKind.IMAGE)

    private val photo = PointObject("photo", "image/jpeg", ScratchRef("/scratch/p.jpg"), image)

    @Test
    fun `an investigation is never offered as a user action`() {
        assertEquals(listOf(action.id), registry.bubblesFor(image).map { it.capabilityId })
        assertEquals(listOf(action.id), registry.bubblesFor(GraphState(photo)).map { it.capabilityId })
    }

    @Test
    fun `discovery and the resolver still see the investigation as a capability`() {
        assertTrue(registry.all().any { it.id == look.id })
        assertEquals(look.id, registry.byId(look.id).id)
    }
}
