package com.point.data

import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CloudScope
import com.point.core.flow.Latency
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.flow.InvestigationState
import com.point.core.flow.alternativesOf
import com.point.core.flow.investigationStateOf
import com.point.core.flow.withInvestigation
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

class DefaultEnrichmentTest {

    private val obj = PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    private fun bubble(id: String) = Bubble("icon", id, CapabilityId(id), ObjectState(ObjectKind.IMAGE))

    private fun registry(bubbles: (ObjectState) -> List<Bubble>) = object : CapabilityRegistry {
        override fun bubblesFor(state: ObjectState) = bubbles(state)
        override fun all() = emptyList<com.point.core.flow.Capability>()
        override fun latentBubblesFor(state: ObjectState) = emptyList<LatentBubble>()
        override fun byId(id: CapabilityId) = throw UnsupportedOperationException()
    }

    private val openingRegistry = registry { state ->
        if (state.features.isEmpty()) listOf(bubble("base")) else listOf(bubble("base"), bubble("entity"))
    }

    /** Исследование в проде- это пара Capability + Realizer, поэтому и фейк такой же. */
    private class Look(
        meta: CapabilityMeta,
        kind: ObjectKind = ObjectKind.IMAGE,
        private val delayMs: Long = 0,
        delta: Findings = Findings(),
        private val fail: Boolean = false,
        val id: CapabilityId = CapabilityId("fake-" + delta.hashCode()),
        label: String = "",
        private val outcome: ActionResult? = null,
    ) {
        var started = false

        val capability: Capability = object : Capability {
            override val id = this@Look.id
            override val icon = ""
            override val meta = meta
            override fun label(state: ObjectState) = label
            override fun accepts(state: ObjectState) = state.kind == kind
            override fun produces(state: ObjectState) = state
        }

        val realizer: Realizer = object : Realizer {
            override val capabilityId = this@Look.id
            override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
                started = true
                delay(delayMs)
                if (fail) error("boom")
                return outcome ?: ActionResult.Done("", delta)
            }
        }
    }

    private fun enrichmentOf(
        looks: Set<Look>,
        registry: CapabilityRegistry = openingRegistry,
        cloud: Set<CapabilityId> = emptySet(),
        consented: Boolean = false,
    ): DefaultEnrichment {
        val known = registry.all().toList() + looks.map { it.capability }
        val full = object : CapabilityRegistry by registry {
            override fun all(): Collection<Capability> = known
        }
        val resolver = object : Resolver {
            override fun realizerFor(capabilityId: CapabilityId): Realizer =
                looks.first { it.id == capabilityId }.realizer

            override fun realizerFor(capabilityId: CapabilityId, state: ObjectState): Realizer =
                realizerFor(capabilityId)

            override fun leavesDevice(capabilityId: CapabilityId) = capabilityId in cloud
        }
        val consent = object : PrivacyConsent {
            override suspend fun allowed(scope: CloudScope) = consented
            override suspend fun allow(scope: CloudScope) = Unit
            override suspend fun revoke(scope: CloudScope) = Unit
        }
        return DefaultEnrichment(full, resolver, consent, com.point.core.flow.DEFAULT_PHONE_REGION)
    }

    @Test
    fun `emits findings progressively as each enricher completes`() = runTest {
        val quick = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delayMs = 10,
            delta = Findings(setOf(Feature.HAS_QR)),
        )
        val slow = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delayMs = 100,
            delta = Findings(setOf(Feature.HAS_PHONE)),
        )
        val updates = enrichmentOf(setOf(quick, slow), openingRegistry).enrich(obj).toList()

        val featureSteps = updates.map { it.features }.distinct()

        assertTrue(featureSteps.contains(setOf(Feature.HAS_QR)))
        assertEquals(setOf(Feature.HAS_QR, Feature.HAS_PHONE), featureSteps.last())
    }

    /**
     * #1242: человек нажал «Прочитать сильнее», облако ответило за секунды — а начатое до
     * того чтение того же снимка грело телефон до конца своего бюджета и клало поверх
     * сильного своё слабое.
     */
    @Test
    fun `ответ со стороны прерывает своё исследование — и только его (#1242)`() = runTest {
        val reading = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delayMs = 100_000,
            delta = Findings(setOf(Feature.HAS_TEXT)),
            id = CapabilityId("image-text"),
            label = "Распознаю текст…",
        )
        val neighbour = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delayMs = 200,
            delta = Findings(setOf(Feature.HAS_QR)),
            id = CapabilityId("qr-content"),
        )
        val answeredElsewhere = kotlinx.coroutines.flow.flow {
            delay(50)
            emit(CapabilityId("image-text"))
        }

        val updates = enrichmentOf(setOf(reading, neighbour)).enrich(obj, answeredElsewhere).toList()

        assertTrue("телефон дочитывал уже отвеченное: $currentTime мс", currentTime < 100_000)
        assertEquals(
            "соседний вопрос выбросили вместе с отменённым",
            setOf(Feature.HAS_QR),
            updates.last().features,
        )
        assertFalse(
            "знание прерванного чтения всё равно легло",
            Feature.HAS_TEXT in updates.last().features,
        )
        assertTrue("прерванное стало упрёком: ${updates.last().failed}", updates.last().failed.isEmpty())
        assertTrue("исследование осталось идущим", updates.last().running.isEmpty())
    }

    @Test
    fun `чужой ответ не трогает исследование другого вопроса (#1242)`() = runTest {
        val reading = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delayMs = 100,
            delta = Findings(setOf(Feature.HAS_TEXT)),
            id = CapabilityId("image-text"),
        )
        val answeredElsewhere = kotlinx.coroutines.flow.flow {
            delay(10)
            emit(CapabilityId("qr-content"))
        }

        val updates = enrichmentOf(setOf(reading)).enrich(obj, answeredElsewhere).toList()

        assertEquals(setOf(Feature.HAS_TEXT), updates.last().features)
    }

    @Test
    fun `runs cheaper waves before expensive ones`() = runTest {
        var slowStartedAt = -1L
        val instant = Look(
            CapabilityMeta(investigation = true, latency = Latency.INSTANT),
            delayMs = 50,
            delta = Findings(setOf(Feature.HAS_URL)),
        )
        val slow = Look(
            CapabilityMeta(investigation = true, latency = Latency.SLOW),
            kind = ObjectKind.IMAGE,
            delta = Findings(setOf(Feature.HAS_PHONE)),
            id = CapabilityId("slow-wave"),
        )
        val updates = enrichmentOf(setOf(slow, instant), openingRegistry).enrich(obj).toList()
        slowStartedAt = if (updates.last().features.contains(Feature.HAS_PHONE)) currentTime else -1

        assertTrue("SLOW must start only after the INSTANT wave", slowStartedAt >= 50)
    }

    @Test
    fun `skips a slow enricher whose features cannot open new actions`() = runTest {
        val gated = Look(
            CapabilityMeta(investigation = true, latency = Latency.SLOW, mayYield = setOf(Feature.HAS_PHONE)),
            delta = Findings(setOf(Feature.HAS_PHONE)),
        )

        val flat = registry { listOf(bubble("base")) }
        val updates = enrichmentOf(setOf(gated), flat).enrich(obj).toList()

        assertFalse(gated.started)
        assertTrue(updates.last().features.isEmpty())
    }

    @Test
    fun `runs a slow enricher when its features open new actions`() = runTest {
        val gated = Look(
            CapabilityMeta(investigation = true, latency = Latency.SLOW, mayYield = setOf(Feature.HAS_PHONE)),
            delta = Findings(setOf(Feature.HAS_PHONE)),
        )
        val updates = enrichmentOf(setOf(gated), openingRegistry).enrich(obj).toList()

        assertTrue(gated.started)
        assertEquals(setOf(Feature.HAS_PHONE), updates.last().features)
    }

    @Test
    fun `slow gate sees the features found by cheaper waves`() = runTest {
        val fast = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(setOf(Feature.HAS_URL)),
        )
        val gated = Look(
            CapabilityMeta(investigation = true, latency = Latency.SLOW, mayYield = setOf(Feature.HAS_URL)),
            delta = Findings(setOf(Feature.HAS_URL)),
        )

        val urlRegistry = registry { state ->
            if (state.has(Feature.HAS_URL)) listOf(bubble("base"), bubble("open-url"))
            else listOf(bubble("base"))
        }
        enrichmentOf(setOf(fast, gated), urlRegistry).enrich(obj).toList()

        assertTrue(fast.started)
        assertFalse("gate must consider features already found", gated.started)
    }

    @Test
    fun `reports running labels while a labelled enricher works and clears them at the end`() = runTest {
        val labelled = Look(
            CapabilityMeta(investigation = true, latency = Latency.SLOW), label = "Распознаю текст…",
            delayMs = 10,
            delta = Findings(setOf(Feature.HAS_PHONE)),
        )
        val updates = enrichmentOf(setOf(labelled), openingRegistry).enrich(obj).toList()

        assertTrue(updates.any { "Распознаю текст…" in it.running })
        assertTrue(updates.last().running.isEmpty())
    }

    @Test
    fun `merges metadata from deltas`() = runTest {
        val withMeta = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(setOf(Feature.HAS_PHONE), mapOf("ocr.text.ref" to "/tmp/ocr.txt")),
        )
        val updates = enrichmentOf(setOf(withMeta), openingRegistry).enrich(obj).toList()

        assertEquals("/tmp/ocr.txt", updates.last().metadata["ocr.text.ref"])
    }

    @Test
    fun `a failing enricher contributes nothing and does not break the rest`() = runTest {
        val failing = Look(CapabilityMeta(investigation = true, latency = Latency.FAST), fail = true)
        val healthy = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(setOf(Feature.HAS_QR)),
        )
        val updates = enrichmentOf(setOf(failing, healthy), openingRegistry).enrich(obj).toList()

        assertEquals(setOf(Feature.HAS_QR), updates.last().features)
    }

    @Test
    fun `skips enrichers that do not apply to the state`() = runTest {
        val textOnly = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            kind = ObjectKind.TEXT,
            delta = Findings(setOf(Feature.HAS_EMAIL)),
        )
        val updates = enrichmentOf(setOf(textOnly), openingRegistry).enrich(obj).toList()

        assertFalse(textOnly.started)
        assertTrue(updates.last().features.isEmpty())
    }

    private val identifierKind = ObjectKind.of("Identifier")

    private fun extracted(id: String, kind: ObjectKind) =
        PointObject(id, "text/plain", ScratchRef("/tmp/$id"), ObjectState(kind), sourceObjects = listOf("id"))

    @Test
    fun `runs a slow extractor that yields a new kind even when no action opens`() = runTest {

        val extractor = Look(
            CapabilityMeta(investigation = true, latency = Latency.SLOW, mayYieldKinds = setOf(identifierKind)),
            delta = Findings(objects = listOf(extracted("ttn", identifierKind))),
        )
        val flat = registry { listOf(bubble("base")) }

        val updates = enrichmentOf(setOf(extractor), flat).enrich(obj).toList()

        assertTrue("an object is worth finding even with no new action", extractor.started)
        assertEquals(listOf(identifierKind), updates.last().objects.map { it.state.kind })
    }

    @Test
    fun `skips a slow extractor whose kind the graph already has`() = runTest {
        val fast = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(objects = listOf(extracted("ttn", identifierKind))),
        )
        val slow = Look(
            CapabilityMeta(investigation = true, latency = Latency.SLOW, mayYieldKinds = setOf(identifierKind)),
            delta = Findings(objects = listOf(extracted("ttn2", identifierKind))),
        )
        val flat = registry { listOf(bubble("base")) }

        enrichmentOf(setOf(fast, slow), flat).enrich(obj).toList()

        assertTrue(fast.started)
        assertFalse("the cheap wave already produced this kind", slow.started)
    }

    @Test
    fun `a slow extractor still runs when its features open an action, whatever it yields`() = runTest {

        val gated = Look(
            CapabilityMeta(
                investigation = true,
                latency = Latency.SLOW,
                mayYield = setOf(Feature.HAS_PHONE),
                mayYieldKinds = setOf(identifierKind),
            ),
            delta = Findings(setOf(Feature.HAS_PHONE)),
        )
        val fast = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(objects = listOf(extracted("ttn", identifierKind))),
        )

        enrichmentOf(setOf(fast, gated), openingRegistry).enrich(obj).toList()

        assertTrue("the kind is taken, but the feature still opens an action", gated.started)
    }

    @Test
    fun `objects and relations accumulate across waves and survive into the final update`() = runTest {
        val org = extracted("org1", ObjectKind.of("Organization"))
        val instant = Look(
            CapabilityMeta(investigation = true, latency = Latency.INSTANT),
            delta = Findings(objects = listOf(extracted("ttn", identifierKind))),
        )
        val fast = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(
                objects = listOf(org),
                relations = listOf(Relation("ttn", RelationType.ISSUED_BY, "org1")),
            ),
        )
        val updates = enrichmentOf(setOf(instant, fast), openingRegistry).enrich(obj).toList()

        assertEquals(listOf("ttn", "org1"), updates.last().objects.map { it.id })
        assertEquals(
            listOf(Relation("ttn", RelationType.ISSUED_BY, "org1")),
            updates.last().relations,
        )
    }

    @Test
    fun `an extractor that declares nothing still runs, as before`() = runTest {

        val undeclared = Look(
            CapabilityMeta(investigation = true, latency = Latency.SLOW),
            delta = Findings(objects = listOf(extracted("x", identifierKind))),
        )
        val flat = registry { listOf(bubble("base")) }

        enrichmentOf(setOf(undeclared), flat).enrich(obj).toList()

        assertTrue(undeclared.started)
    }

    private val qr = CapabilityId("qr-look")

    private fun looked(delta: Findings = Findings(), fail: Boolean = false) = Look(
        CapabilityMeta(investigation = true, latency = Latency.FAST),
        delta = delta,
        fail = fail,
        id = qr,
    )

    @Test
    fun `a question nobody asked stays not investigated`() = runTest {
        val other = Look(CapabilityMeta(investigation = true, latency = Latency.FAST), delta = Findings(setOf(Feature.HAS_URL)))
        val updates = enrichmentOf(setOf(other), openingRegistry).enrich(obj).toList()

        assertEquals(InvestigationState.NOT_INVESTIGATED, investigationStateOf(updates.last().metadata, qr))
    }

    @Test
    fun `a finished look that found nothing is not found, not silence`() = runTest {
        val updates = enrichmentOf(setOf(looked()), openingRegistry).enrich(obj).toList()

        assertEquals(InvestigationState.NOT_FOUND, investigationStateOf(updates.last().metadata, qr))
    }

    @Test
    fun `a finished look that brought a value is found`() = runTest {
        val updates = enrichmentOf(setOf(looked(Findings(setOf(Feature.HAS_QR), mapOf("entity.qr" to "https-//example.org")))), openingRegistry).enrich(obj).toList()

        assertEquals(InvestigationState.FOUND, investigationStateOf(updates.last().metadata, qr))
    }

    @Test
    fun `a look that brought only a feature is found too`() = runTest {
        val updates = enrichmentOf(setOf(looked(Findings(setOf(Feature.HAS_QR)))), openingRegistry).enrich(obj).toList()

        assertEquals(InvestigationState.FOUND, investigationStateOf(updates.last().metadata, qr))
    }

    @Test
    fun `two sources disagreeing leave both readings and mark both investigations contradictory`() = runTest {
        val first = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(metadata = mapOf("entity.phone" to "+380671234567")),
            id = CapabilityId("reader-a"),
        )
        val second = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(metadata = mapOf("entity.phone" to "+380671234599")),
            id = CapabilityId("reader-b"),
        )
        val metadata = enrichmentOf(setOf(first, second), openingRegistry).enrich(obj).toList().last().metadata

        val kept = alternativesOf(metadata, "entity.phone") + metadata.getValue("entity.phone")
        assertTrue("оба прочтения обязаны остаться-$kept", kept.containsAll(listOf("+380671234567", "+380671234599")))
        assertEquals(InvestigationState.CONTRADICTORY, investigationStateOf(metadata, CapabilityId("reader-a")))
        assertEquals(InvestigationState.CONTRADICTORY, investigationStateOf(metadata, CapabilityId("reader-b")))
    }

    @Test
    fun `an investigation runs through the resolver, not through a registry of its own`() = runTest {
        val look = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(setOf(Feature.HAS_QR)),
            id = CapabilityId("through-resolver"),
        )
        val updates = enrichmentOf(setOf(look)).enrich(obj).toList()

        assertTrue("исполнителя обязан выдать Resolver", look.started)
        assertEquals(setOf(Feature.HAS_QR), updates.last().features)
    }

    @Test
    fun `an investigation may bring several objects with their relations`() = runTest {
        val a = extracted("photo-a", identifierKind)
        val b = extracted("photo-b", identifierKind)
        val look = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(
                objects = listOf(a, b),
                relations = listOf(
                    Relation(a.id, RelationType.FOUND_IN, obj.id),
                    Relation(b.id, RelationType.FOUND_IN, obj.id),
                ),
            ),
            id = CapabilityId("two-at-once"),
        )
        val last = enrichmentOf(setOf(look)).enrich(obj).toList().last()

        assertEquals(listOf("photo-a", "photo-b"), last.objects.map { it.id })
        assertEquals(2, last.relations.size)
        assertEquals(InvestigationState.FOUND, investigationStateOf(last.metadata, CapabilityId("two-at-once")))
    }

    @Test
    fun `an external investigation is not run while consent is missing`() = runTest {
        val outside = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(setOf(Feature.HAS_QR)),
            id = CapabilityId("cloud-look"),
        )
        val silent = enrichmentOf(setOf(outside), cloud = setOf(outside.id), consented = false)
        val last = silent.enrich(obj).toList().last()

        assertFalse("объект не уходит наружу без согласия", outside.started)
        assertEquals(
            InvestigationState.NOT_INVESTIGATED,
            investigationStateOf(last.metadata, CapabilityId("cloud-look")),
        )
    }

    @Test
    fun `the same external investigation runs once consent is given`() = runTest {
        val outside = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(setOf(Feature.HAS_QR)),
            id = CapabilityId("cloud-look"),
        )
        enrichmentOf(setOf(outside), cloud = setOf(outside.id), consented = true).enrich(obj).toList()

        assertTrue(outside.started)
    }

    @Test
    fun `an investigation waiting for the human is not a failure and not an answer`() = runTest {
        val asks = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            id = CapabilityId("asking"),
            outcome = com.point.core.model.ActionResult.NeedsInput("Что здесь написано?"),
        )
        val last = enrichmentOf(setOf(asks)).enrich(obj).toList().last()

        assertEquals(listOf(CapabilityId("asking")), last.awaiting.map { it.id })
        assertTrue("ожидание — не провал", last.failed.isEmpty())
        assertEquals(
            InvestigationState.NOT_INVESTIGATED,
            investigationStateOf(last.metadata, CapabilityId("asking")),
        )
    }

    @Test
    fun `an investigation that returns an object instead of knowledge is a failed operation`() = runTest {
        val wrong = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            id = CapabilityId("wrong-shape"),
            outcome = com.point.core.model.ActionResult.Success(
                com.point.core.model.ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/tmp/x.txt")),
            ),
        )
        val last = enrichmentOf(setOf(wrong)).enrich(obj).toList().last()

        assertEquals(listOf(CapabilityId("wrong-shape")), last.failed.map { it.id })
        assertEquals(
            InvestigationState.NOT_INVESTIGATED,
            investigationStateOf(last.metadata, CapabilityId("wrong-shape")),
        )
    }

    @Test
    fun `an expensive question already answered is not asked again`() = runTest {
        val pricey = Look(
            CapabilityMeta(investigation = true, latency = Latency.SLOW),
            delta = Findings(setOf(Feature.HAS_QR)),
            id = CapabilityId("pricey-look"),
        )
        val asked = obj.copy(
            metadata = withInvestigation(emptyMap(), CapabilityId("pricey-look"), InvestigationState.FOUND),
        )

        enrichmentOf(setOf(pricey), openingRegistry).enrich(asked).toList()

        assertFalse("уже отвеченный вопрос не задают заново", pricey.started)
    }

    @Test
    fun `дорогой вопрос, ответивший «не нашлось», тоже не задают заново (#669)`() = runTest {
        // Решение владельца: «исследовано, не нашлось» — знание, а не пустое место. Тессеракт
        // (SLOW) не гоняется заново при каждом входе в неизменный объект: батарея и время.
        val pricey = Look(
            CapabilityMeta(investigation = true, latency = Latency.SLOW),
            delta = Findings(setOf(Feature.HAS_QR)),
            id = CapabilityId("pricey-look"),
        )
        val asked = obj.copy(
            metadata = withInvestigation(emptyMap(), CapabilityId("pricey-look"), InvestigationState.NOT_FOUND),
        )

        enrichmentOf(setOf(pricey), openingRegistry).enrich(asked).toList()

        assertFalse("«смотрели — не нашлось» не повод смотреть снова", pricey.started)
    }

    @Test
    fun `дорогой вопрос, исследованный недостаточно, остаётся открытым (#669)`() = runTest {
        // «Недостаточно» буквально значит, что смотреть ещё есть смысл — в отличие от «не нашлось».
        val pricey = Look(
            CapabilityMeta(investigation = true, latency = Latency.SLOW),
            delta = Findings(setOf(Feature.HAS_QR)),
            id = CapabilityId("pricey-look"),
        )
        val asked = obj.copy(
            metadata = withInvestigation(
                emptyMap(),
                CapabilityId("pricey-look"),
                InvestigationState.INSUFFICIENTLY_INVESTIGATED,
            ),
        )

        enrichmentOf(setOf(pricey), openingRegistry).enrich(asked).toList()

        assertTrue("недостаточно исследованное — повод посмотреть ещё раз", pricey.started)
    }

    @Test
    fun `a question answered as not found is still open for a cheap re-ask`() = runTest {
        val cheap = Look(
            CapabilityMeta(investigation = true, latency = Latency.FAST),
            delta = Findings(setOf(Feature.HAS_QR)),
            id = CapabilityId("cheap-look"),
        )
        val asked = obj.copy(
            metadata = withInvestigation(emptyMap(), CapabilityId("cheap-look"), InvestigationState.NOT_FOUND),
        )

        enrichmentOf(setOf(cheap), openingRegistry).enrich(asked).toList()

        assertTrue(cheap.started)
    }

    @Test
    fun `a look that crashed never becomes not found`() = runTest {
        val updates = enrichmentOf(setOf(looked(fail = true)), openingRegistry).enrich(obj).toList()
        val last = updates.last()

        assertEquals(InvestigationState.NOT_INVESTIGATED, investigationStateOf(last.metadata, qr))
        assertEquals(listOf(qr), last.failed.map { it.id })
    }

    @Test
    fun `a crash keeps the knowledge another look had already found`() = runTest {
        val good = Look(
            CapabilityMeta(investigation = true, latency = Latency.INSTANT),
            delta = Findings(metadata = mapOf("entity.phone" to "+380671234567")),
            id = CapabilityId("reader-a"),
        )
        val updates = enrichmentOf(setOf(good, looked(fail = true)), openingRegistry).enrich(obj).toList()

        assertEquals("+380671234567", updates.last().metadata["entity.phone"])
        assertEquals(InvestigationState.FOUND, investigationStateOf(updates.last().metadata, CapabilityId("reader-a")))
    }
}
