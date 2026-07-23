package com.point

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.Enrichment
import com.point.core.flow.FavoritesStore
import com.point.core.flow.HistoryStore
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.flow.UsageEvent
import com.point.core.flow.UsageEventType
import com.point.core.flow.UsageJournal
import com.point.core.flow.UsageSummary
import com.point.core.flow.UserAiConfig
import com.point.core.flow.UserKeyStore
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.FavoriteChain
import com.point.core.model.Feature
import com.point.core.model.HistoryEntry
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The flow engine — the hardest part of the app (which action, what next, back,
 * hints, chain replay) — tested on the JVM with fakes for its seven contracts and
 * a TestDispatcher standing in for the Main-thread viewModelScope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val store = FakeStore()
    private val resolver = FakeResolver()
    private val enrichment = FakeEnrichment()
    private val history = FakeHistory()
    private val favorites = FakeFavorites()
    private val usage = FakeUsage()
    private val userKeys = FakeUserKeys()
    private val journal = FakeUsageJournal()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** A view model over the fakes, whose registry offers [caps] (id → the intents it serves). */
    private fun vm(caps: Map<CapabilityId, Set<Intent>> = mapOf(CapabilityId("a") to setOf(Intent.PREPARE))) =
        FlowViewModel(store, FakeRegistry(caps), resolver, enrichment, history, favorites, usage, userKeys, journal)

    private fun bubble(id: String = "a", title: String = "Действие") =
        Bubble("x", title, CapabilityId(id), ObjectState(ObjectKind.TEXT))

    // --- Ingest ---

    @Test fun `onShared ingests, pushes a frame and records history`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png")
        advanceUntilIdle()

        val s = vm.ui.value
        assertEquals(ObjectKind.IMAGE, s.frame?.obj?.state?.kind)
        assertNull(s.busy)
        assertEquals(1, history.recorded.size)
    }

    @Test fun `onShared surfaces an ingest failure and pushes no frame`() = runTest(dispatcher) {
        store.failIngest = true
        val vm = vm()
        vm.onShared("uri", "image/png")
        advanceUntilIdle()

        val s = vm.ui.value
        assertNull(s.frame)
        assertNull(s.busy)
        assertTrue(s.message?.contains("Не удалось открыть") == true)
    }

    // --- Action selection (the four ActionResult channels) ---

    @Test fun `a Success step pushes the produced object and records usage`() = runTest(dispatcher) {
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals(ObjectKind.TEXT, vm.ui.value.frame?.obj?.state?.kind)
        assertTrue(usage.recorded.contains(CapabilityId("a")))
    }

    @Test fun `a Done step shows its message and keeps the frame`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("Открываю…")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals("Открываю…", vm.ui.value.message)
        assertNull(vm.ui.value.busy)
        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind) // no new frame
    }

    @Test fun `a Failure step shows its reason, not a dead-end`() = runTest(dispatcher) {
        resolver.result = ActionResult.Failure("Не удалось распознать", recoverable = true)
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals("Не удалось распознать", vm.ui.value.message)
        assertNull(vm.ui.value.busy)
    }

    @Test fun `NeedsInput asks for input, then submit runs with that text`() = runTest(dispatcher) {
        resolver.result = ActionResult.NeedsInput("Введите язык")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()
        assertEquals("Введите язык", vm.ui.value.inputPrompt)

        resolver.result = ActionResult.Done("готово")
        vm.submitAmendment("русский"); advanceUntilIdle()

        assertEquals("русский", resolver.lastAmendment)
        assertNull(vm.ui.value.inputPrompt)
        assertEquals("готово", vm.ui.value.message)
    }

    @Test fun `the busy label is the action title while it runs`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(title = "Распознать текст")) // set synchronously, before the coroutine runs
        assertEquals("Распознать текст", vm.ui.value.busy)

        advanceUntilIdle()
        assertNull(vm.ui.value.busy)
    }

    // --- Intent-first ---

    @Test fun `an intent with one serving capability runs it immediately`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("сделано")
        val vm = vm(mapOf(CapabilityId("a") to setOf(Intent.PREPARE)))
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onIntent(Intent.PREPARE); advanceUntilIdle()

        assertEquals("сделано", vm.ui.value.message)
    }

    @Test fun `an intent with several capabilities reveals them for a choice`() = runTest(dispatcher) {
        val vm = vm(mapOf(CapabilityId("a") to setOf(Intent.PREPARE), CapabilityId("b") to setOf(Intent.PREPARE)))
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onIntent(Intent.PREPARE); advanceUntilIdle()

        assertEquals(Intent.PREPARE, vm.ui.value.selectedIntent)
        assertEquals(2, vm.ui.value.intentBubbles.size)
    }

    // --- Back ---

    @Test fun `back pops to the previous object`() = runTest(dispatcher) {
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()
        assertEquals(ObjectKind.TEXT, vm.ui.value.frame?.obj?.state?.kind)

        val handled = vm.onBack()

        assertTrue(handled)
        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind)
    }

    @Test fun `back steps out of a revealed intent before popping`() = runTest(dispatcher) {
        val vm = vm(mapOf(CapabilityId("a") to setOf(Intent.PREPARE), CapabilityId("b") to setOf(Intent.PREPARE)))
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onIntent(Intent.PREPARE); advanceUntilIdle()
        assertEquals(Intent.PREPARE, vm.ui.value.selectedIntent)

        val handled = vm.onBack()

        assertTrue(handled)
        assertNull(vm.ui.value.selectedIntent)
    }

    @Test fun `back cancels a pending input`() = runTest(dispatcher) {
        resolver.result = ActionResult.NeedsInput("Введите язык")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()
        assertEquals("Введите язык", vm.ui.value.inputPrompt)

        val handled = vm.onBack()

        assertTrue(handled)
        assertNull(vm.ui.value.inputPrompt)
    }

    @Test fun `back at the root object is not handled`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        assertEquals(false, vm.onBack()) // nothing to pop → the Activity handles system back
    }

    // --- Chain replay ---

    @Test fun `applyFavorite replays each step onto a new frame`() = runTest(dispatcher) {
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.applyFavorite(FavoriteChain("c", "Цепочка", listOf(CapabilityId("a"), CapabilityId("a"))))
        advanceUntilIdle()

        assertEquals(ObjectKind.TEXT, vm.ui.value.frame?.obj?.state?.kind)
        assertNull(vm.ui.value.busy)
    }

    @Test fun `applyFavorite stops and reports when a step fails`() = runTest(dispatcher) {
        resolver.result = ActionResult.Failure("шаг упал", recoverable = true)
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.applyFavorite(FavoriteChain("c", "Цепочка", listOf(CapabilityId("a"))))
        advanceUntilIdle()

        assertTrue(vm.ui.value.message?.contains("Цепочка прервана") == true)
        assertNull(vm.ui.value.busy)
    }

    // --- Hints (async enrichment) & collection drill-down ---

    @Test fun `background enrichment augments the state with the discovered hint`() = runTest(dispatcher) {
        enrichment.features = setOf(Feature.HAS_URL) // async peek finds a link
        val vm = vm()
        vm.onShared("uri", "text/plain"); advanceUntilIdle()

        // The hint is appended only after the async enrichment completes.
        assertTrue(vm.ui.value.frame?.obj?.state?.has(Feature.HAS_URL) == true)
    }

    @Test fun `onItem drills into a collection item as a new frame`() = runTest(dispatcher) {
        val vm = vm()
        vm.onSharedMultiple(listOf("a", "b")); advanceUntilIdle()
        assertEquals(ObjectKind.COLLECTION, vm.ui.value.frame?.obj?.state?.kind)

        vm.onItem(PointObject("item", "image/png", ScratchRef("/i"), ObjectState(ObjectKind.IMAGE)))
        advanceUntilIdle()

        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind)
    }

    // --- Bring-your-own AI key (#19) ---

    @Test fun `openKeySettings shows the key screen prefilled`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        assertEquals(UserAiConfig.DEFAULT, vm.ui.value.keyScreen)
    }

    @Test fun `an AI no-key failure opens the key screen on demand`() = runTest(dispatcher) {
        resolver.result = ActionResult.Failure("AI недоступен — задайте свой ключ", recoverable = true)
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()

        assertTrue(vm.ui.value.keyScreen != null) // summoned on demand, not just an error
    }

    @Test fun `saveAiConfig stores the key and closes the screen`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        val config = UserAiConfig("sk-1", "https://h/v1", "m")

        vm.saveAiConfig(config); advanceUntilIdle()

        assertEquals(config, userKeys.saved)
        assertNull(vm.ui.value.keyScreen)
    }

    @Test fun `records usage events for the North Star (shared, action, completed)`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово") // a terminal → COMPLETED
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()

        val types = journal.events.map { it.type }
        assertTrue(types.contains(UsageEventType.SHARED))
        assertTrue(types.contains(UsageEventType.ACTION))
        assertTrue(types.contains(UsageEventType.COMPLETED))
    }
}

// --- Fakes ---

private class FakeStore : ObjectStore {
    var failIngest = false
    override suspend fun ingest(sourceUri: String, mime: String): PointObject =
        if (failIngest) error("boom") else PointObject("in", mime, ScratchRef("/in"), ObjectState(ObjectKind.IMAGE))
    override suspend fun ingestMultiple(sources: List<String>): PointObject =
        PointObject("coll", "inode/directory", ScratchRef("/coll"), ObjectState(ObjectKind.COLLECTION))
    override suspend fun put(result: ResultObject): PointObject =
        PointObject("out", result.mime, result.uri, ObjectState(result.type), result.metadata)
    override suspend fun children(collection: PointObject): List<PointObject> = emptyList()
    override suspend fun readText(obj: PointObject, limit: Int): String = ""
    override suspend fun newScratchFile(extension: String): ScratchRef = ScratchRef("/scratch.$extension")
    override suspend fun clear() = Unit
}

private class FakeResolver : Resolver {
    var result: ActionResult = ActionResult.Done("done")
    var lastAmendment: String? = "__unset__"
    override fun realizerFor(capabilityId: CapabilityId): Realizer = object : Realizer {
        override val capabilityId = capabilityId
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            lastAmendment = amendment
            return result
        }
    }
}

private class FakeCapability(override val id: CapabilityId, private val served: Set<Intent>) : Capability {
    override val icon = "x"
    override fun label(state: ObjectState) = "Action ${id.value}"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    override fun intents(state: ObjectState) = served
}

private class FakeRegistry(private val caps: Map<CapabilityId, Set<Intent>>) : CapabilityRegistry {
    override fun bubblesFor(state: ObjectState): List<Bubble> =
        caps.keys.map { Bubble("x", "Action ${it.value}", it, ObjectState(ObjectKind.TEXT)) }
    override fun intentsFor(state: ObjectState): List<Intent> =
        Intent.entries.filter { intent -> caps.values.any { intent in it } }
    override fun byId(id: CapabilityId): Capability = FakeCapability(id, caps[id] ?: emptySet())
}

private class FakeEnrichment(var features: Set<Feature> = emptySet()) : Enrichment {
    override suspend fun enrich(obj: PointObject): Set<Feature> = features
}

private class FakeHistory : HistoryStore {
    val recorded = mutableListOf<PointObject>()
    override suspend fun record(obj: PointObject) { recorded += obj }
    override suspend fun recent(limit: Int): List<HistoryEntry> = emptyList()
    override suspend fun open(entryId: String): PointObject? = null
    override suspend fun clearAll() = Unit
}

private class FakeFavorites(var chains: List<FavoriteChain> = emptyList()) : FavoritesStore {
    override suspend fun save(name: String, steps: List<CapabilityId>) = FavoriteChain("id", name, steps)
    override suspend fun all(): List<FavoriteChain> = chains
    override suspend fun delete(id: String) = Unit
}

private class FakeUsage : CapabilityUsage {
    val recorded = mutableListOf<CapabilityId>()
    override fun counts(): Map<CapabilityId, Int> = emptyMap()
    override suspend fun record(id: CapabilityId) { recorded += id }
}

private class FakeUserKeys(var config: UserAiConfig? = null) : UserKeyStore {
    var saved: UserAiConfig? = null
    override fun read() = config
    override suspend fun save(config: UserAiConfig) { saved = config; this.config = config }
    override suspend fun clear() { config = null }
}

private class FakeUsageJournal(private var enabled: Boolean = true) : UsageJournal {
    val events = mutableListOf<UsageEvent>()
    override suspend fun isEnabled() = enabled
    override suspend fun setEnabled(enabled: Boolean) { this.enabled = enabled }
    override suspend fun record(event: UsageEvent) { if (enabled) events += event }
    override suspend fun summary() = UsageSummary(
        events.count { it.type == UsageEventType.SHARED },
        events.count { it.type == UsageEventType.ACTION },
        events.count { it.type == UsageEventType.COMPLETED },
    )
    override suspend fun clear() { events.clear() }
}
