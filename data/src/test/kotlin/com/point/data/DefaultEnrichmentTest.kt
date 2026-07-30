package com.point.data

import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ScratchRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The progressive enrichment scheduler: cheap waves first, findings emitted as each
 * enricher completes (not one batch at the end), SLOW work gated on whether its
 * possible features could open any new action, running labels reported for UI feedback.
 */
class DefaultEnrichmentTest {

    private val obj = PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    private fun bubble(id: String) = Bubble("icon", id, CapabilityId(id), ObjectState(ObjectKind.IMAGE))

    /** A registry whose graph is driven by a lambda — enough for the gate. */
    private fun registry(bubbles: (ObjectState) -> List<Bubble>) = object : CapabilityRegistry {
        override fun bubblesFor(state: ObjectState) = bubbles(state)
        override fun intentsFor(state: ObjectState) = emptyList<Intent>()
        override fun latentBubblesFor(state: ObjectState) = emptyList<LatentBubble>()
        override fun byId(id: CapabilityId) = throw UnsupportedOperationException()
    }

    /** A registry where entity features always open one more action — the common real case. */
    private val openingRegistry = registry { state ->
        if (state.features.isEmpty()) listOf(bubble("base")) else listOf(bubble("base"), bubble("entity"))
    }

    private class FakeEnricher(
        override val meta: EnricherMeta,
        private val kind: ObjectKind = ObjectKind.IMAGE,
        private val delayMs: Long = 0,
        private val delta: EnrichmentDelta = EnrichmentDelta(),
        private val fail: Boolean = false,
    ) : Enricher {
        var started = false
        override fun appliesTo(state: ObjectState) = state.kind == kind
        override suspend fun enrich(obj: PointObject): EnrichmentDelta {
            started = true
            delay(delayMs)
            if (fail) error("boom")
            return delta
        }
    }

    @Test
    fun `emits findings progressively as each enricher completes`() = runTest {
        val quick = FakeEnricher(
            EnricherMeta(cost = EnrichCost.FAST),
            delayMs = 10,
            delta = EnrichmentDelta(setOf(Feature.HAS_QR)),
        )
        val slow = FakeEnricher(
            EnricherMeta(cost = EnrichCost.FAST),
            delayMs = 100,
            delta = EnrichmentDelta(setOf(Feature.HAS_PHONE)),
        )
        val updates = DefaultEnrichment(setOf(quick, slow), openingRegistry).enrich(obj).toList()

        val featureSteps = updates.map { it.features }.distinct()
        // First the quick enricher's finding alone, then both — never one final batch only.
        assertTrue(featureSteps.contains(setOf(Feature.HAS_QR)))
        assertEquals(setOf(Feature.HAS_QR, Feature.HAS_PHONE), featureSteps.last())
    }

    @Test
    fun `runs cheaper waves before expensive ones`() = runTest {
        var slowStartedAt = -1L
        val instant = FakeEnricher(
            EnricherMeta(cost = EnrichCost.INSTANT),
            delayMs = 50,
            delta = EnrichmentDelta(setOf(Feature.HAS_URL)),
        )
        val slow = object : Enricher {
            override val meta = EnricherMeta(cost = EnrichCost.SLOW)
            override fun appliesTo(state: ObjectState) = true
            override suspend fun enrich(obj: PointObject): EnrichmentDelta {
                slowStartedAt = currentTime
                return EnrichmentDelta(setOf(Feature.HAS_PHONE))
            }
        }
        DefaultEnrichment(setOf(slow, instant), openingRegistry).enrich(obj).toList()
        assertTrue("SLOW must start only after the INSTANT wave", slowStartedAt >= 50)
    }

    @Test
    fun `skips a slow enricher whose features cannot open new actions`() = runTest {
        val gated = FakeEnricher(
            EnricherMeta(cost = EnrichCost.SLOW, mayYield = setOf(Feature.HAS_PHONE)),
            delta = EnrichmentDelta(setOf(Feature.HAS_PHONE)),
        )
        // The graph never changes with features → the expensive look-inside is pointless.
        val flat = registry { listOf(bubble("base")) }
        val updates = DefaultEnrichment(setOf(gated), flat).enrich(obj).toList()

        assertFalse(gated.started)
        assertTrue(updates.last().features.isEmpty())
    }

    @Test
    fun `runs a slow enricher when its features open new actions`() = runTest {
        val gated = FakeEnricher(
            EnricherMeta(cost = EnrichCost.SLOW, mayYield = setOf(Feature.HAS_PHONE)),
            delta = EnrichmentDelta(setOf(Feature.HAS_PHONE)),
        )
        val updates = DefaultEnrichment(setOf(gated), openingRegistry).enrich(obj).toList()

        assertTrue(gated.started)
        assertEquals(setOf(Feature.HAS_PHONE), updates.last().features)
    }

    @Test
    fun `slow gate sees the features found by cheaper waves`() = runTest {
        val fast = FakeEnricher(
            EnricherMeta(cost = EnrichCost.FAST),
            delta = EnrichmentDelta(setOf(Feature.HAS_URL)),
        )
        val gated = FakeEnricher(
            EnricherMeta(cost = EnrichCost.SLOW, mayYield = setOf(Feature.HAS_URL)),
            delta = EnrichmentDelta(setOf(Feature.HAS_URL)),
        )
        // HAS_URL opens an extra action only while the state does not have it yet — so after
        // the FAST wave found it, the SLOW enricher that could only re-find it must be skipped.
        val urlRegistry = registry { state ->
            if (state.has(Feature.HAS_URL)) listOf(bubble("base"), bubble("open-url"))
            else listOf(bubble("base"))
        }
        DefaultEnrichment(setOf(fast, gated), urlRegistry).enrich(obj).toList()

        assertTrue(fast.started)
        assertFalse("gate must consider features already found", gated.started)
    }

    @Test
    fun `reports running labels while a labelled enricher works and clears them at the end`() = runTest {
        val labelled = FakeEnricher(
            EnricherMeta(cost = EnrichCost.SLOW, label = "Распознаю текст…"),
            delayMs = 10,
            delta = EnrichmentDelta(setOf(Feature.HAS_PHONE)),
        )
        val updates = DefaultEnrichment(setOf(labelled), openingRegistry).enrich(obj).toList()

        assertTrue(updates.any { "Распознаю текст…" in it.running })
        assertTrue(updates.last().running.isEmpty())
    }

    @Test
    fun `merges metadata from deltas`() = runTest {
        val withMeta = FakeEnricher(
            EnricherMeta(cost = EnrichCost.FAST),
            delta = EnrichmentDelta(setOf(Feature.HAS_PHONE), mapOf("ocr.text.ref" to "/tmp/ocr.txt")),
        )
        val updates = DefaultEnrichment(setOf(withMeta), openingRegistry).enrich(obj).toList()

        assertEquals("/tmp/ocr.txt", updates.last().metadata["ocr.text.ref"])
    }

    @Test
    fun `a failing enricher contributes nothing and does not break the rest`() = runTest {
        val failing = FakeEnricher(EnricherMeta(cost = EnrichCost.FAST), fail = true)
        val healthy = FakeEnricher(
            EnricherMeta(cost = EnrichCost.FAST),
            delta = EnrichmentDelta(setOf(Feature.HAS_QR)),
        )
        val updates = DefaultEnrichment(setOf(failing, healthy), openingRegistry).enrich(obj).toList()

        assertEquals(setOf(Feature.HAS_QR), updates.last().features)
    }

    @Test
    fun `skips enrichers that do not apply to the state`() = runTest {
        val textOnly = FakeEnricher(
            EnricherMeta(cost = EnrichCost.FAST),
            kind = ObjectKind.TEXT,
            delta = EnrichmentDelta(setOf(Feature.HAS_EMAIL)),
        )
        val updates = DefaultEnrichment(setOf(textOnly), openingRegistry).enrich(obj).toList()

        assertFalse(textOnly.started)
        assertTrue(updates.last().features.isEmpty())
    }

    // ── Extraction: objects and relations (#222) ────────────────────────────────────────

    private val identifierKind = ObjectKind.of("Identifier")

    private fun extracted(id: String, kind: ObjectKind) =
        PointObject(id, "text/plain", ScratchRef("/tmp/$id"), ObjectState(kind), sourceObjects = listOf("id"))

    @Test
    fun `runs a slow extractor that yields a new kind even when no action opens`() = runTest {
        // The case the old gate got wrong. A waybill number opens no new action at all —
        // judging by actions alone would skip the extractor that finds the single most useful
        // thing on a parcel screenshot.
        val extractor = FakeEnricher(
            EnricherMeta(cost = EnrichCost.SLOW, mayYieldKinds = setOf(identifierKind)),
            delta = EnrichmentDelta(objects = listOf(extracted("ttn", identifierKind))),
        )
        val flat = registry { listOf(bubble("base")) } // the action list never changes

        val updates = DefaultEnrichment(setOf(extractor), flat).enrich(obj).toList()

        assertTrue("an object is worth finding even with no new action", extractor.started)
        assertEquals(listOf(identifierKind), updates.last().objects.map { it.state.kind })
    }

    @Test
    fun `skips a slow extractor whose kind the graph already has`() = runTest {
        val fast = FakeEnricher(
            EnricherMeta(cost = EnrichCost.FAST),
            delta = EnrichmentDelta(objects = listOf(extracted("ttn", identifierKind))),
        )
        val slow = FakeEnricher(
            EnricherMeta(cost = EnrichCost.SLOW, mayYieldKinds = setOf(identifierKind)),
            delta = EnrichmentDelta(objects = listOf(extracted("ttn2", identifierKind))),
        )
        val flat = registry { listOf(bubble("base")) }

        DefaultEnrichment(setOf(fast, slow), flat).enrich(obj).toList()

        assertTrue(fast.started)
        assertFalse("the cheap wave already produced this kind", slow.started)
    }

    @Test
    fun `a slow extractor still runs when its features open an action, whatever it yields`() = runTest {
        // Both halves of the gate are honoured: features OR kinds is enough.
        val gated = FakeEnricher(
            EnricherMeta(
                cost = EnrichCost.SLOW,
                mayYield = setOf(Feature.HAS_PHONE),
                mayYieldKinds = setOf(identifierKind),
            ),
            delta = EnrichmentDelta(setOf(Feature.HAS_PHONE)),
        )
        val fast = FakeEnricher(
            EnricherMeta(cost = EnrichCost.FAST),
            delta = EnrichmentDelta(objects = listOf(extracted("ttn", identifierKind))),
        )

        DefaultEnrichment(setOf(fast, gated), openingRegistry).enrich(obj).toList()

        assertTrue("the kind is taken, but the feature still opens an action", gated.started)
    }

    @Test
    fun `objects and relations accumulate across waves and survive into the final update`() = runTest {
        val org = extracted("org1", ObjectKind.of("Organization"))
        val instant = FakeEnricher(
            EnricherMeta(cost = EnrichCost.INSTANT),
            delta = EnrichmentDelta(objects = listOf(extracted("ttn", identifierKind))),
        )
        val fast = FakeEnricher(
            EnricherMeta(cost = EnrichCost.FAST),
            delta = EnrichmentDelta(
                objects = listOf(org),
                relations = listOf(Relation("ttn", RelationType.ISSUED_BY, "org1")),
            ),
        )
        val updates = DefaultEnrichment(setOf(instant, fast), openingRegistry).enrich(obj).toList()

        assertEquals(listOf("ttn", "org1"), updates.last().objects.map { it.id })
        assertEquals(
            listOf(Relation("ttn", RelationType.ISSUED_BY, "org1")),
            updates.last().relations,
        )
    }

    @Test
    fun `an extractor that declares nothing still runs, as before`() = runTest {
        // Unknown yield must keep meaning «run rather than guess» — the old contract.
        val undeclared = FakeEnricher(
            EnricherMeta(cost = EnrichCost.SLOW),
            delta = EnrichmentDelta(objects = listOf(extracted("x", identifierKind))),
        )
        val flat = registry { listOf(bubble("base")) }

        DefaultEnrichment(setOf(undeclared), flat).enrich(obj).toList()

        assertTrue(undeclared.started)
    }
}
