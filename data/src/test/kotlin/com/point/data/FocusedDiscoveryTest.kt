package com.point.data

import com.point.core.flow.Box
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.CloudScope
import com.point.core.flow.Focus
import com.point.core.flow.GraphState
import com.point.core.flow.InvestigationState
import com.point.core.flow.Latency
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.flow.investigationStateOf
import com.point.core.flow.withFocus
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.Intent
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused-проход цикла (ADR-0001 §10, RFC §10): Focus запускает только исследования,
 * ставшие применимыми из-за него, а их состояние живёт под ключом своей области.
 */
class FocusedDiscoveryTest {

    private val photo = PointObject("photo", "image/jpeg", ScratchRef("/tmp/p.jpg"), ObjectState(ObjectKind.IMAGE))

    private val areaA = Focus("photo", region = Box(0f, 0f, 100f, 50f))

    private val areaB = Focus("photo", region = Box(0f, 100f, 100f, 150f))

    private fun focused(obj: PointObject, focus: Focus) =
        obj.copy(metadata = withFocus(obj.metadata, focus))

    /** Исследование как в проде: декларация + исполнитель. */
    private class Look(
        val id: CapabilityId,
        latency: Latency = Latency.FAST,
        private val takesState: (ObjectState) -> Boolean = { false },
        private val takesGraph: ((GraphState) -> Boolean)? = null,
        private val delta: Findings = Findings(),
        private val fail: Boolean = false,
    ) {
        var started = false

        val capability: Capability = object : Capability {
            override val id = this@Look.id
            override val icon = ""
            override val meta = CapabilityMeta(investigation = true, latency = latency)
            override fun label(state: ObjectState) = ""
            override fun accepts(state: ObjectState) = takesState(state)
            override fun accepts(graph: GraphState) = takesGraph?.invoke(graph) ?: accepts(graph.state)
            override fun produces(state: ObjectState) = state
        }

        val realizer: Realizer = object : Realizer {
            override val capabilityId = this@Look.id
            override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
                started = true
                if (fail) error("boom")
                return ActionResult.Done("", delta)
            }
        }
    }

    private fun enrichmentOf(vararg looks: Look): DefaultEnrichment {
        val registry = object : CapabilityRegistry {
            override fun all(): Collection<Capability> = looks.map { it.capability }
            override fun bubblesFor(state: ObjectState) = emptyList<Bubble>()
            override fun latentBubblesFor(state: ObjectState) = emptyList<LatentBubble>()
            override fun byId(id: CapabilityId) = looks.first { it.id == id }.capability
        }
        val resolver = object : Resolver {
            override fun realizerFor(capabilityId: CapabilityId): Realizer =
                looks.first { it.id == capabilityId }.realizer

            override fun leavesDevice(capabilityId: CapabilityId) = false
        }
        val consent = object : PrivacyConsent {
            override suspend fun allowed(scope: CloudScope) = false
            override suspend fun allow(scope: CloudScope) = Unit
            override suspend fun revoke(scope: CloudScope) = Unit
        }
        return DefaultEnrichment(registry, resolver, consent)
    }

    private fun regionLook(
        id: String = "region-look",
        latency: Latency = Latency.FAST,
        delta: Findings = Findings(),
        fail: Boolean = false,
    ) = Look(
        CapabilityId(id),
        latency = latency,
        takesState = { false },
        takesGraph = { it.focus != null && it.state.kind == ObjectKind.IMAGE },
        delta = delta,
        fail = fail,
    )

    @Test
    fun `focus runs only what became applicable because of it`() = runTest {
        val regional = regionLook(delta = Findings(setOf(Feature.HAS_PHONE)))

        val alwaysOn = Look(CapabilityId("qr-like"), takesState = { it.kind == ObjectKind.IMAGE })
        val updates = enrichmentOf(regional, alwaysOn).enrich(focused(photo, areaA)).toList()

        assertTrue("областное исследование обязано запуститься", regional.started)
        assertFalse("kind-исследование не перезапускается из-за Focus", alwaysOn.started)
        assertEquals(setOf(Feature.HAS_PHONE), updates.last().features)
    }

    @Test
    fun `without focus the regional investigation is not applicable at all`() = runTest {
        val regional = regionLook()
        enrichmentOf(regional).enrich(photo).toList()

        assertFalse(regional.started)
    }

    @Test
    fun `focus A and focus B keep separate scoped states, the global one untouched`() = runTest {
        val regional = regionLook(delta = Findings(setOf(Feature.HAS_PHONE)))
        val loop = enrichmentOf(regional)

        val afterA = loop.enrich(focused(photo, areaA)).toList().last().metadata
        assertEquals(InvestigationState.FOUND, investigationStateOf(afterA, regional.id, areaA))
        assertEquals(InvestigationState.NOT_INVESTIGATED, investigationStateOf(afterA, regional.id))

        val afterB = loop.enrich(focused(photo.copy(metadata = photo.metadata + afterA), areaB))
            .toList().last().metadata
        assertEquals(InvestigationState.FOUND, investigationStateOf(afterB, regional.id, areaB))
        assertEquals(InvestigationState.NOT_INVESTIGATED, investigationStateOf(afterB, regional.id))
    }

    @Test
    fun `focused not found means nothing about the whole object`() = runTest {

        val regional = regionLook(delta = Findings())
        val last = enrichmentOf(regional).enrich(focused(photo, areaA)).toList().last().metadata

        assertEquals(InvestigationState.NOT_FOUND, investigationStateOf(last, regional.id, areaA))
        assertEquals(
            "глобальное состояние не смеет знать про область",
            InvestigationState.NOT_INVESTIGATED,
            investigationStateOf(last, regional.id),
        )
    }

    @Test
    fun `focused failure stays a failure of that area`() = runTest {
        val regional = regionLook(fail = true)
        val last = enrichmentOf(regional).enrich(focused(photo, areaA)).toList().last()

        assertEquals(listOf(regional.id), last.failed.map { it.id })
        assertEquals(InvestigationState.NOT_INVESTIGATED, investigationStateOf(last.metadata, regional.id, areaA))
        assertEquals(InvestigationState.NOT_INVESTIGATED, investigationStateOf(last.metadata, regional.id))
    }

    @Test
    fun `a slow question answered for area A is still open for area B`() = runTest {
        val first = regionLook(id = "slow-look", latency = Latency.SLOW, delta = Findings(setOf(Feature.HAS_PHONE)))
        val afterA = enrichmentOf(first).enrich(focused(photo, areaA)).toList().last().metadata
        assertTrue(first.started)

        val second = regionLook(id = "slow-look", latency = Latency.SLOW, delta = Findings(setOf(Feature.HAS_PHONE)))
        enrichmentOf(second).enrich(focused(photo.copy(metadata = photo.metadata + afterA), areaB)).toList()

        assertTrue("FOUND области A не закрывает вопрос области B", second.started)
    }

    @Test
    fun `the same slow area is not asked twice`() = runTest {
        val first = regionLook(id = "slow-look", latency = Latency.SLOW, delta = Findings(setOf(Feature.HAS_PHONE)))
        val afterA = enrichmentOf(first).enrich(focused(photo, areaA)).toList().last().metadata

        val again = regionLook(id = "slow-look", latency = Latency.SLOW)
        enrichmentOf(again).enrich(focused(photo.copy(metadata = photo.metadata + afterA), areaA)).toList()

        assertFalse("та же область с FOUND не переспрашивается", again.started)
    }
}
