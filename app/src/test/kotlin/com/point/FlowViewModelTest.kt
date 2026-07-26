package com.point

import com.point.core.flow.AppLauncher
import com.point.core.flow.AppTarget
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Latency
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.Enrichment
import com.point.core.flow.EnrichmentUpdate
import com.point.core.flow.FavoritesStore
import com.point.core.flow.HistoryStore
import com.point.core.flow.ObjectStore
import com.point.core.flow.PrivacyConsent
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
import com.point.core.model.Preview
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import com.point.executors.OpenInCapability
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
    private val consent = FakePrivacyConsent()
    private val appLauncher = FakeAppLauncher()
    private val sensory = FakeSensoryFeedback()
    private val sensorySettings = FakeSensorySettings()
    private val snapshot = FakeFlowSnapshotStore()
    private val crashLog = FakeCrashLog()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** A view model over the fakes, whose registry offers [caps] (id → the intents it serves)
     *  and treats the ids in [cloud] as network capabilities (gated by consent). */
    private fun vm(
        caps: Map<CapabilityId, Set<Intent>> = mapOf(CapabilityId("a") to setOf(Intent.PREPARE)),
        cloud: Set<CapabilityId> = emptySet(),
    ) = FlowViewModel(store, FakeRegistry(caps, cloud), resolver, enrichment, history, favorites, usage, userKeys, journal, consent, appLauncher, FakePdfRasterizer(), sensory, sensorySettings, snapshot, crashLog, dispatcher)

    private fun bubble(id: String = "a", title: String = "Действие") =
        Bubble("x", title, CapabilityId(id), ObjectState(ObjectKind.TEXT))

    // --- Crash visibility (#11): the last crash is offered once, shared only explicitly ---

    @Test fun `a previous crash surfaces once and is forgotten on dismiss`() = runTest(dispatcher) {
        crashLog.report = "Point 0.2.0 crashed"
        val vm = vm(); advanceUntilIdle()

        assertEquals("Point 0.2.0 crashed", vm.crashReport.value)

        vm.dismissCrashReport(); advanceUntilIdle()
        assertNull(vm.crashReport.value)
        assertEquals(1, crashLog.clearedTimes)
    }

    // --- Crash-proof flow (#7): the journey survives process death ---

    private fun tempFile(content: String): String =
        java.io.File.createTempFile("snap", ".bin").apply { writeText(content); deleteOnExit() }.absolutePath

    @Test fun `restores the journey after process death`() = runTest(dispatcher) {
        snapshot.frames = listOf(
            com.point.core.model.FlowSnapshotFrame(
                "root", ObjectKind.IMAGE, "image/png", tempFile("img"),
                metadata = mapOf("entity.phone" to "+380671234567"),
            ),
            com.point.core.model.FlowSnapshotFrame(
                "step", ObjectKind.TEXT, "text/plain", tempFile("txt"),
                viaCapabilityId = "ocr", viaTitle = "Распознать текст",
            ),
        )
        val vm = vm(); advanceUntilIdle()

        assertEquals(ObjectKind.TEXT, vm.ui.value.frame?.obj?.state?.kind) // back on the same step
        assertEquals(2, vm.ui.value.path.size)                             // the whole journey
        assertEquals("Распознать текст", vm.ui.value.path.last().via)
        assertEquals("+380671234567", vm.ui.value.path.let { snapshot.frames.first().metadata["entity.phone"] })
    }

    @Test fun `a frame whose file died is skipped on restore`() = runTest(dispatcher) {
        snapshot.frames = listOf(
            com.point.core.model.FlowSnapshotFrame("root", ObjectKind.IMAGE, "image/png", tempFile("img")),
            com.point.core.model.FlowSnapshotFrame("gone", ObjectKind.TEXT, "text/plain", "/nowhere/gone.txt"),
        )
        val vm = vm(); advanceUntilIdle()

        assertEquals(1, vm.ui.value.path.size)
        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind)
    }

    @Test fun `a fresh share wins over a stale snapshot`() = runTest(dispatcher) {
        snapshot.frames = listOf(
            com.point.core.model.FlowSnapshotFrame("old", ObjectKind.TEXT, "text/plain", tempFile("old")),
        )
        val vm = vm()
        vm.onShared("uri", "image/png") // arrives before the async restore lands
        advanceUntilIdle()

        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind)
        assertEquals(1, vm.ui.value.path.size)
    }

    @Test fun `every step persists the journey, ending the flow clears it`() = runTest(dispatcher) {
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(title = "Шаг")); advanceUntilIdle()

        assertEquals(2, snapshot.saved.last().size)
        assertEquals("Шаг", snapshot.saved.last().last().viaTitle)

        vm.endFlow(); advanceUntilIdle()
        assertTrue(snapshot.cleared)
    }

    @Test fun `enrichment findings are persisted into the journey too`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(setOf(Feature.HAS_PHONE), mapOf("entity.phone" to "+380671234567"), emptyList()),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        assertEquals("+380671234567", snapshot.saved.last().last().metadata["entity.phone"])
    }

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

    @Test fun `a previewed action shows the preview first, then confirm runs it`() = runTest(dispatcher) {
        resolver.previews = mapOf(CapabilityId("a") to Preview("Добавить в контакты", listOf("Иван")))
        resolver.result = ActionResult.Done("Открываю контакт…")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()
        assertEquals("Добавить в контакты", vm.ui.value.preview?.title)
        assertNull(vm.ui.value.message) // not run yet

        vm.confirmPreview(); advanceUntilIdle()
        assertNull(vm.ui.value.preview)
        assertEquals("Открываю контакт…", vm.ui.value.message) // ran only on confirm
    }

    @Test fun `cancelling a preview runs nothing`() = runTest(dispatcher) {
        resolver.previews = mapOf(CapabilityId("a") to Preview("X"))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()
        vm.cancelPreview(); advanceUntilIdle()
        assertNull(vm.ui.value.preview)
        assertNull(vm.ui.value.message)
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

    @Test fun `NeedsInput surfaces its prompt suggestions, cleared on submit`() = runTest(dispatcher) {
        resolver.result = ActionResult.NeedsInput("Что сделать?", listOf("Опиши", "Переведи"))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()
        assertEquals(listOf("Опиши", "Переведи"), vm.ui.value.inputSuggestions)

        resolver.result = ActionResult.Done("готово")
        vm.submitAmendment("Опиши"); advanceUntilIdle()
        assertTrue(vm.ui.value.inputSuggestions.isEmpty())
    }

    @Test fun `NeedsImage raises the picker flag, cleared when the picked image is submitted`() = runTest(dispatcher) {
        resolver.result = ActionResult.NeedsImage("Выберите фон")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()
        assertEquals("Выберите фон", vm.ui.value.needsImage)

        resolver.result = ActionResult.Done("готово")
        vm.submitAmendment("content://bg/1"); advanceUntilIdle()
        assertNull(vm.ui.value.needsImage)
        assertEquals("content://bg/1", resolver.lastAmendment) // picked URI fed back as the amendment
    }

    @Test fun `the busy label is the action title while it runs`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(title = "Распознать текст")) // set synchronously, before the coroutine runs
        assertEquals("Распознать текст", vm.ui.value.busy)

        advanceUntilIdle()
        assertNull(vm.ui.value.busy)
    }

    @Test fun `sound toggle persists and reflects in ui`() = runTest(dispatcher) {
        val vm = vm()
        vm.setSoundEnabled(false); advanceUntilIdle()
        assertEquals(false, vm.ui.value.soundEnabled)
        assertEquals(false, sensorySettings.enabled)
    }

    // --- M4 (MOTION.md №7): every action answers in the hand — tap / success / failure ---

    @Test fun `a bubble tap clicks, a Done answers with success in the hand`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("Скопировано")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals(listOf("tap", "success"), sensory.events)
    }

    @Test fun `a Failure answers with the failure buzz`() = runTest(dispatcher) {
        resolver.result = ActionResult.Failure("не вышло", recoverable = true)
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals(listOf("tap", "failure"), sensory.events)
    }

    @Test fun `a Success transformation answers with success too`() = runTest(dispatcher) {
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals(listOf("tap", "success"), sensory.events)
    }

    // --- M3 (MOTION.md №8/9): a fast local action works quietly — the object stays put ---

    @Test fun `a fast local action is quiet busy — the screen must not switch away`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()) // default fake capability: local, instant latency
        assertTrue(vm.ui.value.busyQuiet)

        advanceUntilIdle()
        assertNull(vm.ui.value.busy)
    }

    @Test fun `only local non-slow work is quiet — cloud and slow keep the full busy screen`() {
        assertTrue(quietWork(CapabilityMeta()))
        assertTrue(quietWork(CapabilityMeta(latency = Latency.FAST)))
        assertEquals(false, quietWork(CapabilityMeta(network = true)))
        assertEquals(false, quietWork(CapabilityMeta(latency = Latency.SLOW)))
    }

    // --- Discover (#114): one never-tried possibility is surfaced as a hint ---

    @Test fun `discover offers the first hidden action the user never tried`() = runTest(dispatcher) {
        // Six caps → top-3 shown big, three folded; "d" is the first hidden untried one.
        val vm = vm(
            caps = listOf("a", "b", "c", "d", "e", "f").associate { CapabilityId(it) to setOf(Intent.PREPARE) },
        )
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        assertEquals(CapabilityId("d"), vm.ui.value.frame?.discover?.capabilityId)
    }

    @Test fun `discover skips actions the user already tried`() = runTest(dispatcher) {
        usage.counts = mapOf(CapabilityId("d") to 2)
        val vm = vm(
            caps = listOf("a", "b", "c", "d", "e", "f").associate { CapabilityId(it) to setOf(Intent.PREPARE) },
        )
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        assertEquals(CapabilityId("e"), vm.ui.value.frame?.discover?.capabilityId)
    }

    @Test fun `no discover when every action is visible anyway`() = runTest(dispatcher) {
        val vm = vm() // single capability — nothing is folded
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        assertNull(vm.ui.value.frame?.discover)
    }

    // --- Object Timeline (#114): the journey is visible and tappable ---

    @Test fun `the path mirrors the stack — kinds with the actions between them`() = runTest(dispatcher) {
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        assertEquals(listOf(ObjectKind.IMAGE), vm.ui.value.path.map { it.kind })

        vm.onBubble(bubble(title = "Распознать текст")); advanceUntilIdle()
        assertEquals(listOf(ObjectKind.IMAGE, ObjectKind.TEXT), vm.ui.value.path.map { it.kind })
        assertEquals("Распознать текст", vm.ui.value.path.last().via)

        vm.onBack()
        assertEquals(listOf(ObjectKind.IMAGE), vm.ui.value.path.map { it.kind })
    }

    @Test fun `tapping a timeline node jumps back to that object`() = runTest(dispatcher) {
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle() // three frames deep
        assertEquals(3, vm.ui.value.path.size)

        vm.jumpTo(0)

        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind)
        assertEquals(1, vm.ui.value.path.size)
    }

    @Test fun `jumping to the current node changes nothing`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.jumpTo(0)

        assertEquals(1, vm.ui.value.path.size)
        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind)
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

    @Test fun `enrichment updates land progressively — each finding refreshes the frame`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(setOf(Feature.HAS_URL), emptyMap(), listOf("Распознаю текст…")),
            EnrichmentUpdate(setOf(Feature.HAS_URL, Feature.HAS_PHONE), emptyMap(), emptyList()),
        )
        enrichment.stepDelayMs = 100
        val vm = vm()
        vm.onShared("uri", "image/png")
        dispatcher.scheduler.advanceTimeBy(150) // after the 1st update, before the 2nd

        val mid = vm.ui.value.frame
        assertTrue(mid?.obj?.state?.has(Feature.HAS_URL) == true)   // first finding already visible
        assertEquals(false, mid?.obj?.state?.has(Feature.HAS_PHONE))
        assertEquals(listOf("Распознаю текст…"), mid?.enriching)    // background work is announced

        advanceUntilIdle()
        val end = vm.ui.value.frame
        assertTrue(end?.obj?.state?.has(Feature.HAS_PHONE) == true) // second finding arrived
        assertTrue(end?.enriching?.isEmpty() == true)               // and the announcement cleared
    }

    @Test fun `enrichment merges discovered metadata into the frame object`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(setOf(Feature.HAS_PHONE), mapOf("ocr.text.ref" to "/scratch/ocr.txt"), emptyList()),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        assertEquals("/scratch/ocr.txt", vm.ui.value.frame?.obj?.metadata?.get("ocr.text.ref"))
    }

    @Test fun `late enrichment still lands on its object below the top of the stack`() = runTest(dispatcher) {
        enrichment.updates = listOf(EnrichmentUpdate(setOf(Feature.HAS_PHONE), emptyMap(), emptyList()))
        enrichment.stepDelayMs = 500 // the root's OCR is still running when the user acts
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))
        val vm = vm()
        vm.onShared("uri", "image/png")
        dispatcher.scheduler.advanceTimeBy(50)   // root frame pushed, its enrichment pending
        vm.onBubble(bubble())
        dispatcher.scheduler.advanceTimeBy(100)  // a TEXT frame is now on top
        assertEquals(ObjectKind.TEXT, vm.ui.value.frame?.obj?.state?.kind)

        advanceUntilIdle()                        // root enrichment finally lands

        vm.onBack()
        assertTrue(vm.ui.value.frame?.obj?.state?.has(Feature.HAS_PHONE) == true) // not lost
    }

    @Test fun `finished enrichment lands its understanding in history`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(setOf(Feature.HAS_PHONE), mapOf("entity.phone" to "+380671234567"), emptyList()),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        val updated = history.updated.single()
        assertTrue(updated.state.has(Feature.HAS_PHONE))
        assertEquals("+380671234567", updated.metadata["entity.phone"])
    }

    @Test fun `ending the flow cancels running enrichment`() = runTest(dispatcher) {
        enrichment.updates = listOf(EnrichmentUpdate(setOf(Feature.HAS_PHONE), emptyMap(), emptyList()))
        enrichment.stepDelayMs = 10_000
        val vm = vm()
        vm.onShared("uri", "image/png")
        dispatcher.scheduler.advanceTimeBy(50)

        vm.endFlow(); advanceUntilIdle() // must not hang or apply to a cleared stack

        assertNull(vm.ui.value.frame)
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

    // --- Cloud privacy consent (#10) ---

    private fun cloudVm() = vm(
        caps = mapOf(CapabilityId("ai") to setOf(Intent.UNDERSTAND)),
        cloud = setOf(CapabilityId("ai")),
    )

    @Test fun `a cloud action asks for consent before anything leaves the device`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()

        assertTrue(vm.ui.value.cloudConsent)                  // asked
        assertNull(vm.ui.value.message)                       // nothing ran
        assertEquals("__unset__", resolver.lastAmendment)     // the realizer was never invoked
    }

    @Test fun `confirming consent runs the pending cloud action and persists the grant`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()

        vm.confirmCloud(); advanceUntilIdle()

        assertEquals("готово", vm.ui.value.message)           // the gated action finally ran
        assertEquals(false, vm.ui.value.cloudConsent)         // prompt dismissed
        assertTrue(consent.granted)                           // remembered for next time
    }

    @Test fun `declining consent cancels the cloud action and sends nothing`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()

        vm.declineCloud()

        assertNull(vm.ui.value.message)
        assertEquals("__unset__", resolver.lastAmendment)     // never ran
        assertEquals(false, vm.ui.value.cloudConsent)
        assertEquals(false, consent.granted)                  // not persisted — asks again next time
    }

    @Test fun `an already-granted consent lets a cloud action run without asking`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        consent.granted = true
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle() // init caches cloudAllowed = true

        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()

        assertEquals("готово", vm.ui.value.message)
        assertEquals(false, vm.ui.value.cloudConsent)         // no prompt
    }

    @Test fun `a favorite chain hiding a cloud step is gated too — not a back door`() = runTest(dispatcher) {
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.applyFavorite(FavoriteChain("c", "Цепочка", listOf(CapabilityId("ai"))))
        advanceUntilIdle()

        assertTrue(vm.ui.value.cloudConsent)                  // asked before replaying
        assertEquals("__unset__", resolver.lastAmendment)     // no step reached the cloud
    }

    // --- Device actions: inline app picker (#66) ---

    @Test fun `open-in shows the device's installed apps`() = runTest(dispatcher) {
        appLauncher.apps = listOf(AppTarget("Chrome", "com.chrome", "A"), AppTarget("Firefox", "org.ff", "B"))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "open-in")); advanceUntilIdle()

        assertEquals(2, vm.ui.value.appPicker?.size)
    }

    @Test fun `picking an app launches it and closes the picker`() = runTest(dispatcher) {
        val chrome = AppTarget("Chrome", "com.chrome", "A")
        appLauncher.apps = listOf(chrome)
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "open-in")); advanceUntilIdle()

        vm.onPickApp(chrome); advanceUntilIdle()

        assertEquals(chrome, appLauncher.launched)
        assertNull(vm.ui.value.appPicker)
    }

    @Test fun `open-in with no handler shows a plain message, not an empty picker`() = runTest(dispatcher) {
        appLauncher.apps = emptyList()
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "open-in")); advanceUntilIdle()

        assertNull(vm.ui.value.appPicker)
        assertTrue(vm.ui.value.message?.contains("Нет приложения") == true)
    }

    // --- Synthesized compatibility: bridged app targets via one transform (#79.1) ---

    @Test fun `open-in also offers apps reachable via one transform`() = runTest(dispatcher) {
        // No direct handler; the default capability "a" produces TEXT → text apps become reachable.
        appLauncher.apps = emptyList()
        appLauncher.mimeApps = mapOf("text/plain" to listOf(AppTarget("Notepad", "com.np", "A")))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle() // an IMAGE object

        vm.onBubble(bubble(id = "open-in")); advanceUntilIdle()

        val picker = vm.ui.value.appPicker
        assertEquals(1, picker?.size)
        assertEquals("a", picker?.first()?.via)                       // bridged via transform "a"
        assertTrue(picker?.first()?.label?.contains("текст") == true) // labelled with the produced kind
    }

    @Test fun `picking a bridged app converts first, then launches the produced object`() = runTest(dispatcher) {
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/converted")))
        appLauncher.mimeApps = mapOf("text/plain" to listOf(AppTarget("Notepad", "com.np", "A")))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "open-in")); advanceUntilIdle()
        val bridged = vm.ui.value.appPicker!!.first()

        vm.onPickApp(bridged); advanceUntilIdle()

        assertEquals("com.np", appLauncher.launched?.packageName)
        assertEquals(ObjectKind.TEXT, appLauncher.launchedObj?.state?.kind) // the converted object, not the image
    }

    // --- Clipboard-on-open (#72): only actionable, new text is offered ---

    @Test fun `offers any new clipboard text, ignores blank and re-dismissed text`() {
        val vm = vm()

        vm.offerClipboard("любой скопированный текст")
        assertEquals("любой скопированный текст", vm.clipboard.value)

        vm.offerClipboard("   ") // blank → not offered
        assertNull(vm.clipboard.value)

        vm.offerClipboard("ещё текст")
        assertEquals("ещё текст", vm.clipboard.value)
        vm.dismissClipboard()
        assertNull(vm.clipboard.value)
        vm.offerClipboard("ещё текст") // same as dismissed → not re-offered
        assertNull(vm.clipboard.value)
    }

    @Test fun `the app picker never lists an app twice — direct and bridged dedup`() = runTest(dispatcher) {
        val files = AppTarget("Files", "com.files", "A")
        appLauncher.apps = listOf(files)                            // Files handles it directly
        appLauncher.mimeApps = mapOf("text/plain" to listOf(files)) // AND via the "a" transform
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "open-in")); advanceUntilIdle()

        assertEquals(1, vm.ui.value.appPicker?.size) // deduped by package (a dup key crashed the list)
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
    var previews: Map<CapabilityId, Preview> = emptyMap()
    override fun realizerFor(capabilityId: CapabilityId): Realizer = object : Realizer {
        override val capabilityId = capabilityId
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            lastAmendment = amendment
            return result
        }
        override suspend fun preview(input: PointObject): Preview? = previews[capabilityId]
    }
}

private class FakeCapability(
    override val id: CapabilityId,
    private val served: Set<Intent>,
    network: Boolean = false,
) : Capability {
    override val icon = "x"
    override val meta = CapabilityMeta(network = network)
    override fun label(state: ObjectState) = "Action ${id.value}"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    override fun intents(state: ObjectState) = served
}

private class FakeRegistry(
    private val caps: Map<CapabilityId, Set<Intent>>,
    private val cloud: Set<CapabilityId> = emptySet(),
) : CapabilityRegistry {
    override fun bubblesFor(state: ObjectState): List<Bubble> =
        caps.keys.map { Bubble("x", "Action ${it.value}", it, ObjectState(ObjectKind.TEXT)) }
    override fun intentsFor(state: ObjectState): List<Intent> =
        Intent.entries.filter { intent -> caps.values.any { intent in it } }
    override fun latentBubblesFor(state: ObjectState) = emptyList<com.point.core.model.LatentBubble>()
    override fun byId(id: CapabilityId): Capability = FakeCapability(id, caps[id] ?: emptySet(), id in cloud)
}

private class FakeEnrichment(var features: Set<Feature> = emptySet()) : Enrichment {
    /** Progressive script: each update is emitted after [stepDelayMs] of virtual time. */
    var updates: List<EnrichmentUpdate>? = null
    var stepDelayMs: Long = 0
    override fun enrich(obj: PointObject): kotlinx.coroutines.flow.Flow<EnrichmentUpdate> =
        kotlinx.coroutines.flow.flow {
            val script = updates ?: listOf(EnrichmentUpdate(features, emptyMap(), emptyList()))
            for (u in script) {
                if (stepDelayMs > 0) kotlinx.coroutines.delay(stepDelayMs)
                emit(u)
            }
        }
}

private class FakeHistory : HistoryStore {
    val recorded = mutableListOf<PointObject>()
    val updated = mutableListOf<PointObject>()
    override suspend fun record(obj: PointObject) { recorded += obj }
    override suspend fun update(obj: PointObject) { updated += obj }
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
    var counts: Map<CapabilityId, Int> = emptyMap()
    override fun counts(): Map<CapabilityId, Int> = counts
    override suspend fun record(id: CapabilityId) { recorded += id }
}

private class FakeUserKeys(var config: UserAiConfig? = null) : UserKeyStore {
    var saved: UserAiConfig? = null
    override fun read() = config
    override suspend fun save(config: UserAiConfig) { saved = config; this.config = config }
    override suspend fun clear() { config = null }
}

private class FakePrivacyConsent(var granted: Boolean = false) : PrivacyConsent {
    override suspend fun cloudAllowed() = granted
    override suspend fun allowCloud() { granted = true }
}

private class FakeSensoryFeedback : com.point.core.flow.SensoryFeedback {
    val events = mutableListOf<String>()
    override fun tap() { events += "tap" }
    override fun success() { events += "success" }
    override fun failure() { events += "failure" }
}

private class FakeCrashLog : com.point.core.flow.CrashLog {
    var report: String? = null
    var clearedTimes = 0
    override fun record(report: String) { this.report = report }
    override suspend fun pending() = report
    override suspend fun clear() { clearedTimes++; report = null }
}

private class FakeFlowSnapshotStore : com.point.core.flow.FlowSnapshotStore {
    var frames: List<com.point.core.model.FlowSnapshotFrame> = emptyList()
    val saved = mutableListOf<List<com.point.core.model.FlowSnapshotFrame>>()
    var cleared = false
    override suspend fun save(frames: List<com.point.core.model.FlowSnapshotFrame>) { saved += frames }
    override suspend fun load() = frames
    override suspend fun clear() { cleared = true; frames = emptyList() }
}

private class FakeSensorySettings : com.point.core.flow.SensorySettings {
    var enabled = true
    override fun isSoundEnabled() = enabled
    override suspend fun setSoundEnabled(enabled: Boolean) { this.enabled = enabled }
}

private class FakePdfRasterizer : com.point.core.flow.PdfRasterizer {
    override suspend fun rasterize(obj: PointObject) = ScratchRef("/pages")
    override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? = null
}

private class FakeAppLauncher(
    var apps: List<AppTarget> = emptyList(),
    var mimeApps: Map<String, List<AppTarget>> = emptyMap(),
) : AppLauncher {
    var launched: AppTarget? = null
    var launchedObj: PointObject? = null
    override suspend fun handlers(obj: PointObject) = apps
    override suspend fun handlersForMime(mime: String) = mimeApps[mime].orEmpty()
    override suspend fun launch(target: AppTarget, obj: PointObject) { launched = target; launchedObj = obj }
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
