package com.point

import com.point.core.flow.AiChatResponder
import com.point.core.flow.AppLauncher
import com.point.core.flow.AppTarget
import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.ChosenApp
import com.point.core.flow.ChosenApps
import com.point.core.flow.CloudScope
import com.point.core.flow.CollectionContent
import com.point.core.flow.CrashLog
import com.point.core.flow.Entitlements
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.Focus
import com.point.core.flow.HistoryStore
import com.point.core.flow.InvestigationState
import com.point.core.flow.KeyProbe
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.ObjectStore
import com.point.core.flow.PcCapsStore
import com.point.core.flow.PcLinks
import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.PinnedActions
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.QrReader
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.flow.SensoryFeedback
import com.point.core.flow.SensorySettings
import com.point.core.flow.SharedSecrets
import com.point.core.flow.SharedTexts
import com.point.core.flow.UsageEvent
import com.point.core.flow.UsageJournal
import com.point.core.flow.UsageSummary
import com.point.core.flow.UserAiConfig
import com.point.core.flow.UserKeyStore
import com.point.core.flow.alternativesOf
import com.point.core.flow.investigationStateOf
import com.point.core.flow.provenanceOf
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.HistoryEntry
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import com.point.data.DefaultEnrichment
import com.point.data.EntityInvestigation
import com.point.data.EntityInvestigationRealizer
import com.point.data.MetadataEntityInvestigation
import com.point.data.MetadataEntityInvestigationRealizer
import com.point.data.QrInvestigation
import com.point.data.QrInvestigationRealizer
import com.point.executors.CallCapability
import com.point.executors.CorrectValueCapability
import com.point.executors.CorrectValueRealizer
import com.point.executors.DefaultCapabilityRegistry
import com.point.executors.DefaultResolver
import com.point.executors.LearningBubblePolicy
import com.point.executors.ReadQrCapability
import com.point.executors.ReadQrRealizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Интеграционный контур Этапа 7: настоящая граница
 *
 *   FlowViewModel → DefaultEnrichment → DefaultCapabilityRegistry → DefaultResolver → Realizer → Graph.
 *
 * Фейки — только у внешних движков и IO (QR-сканер, извлекатель сущностей, хранилища
 * настроек). Ни цикл, ни реестр, ни Resolver, ни реализаторы не подменяются: тесты
 * ломаются при вырезании любого звена production-композиции.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphPipelineIntegrationTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    // ---------- внешние движки (единственные фейки исполнения) ----------

    private class ScriptedQr : QrReader {
        var value: String? = null
        var broken = false
        override suspend fun decode(imagePath: String): String? {
            if (broken) error("сканер сломан")
            return value
        }
    }

    private val qrEngine = ScriptedQr()

    private val phoneEngine = object : EntityExtractor {
        override suspend fun extract(text: String): List<Entity> =
            Regex("""\+380\d{9}""").findAll(text).map { Entity(EntityType.PHONE, it.value) }.toList()
    }

    // ---------- зонды планировщика: смысл виден по порядку ----------

    private class Probe(id: String, priority: Int, private val serves: Set<Intent>) : Capability {
        override val id = CapabilityId(id)
        override val icon = ""
        override val meta = CapabilityMeta(priority = priority)
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
        override fun produces(state: ObjectState) = state
        override fun intents(state: ObjectState) = serves
    }

    // ---------- production-стек ----------

    private val store = IngestStore()

    private val registry = DefaultCapabilityRegistry(
        capabilities = setOf(
            QrInvestigation(), EntityInvestigation(), MetadataEntityInvestigation(),
            ReadQrCapability(), CallCapability(), CorrectValueCapability(),
            Probe("probe-understand", priority = 90, serves = setOf(Intent.UNDERSTAND)),
            Probe("probe-send", priority = 10, serves = setOf(Intent.SEND)),
        ),
        policy = LearningBubblePolicy(
            pins = object : PinnedActions {
                override fun pinnedFor(kind: ObjectKind): CapabilityId? = null
                override suspend fun pin(kind: ObjectKind, id: CapabilityId) = Unit
                override suspend fun unpin(kind: ObjectKind) = Unit
            },
            usage = object : CapabilityUsage {
                override fun counts(): Map<CapabilityId, Int> = emptyMap()
                override suspend fun record(id: CapabilityId) = Unit
            },
            llm = object : LlmClient {
                override val configured = true
                override suspend fun run(obj: PointObject, prompt: String) =
                    ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/none"))
            },
        ),
    )

    /** Шпион вокруг настоящего Resolver: production-выбор + захват состояния для N2. */
    private inner class SpyResolver : Resolver {
        val real = DefaultResolver(
            realizers = setOf(
                QrInvestigationRealizer(qrEngine),
                EntityInvestigationRealizer(phoneEngine),
                MetadataEntityInvestigationRealizer(),
                ReadQrRealizer(store, qrEngine),
                CorrectValueRealizer(),
            ),
            registry = registry,
            entitlements = Entitlements { true },
        )
        val seenStates = mutableMapOf<CapabilityId, ObjectState>()

        override fun realizerFor(capabilityId: CapabilityId): Realizer =
            realizerFor(capabilityId, ObjectState(ObjectKind.UNKNOWN))

        override fun realizerFor(capabilityId: CapabilityId, state: ObjectState): Realizer {
            seenStates[capabilityId] = state
            return real.realizerFor(capabilityId, state)
        }

        override fun leavesDevice(capabilityId: CapabilityId) = real.leavesDevice(capabilityId)
    }

    private val resolver = SpyResolver()

    private val consent = object : PrivacyConsent {
        override suspend fun allowed(scope: CloudScope) = true
        override suspend fun allow(scope: CloudScope) = Unit
        override suspend fun revoke(scope: CloudScope) = Unit
    }

    private val enrichment = DefaultEnrichment(registry, resolver, consent)

    private val snapshot = MemorySnapshot()

    // ---------- share-вход ----------

    private class IngestStore : ObjectStore {
        var next: PointObject? = null
        override suspend fun ingest(sourceUri: String, mime: String): PointObject = next ?: error("нет объекта")
        override suspend fun ingestMultiple(sources: List<String>): PointObject = error("не используется")
        override suspend fun put(result: ResultObject): PointObject =
            PointObject("out", result.mime, result.uri, ObjectState(result.type), result.metadata)
        override suspend fun children(collection: PointObject, limit: Int) = CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int): String = ""
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("pipeline-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private class MemorySnapshot : com.point.core.flow.FlowSnapshotStore {
        var frames: List<com.point.core.model.FlowSnapshotFrame> = emptyList()
        val saved = mutableListOf<List<com.point.core.model.FlowSnapshotFrame>>()
        override suspend fun save(frames: List<com.point.core.model.FlowSnapshotFrame>) { saved += frames }
        override suspend fun load() = frames
        override suspend fun clear() { frames = emptyList() }
    }

    private fun imageObject(metadata: Map<String, String> = emptyMap()): PointObject {
        val payload = File.createTempFile("img-", ".jpg").apply { writeText("jpg"); deleteOnExit() }
        return PointObject("img", "image/jpeg", ScratchRef(payload.absolutePath), ObjectState(ObjectKind.IMAGE), metadata)
    }

    private fun vm(): FlowViewModel = FlowViewModel(
        store, registry, resolver,
        object : AiChatResponder {
            override suspend fun reply(obj: PointObject, history: List<com.point.core.model.ChatMessage>, message: String) = "ok"
        },
        enrichment,
        object : HistoryStore {
            override suspend fun record(obj: PointObject) = Unit
            override suspend fun update(obj: PointObject) = Unit
            override suspend fun recent(limit: Int): List<HistoryEntry> = emptyList()
            override suspend fun open(entryId: String): PointObject? = null
            override suspend fun clearAll() = Unit
        },
        object : CapabilityUsage {
            override fun counts(): Map<CapabilityId, Int> = emptyMap()
            override suspend fun record(id: CapabilityId) = Unit
        },
        object : ChosenApps {
            override fun all(): List<ChosenApp> = emptyList()
            override suspend fun record(app: ChosenApp) = Unit
        },
        object : UserKeyStore {
            override fun read(): UserAiConfig? = UserAiConfig("k", "https://api.example", "m")
            override suspend fun save(config: UserAiConfig) = Unit
            override suspend fun clear() = Unit
        },
        object : UsageJournal {
            override suspend fun isEnabled() = false
            override suspend fun setEnabled(enabled: Boolean) = Unit
            override suspend fun record(event: UsageEvent) = Unit
            override suspend fun graph(): Map<String, Int> = emptyMap()
            override suspend fun summary() = UsageSummary(0, 0, 0)
            override suspend fun clear() = Unit
        },
        consent,
        object : AppLauncher {
            override suspend fun handlers(obj: PointObject): List<AppTarget> = emptyList()
            override suspend fun handlersForMime(mime: String): List<AppTarget> = emptyList()
            override suspend fun launch(target: AppTarget, obj: PointObject) = Unit
        },
        object : PdfRasterizer {
            override suspend fun rasterize(obj: PointObject) = ScratchRef("/pages")
            override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? = null
        },
        object : SensoryFeedback {
            override fun tap() = Unit
            override fun success() = Unit
            override fun failure() = Unit
        },
        object : SensorySettings {
            override fun isSoundEnabled() = false
            override suspend fun setSoundEnabled(enabled: Boolean) = Unit
        },
        object : com.point.core.flow.CloudPrivacySettings {
            override fun level() = com.point.core.flow.PrivacyLevel.DEFAULT
            override suspend fun setLevel(level: com.point.core.flow.PrivacyLevel) = Unit
        },
        snapshot,
        object : CrashLog {
            override fun record(report: String) = Unit
            override suspend fun pending(): String? = null
            override suspend fun clear() = Unit
        },
        dispatcher as CoroutineDispatcher,
        object : PinnedActions {
            override fun pinnedFor(kind: ObjectKind): CapabilityId? = null
            override suspend fun pin(kind: ObjectKind, id: CapabilityId) = Unit
            override suspend fun unpin(kind: ObjectKind) = Unit
        },
        AppIconResolver { null },
        object : PcLinks {
            override fun current(): com.point.core.flow.LinkedPc? = null
            override suspend fun save(pc: com.point.core.flow.LinkedPc) = Unit
            override suspend fun clear() = Unit
        },
        object : PcTransport {
            override suspend fun send(
                pc: com.point.core.flow.LinkedPc,
                obj: PointObject,
                fileName: String,
                meta: Map<String, String>,
                action: String?,
            ): PcSendOutcome = PcSendOutcome.Sent()
            override suspend fun fetchCaps(pc: com.point.core.flow.LinkedPc): List<PcRemoteAction>? = null
            override suspend fun fetchOutbox(pc: com.point.core.flow.LinkedPc): List<PcOutboxEntry>? = null
            override suspend fun downloadOutboxFile(pc: com.point.core.flow.LinkedPc, id: Int, targetPath: String) = false
            override suspend fun ackOutbox(pc: com.point.core.flow.LinkedPc, id: Int) = Unit
            override suspend fun pushPhoneCaps(pc: com.point.core.flow.LinkedPc, caps: List<PcRemoteAction>) = false
            override suspend fun exchangeSecrets(pc: com.point.core.flow.LinkedPc, mine: SharedSecrets): SharedSecrets? = null
        },
        object : PcCapsStore {
            override fun all(): List<PcRemoteAction> = emptyList()
            override suspend fun save(caps: List<PcRemoteAction>) = Unit
            override suspend fun clear() = Unit
        },
        com.point.core.flow.RememberingLinkMonitor(),
        PulledFileFactory { name -> File(File(System.getProperty("java.io.tmpdir")), "pull-$name").absolutePath },
        object : SelectionFrames {
            override fun frame(path: String, maxPx: Int) = null
            override fun crop(path: String, left: Int, top: Int, right: Int, bottom: Int) = null
        },
        object : com.point.core.flow.AiKeyCheck {
            override suspend fun check(config: UserAiConfig) = KeyProbe(status = 200, reply = "ok")
        },
        FakeAccountStore(TEST_ACCOUNT),
        FakeCircleClient(),
        com.point.core.flow.InMemoryPendingLogins(),
        object : com.point.core.flow.DeviceKeyStore {
            override fun keys() = TEST_KEYS
        },
        com.point.core.flow.BrowserOpener { },
        object : SharedTexts {
            override fun create(text: String): String = ""
            override fun clear() = Unit
        },
    )

    private fun frame(vm: FlowViewModel) = vm.ui.value.frame!!

    /** Гасим настоящие IO-корутины VM, чтобы они не пережили тест. */
    private fun kotlinx.coroutines.test.TestScope.shutdown(vararg vms: FlowViewModel) {
        vms.forEach { it.endFlow() }
        advanceUntilIdle()
        Thread.sleep(30)
        advanceUntilIdle()
    }

    /**
     * Реализаторы работают на настоящем Dispatchers.IO — это часть проверяемой границы.
     * Дожидаемся результата, чередуя продвижение тестового планировщика и реальное время.
     */
    private fun kotlinx.coroutines.test.TestScope.settle(until: () -> Boolean) {
        repeat(200) {
            advanceUntilIdle()
            if (until()) return
            Thread.sleep(10)
        }
        advanceUntilIdle()
    }

    // ================= A. Share → Graph → Bubbles → Action → Result → Graph =================

    @Test
    fun `A - знание от настоящего исследования открывает действие, действие даёт новый объект`() = runTest(dispatcher) {
        qrEngine.value = "https://point.app/x"
        store.next = imageObject()
        val vm = vm()

        vm.onShared("uri", "image/jpeg"); advanceUntilIdle()

        val enriched = frame(vm)
        assertTrue("настоящий цикл нашёл QR", enriched.obj.state.has(Feature.HAS_QR))
        assertEquals("https://point.app/x", enriched.obj.metadata["entity.qr"])
        assertEquals(
            "вопрос закрыт по-настоящему",
            InvestigationState.FOUND,
            investigationStateOf(enriched.obj.metadata, QrInvestigation.ID),
        )

        val readQr = enriched.bubbles.first { it.capabilityId == ReadQrCapability.ID }

        vm.onBubble(readQr)
        settle { vm.ui.value.frame?.obj?.state?.kind == ObjectKind.TEXT }

        val produced = frame(vm)
        assertEquals("действие через настоящий Resolver дало объект", ObjectKind.TEXT, produced.obj.state.kind)
        assertEquals("https://point.app/x", File(produced.obj.uri.value).readText())

        // N2: Resolver получил фактическое состояние, а не UNKNOWN
        assertEquals(ObjectKind.IMAGE, resolver.seenStates[ReadQrCapability.ID]?.kind)
        assertTrue(resolver.seenStates[ReadQrCapability.ID]?.has(Feature.HAS_QR) == true)

        shutdown(vm)
    }

    @Test
    fun `A - исследования не предлагаются человеку даже настоящим реестром`() = runTest(dispatcher) {
        qrEngine.value = null
        store.next = imageObject()
        val vm = vm()
        vm.onShared("uri", "image/jpeg"); advanceUntilIdle()

        val ids = frame(vm).bubbles.map { it.capabilityId }
        assertFalse(QrInvestigation.ID in ids)
        assertFalse(MetadataEntityInvestigation.ID in ids)

        shutdown(vm)
    }

    // ================= B. Focus → scoped discovery → merge → planner =================

    @Test
    fun `B - Focus запускает настоящий областной проход и планировщик видит результат`() = runTest(dispatcher) {
        qrEngine.value = null
        val atoms = AtomLayer(
            listOf(
                Atom("w1", "тел:", Box(10f, 20f, 60f, 40f), confidence = 0.99f),
                Atom("w2", "+380671234567", Box(70f, 20f, 260f, 40f), confidence = 0.99f),
            ),
        )
        val layerFile = File.createTempFile("atoms-", ".tsv").apply {
            writeText(AtomCodec.encode(atoms)); deleteOnExit()
        }
        store.next = imageObject(metadata = mapOf(META_OCR_ATOMS_REF to layerFile.absolutePath))
        val vm = vm()
        vm.onShared("uri", "image/jpeg"); advanceUntilIdle()

        val before = frame(vm)
        assertNull("до Focus сущностей нет", before.obj.metadata["entity.phone"])
        assertEquals(
            "порядок без Intent — по priority",
            listOf("probe-send", "probe-understand"),
            before.bubbles.filter { it.capabilityId.value.startsWith("probe-") }.map { it.capabilityId.value },
        )

        vm.focusOn(Focus("img", region = Box(0f, 0f, 300f, 60f)))
        settle { vm.ui.value.frame?.obj?.metadata?.containsKey("entity.phone") == true }

        val after = frame(vm)
        assertEquals("настоящий реализатор прочёл область", "+380671234567", after.obj.metadata["entity.phone"])
        assertEquals(
            "область отвечена",
            InvestigationState.FOUND,
            investigationStateOf(after.obj.metadata, EntityInvestigation.ID, after.focus),
        )
        assertEquals(
            "глобальный вопрос не тронут областью",
            InvestigationState.NOT_INVESTIGATED,
            investigationStateOf(after.obj.metadata, EntityInvestigation.ID),
        )
        assertTrue("узел области в графе", after.found.any { it.id == "img:phone:380671234567" })
        assertTrue(
            "знание открыло действие в настоящем реестре",
            after.bubbles.any { it.capabilityId == CallCapability.ID },
        )

        // Intent пересчитан из нового состояния: Focus → «понять» поднимается над priority
        assertEquals(
            listOf("probe-understand", "probe-send"),
            after.bubbles.filter { it.capabilityId.value.startsWith("probe-") }.map { it.capabilityId.value },
        )

        shutdown(vm)
    }

    // ================= C. Failure → failed → NOT_INVESTIGATED → объект жив =================

    @Test
    fun `C - сломанный движок остаётся неудачей операции, а не знанием об отсутствии`() = runTest(dispatcher) {
        qrEngine.broken = true
        store.next = imageObject()
        val vm = vm()

        vm.onShared("uri", "image/jpeg"); advanceUntilIdle()

        val alive = frame(vm)
        assertEquals(
            "сорвавшийся вопрос остался незаданным",
            InvestigationState.NOT_INVESTIGATED,
            investigationStateOf(alive.obj.metadata, QrInvestigation.ID),
        )
        assertNull("ложного знания нет", alive.obj.metadata["entity.qr"])
        assertFalse(alive.obj.state.has(Feature.HAS_QR))
        assertTrue("объект жив и планировщик работает", alive.bubbles.isNotEmpty())

        qrEngine.broken = false
        qrEngine.value = "https://point.app/retry"
        vm.focusOn(Focus("img", region = Box(0f, 0f, 5f, 5f)))
        settle { vm.ui.value.frame?.focus != null }
        assertTrue("после неудачи flow продолжается", frame(vm).focus != null)

        shutdown(vm)
    }

    // ================= D. HUMAN через настоящий registry + Resolver =================

    @Test
    fun `D - исправление человека проходит настоящую цепочку до родителя и журнала`() = runTest(dispatcher) {
        qrEngine.value = null
        store.next = imageObject(metadata = mapOf("entity.phone" to "+380671111111"))
        val vm = vm()
        vm.onShared("uri", "image/jpeg"); advanceUntilIdle()

        val node = frame(vm).found.first { it.metadata.containsKey("entity.phone") }
        vm.onFound(node); advanceUntilIdle()

        val correct = frame(vm).bubbles.first { it.capabilityId == CorrectValueCapability.ID }
        vm.onBubble(correct); advanceUntilIdle()

        assertEquals("настоящий реализатор спросил человека", "Как правильно?", vm.ui.value.inputPrompt)
        assertEquals(listOf("+380671111111"), vm.ui.value.inputSuggestions)

        vm.submitAmendment("+380672222222"); advanceUntilIdle()

        assertEquals("+380672222222", frame(vm).obj.metadata["entity.phone"])

        // N2: состояние узла доехало до настоящего Resolver
        assertEquals(
            com.point.core.flow.KIND_PHONE,
            resolver.seenStates[CorrectValueCapability.ID]?.kind,
        )

        vm.onBack()
        val parent = frame(vm)
        assertEquals("носитель истины — родитель", "+380672222222", parent.obj.metadata["entity.phone"])
        assertEquals(Provenance.HUMAN, provenanceOf(parent.obj.metadata, "entity.phone"))
        assertTrue(alternativesOf(parent.obj.metadata, "entity.phone").contains("+380671111111"))

        val chip = parent.found.first { it.metadata.containsKey("entity.phone") }
        assertEquals("узел не разошёлся с фактом", "+380672222222", chip.metadata["entity.phone"])

        val persisted = snapshot.saved.last().first()
        assertEquals("+380672222222", persisted.metadata["entity.phone"])
        assertEquals(Provenance.HUMAN.wire, persisted.metadata["entity.phone" + META_SOURCE_SUFFIX])

        // process death: восстановленный журнал держит слово человека
        snapshot.frames = snapshot.saved.last()
        val reborn = vm()
        reborn.restoreJourney(); advanceUntilIdle()
        val restored = reborn.ui.value.frame!!
        assertEquals("+380672222222", restored.obj.metadata["entity.phone"])
        assertEquals(Provenance.HUMAN, provenanceOf(restored.obj.metadata, "entity.phone"))

        shutdown(vm, reborn)
    }
}
