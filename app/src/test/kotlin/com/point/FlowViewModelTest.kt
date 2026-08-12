package com.point

import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AppLauncher
import com.point.core.flow.AppTarget
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.CollectionContent
import com.point.core.flow.Latency
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.Enrichment
import com.point.core.flow.EnrichmentUpdate
import com.point.core.flow.HistoryStore
import com.point.core.flow.ObjectStore
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.flow.UserAiConfig
import com.point.core.flow.UserKeyStore
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.HistoryEntry
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Preview
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import com.point.core.model.ValueRef
import com.point.core.ui.Outcome
import com.point.executors.OpenInCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val store = FakeStore()
    private val resolver = FakeResolver()
    private val enrichment = FakeEnrichment()
    private val history = FakeHistory()
    private val usage = FakeUsage()
    private val chosenApps = FakeChosenApps()
    private val userKeys = FakeUserKeys()
    private val consent = FakePrivacyConsent()
    private val appLauncher = FakeAppLauncher()
    private val sensory = FakeSensoryFeedback()
    private val sensorySettings = FakeSensorySettings()

    private val cloudPrivacy = object : com.point.core.flow.CloudPrivacySettings {
        var level = com.point.core.flow.PrivacyLevel.DEFAULT
        override fun level() = level
        override suspend fun setLevel(level: com.point.core.flow.PrivacyLevel) { this.level = level }
    }
    private val snapshot = FakeFlowSnapshotStore()
    private val crashLog = FakeCrashLog()
    private val pins = FakePinnedActions()

    private val noFrames = object : SelectionFrames {

        /** Последний путь, по которому спрашивали картинку (#812). */
        var askedPath: String? = null
        override fun frame(path: String, maxPx: Int): com.point.data.SelectionFrame? {
            askedPath = path
            return null
        }
        override fun crop(path: String, left: Int, top: Int, right: Int, bottom: Int) = null
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        caps: Map<CapabilityId, Set<Intent>> = mapOf(CapabilityId("a") to setOf(Intent.PREPARE)),
        cloud: Set<CapabilityId> = emptySet(),
        slow: Set<CapabilityId> = emptySet(),
        linkMonitor: com.point.core.flow.LinkMonitor = com.point.core.flow.RememberingLinkMonitor(),

        account: com.point.core.flow.AccountStore = FakeAccountStore(TEST_ACCOUNT),
        accountClient: com.point.core.flow.AccountClient = FakeCircleClient(),

        pendingLogins: com.point.core.flow.PendingLoginStore = com.point.core.flow.InMemoryPendingLogins(),
        browser: com.point.core.flow.BrowserOpener = com.point.core.flow.BrowserOpener { },
        sharedTexts: com.point.core.flow.SharedTexts = FakeSharedTexts(),

        keyNeeding: Set<CapabilityId> = emptySet(),

        pdf: com.point.core.flow.PdfRasterizer = FakePdfRasterizer(),
    ) = FlowViewModel(store, FakeRegistry(caps, cloud, slow, keyNeeding) { userKeys.keys().mine.isNotEmpty() }, resolver, chatResponder, enrichment, history, usage, chosenApps, userKeys, aiFacts, builtInKeys, consent, appLauncher, pdf, sensory, sensorySettings, cloudPrivacy, com.point.core.flow.YoloMode.OFF, snapshot, crashLog, dispatcher, pins, AppIconResolver { null }, pcLinks, pcTransport, pcCaps, linkMonitor, PulledFileFactory { name -> java.io.File(java.io.File(System.getProperty("java.io.tmpdir")), "pulled-" + name).absolutePath }, noFrames, keyCheck, account, accountClient, pendingLogins, deviceKeys, browser, sharedTexts)

    private val keyCheck = FakeAiKeyCheck()

    private val aiFacts = FakeAiFacts()

    private val builtInKeys = FakeBuiltInKeys()

    private class FakeAiKeyCheck : com.point.core.flow.AiKeyCheck {
        var probe = com.point.core.flow.KeyProbe(status = 200, reply = "Готово")
        var asked: UserAiConfig? = null

        var explode = false
        override suspend fun check(config: UserAiConfig): com.point.core.flow.KeyProbe {
            asked = config
            if (explode) error("что-то сломалось внутри проверки")
            return probe
        }
    }

    private val chatResponder = FakeChatResponder()
    private val pcCaps = FakePcCaps()
    private val pcLinks = FakePcLinks()
    private val deviceKeys = object : com.point.core.flow.DeviceKeyStore {
        override fun keys() = TEST_KEYS
    }
    private val pcTransport = FakePcTransport()

    private class FakePcCaps : com.point.core.flow.PcCapsStore {
        var saved: List<com.point.core.flow.PcRemoteAction>? = null
        var cleared = false
        override fun all(): List<com.point.core.flow.PcRemoteAction> = saved.orEmpty()
        override suspend fun save(caps: List<com.point.core.flow.PcRemoteAction>) { saved = caps }
        override suspend fun clear() { cleared = true; saved = null }
    }

    private fun bubble(id: String = "a", title: String = "Действие") =
        Bubble("x", title, CapabilityId(id), ObjectState(ObjectKind.TEXT))

    /**
     * #570: у документа без единой страницы предпросмотр не выходит — и человек читал общее
     * «файл не открылся — он повреждён или это не изображение». Пустой документ обязан
     * сказать про себя правду, и это знание остаётся с объектом (#684/#685).
     */
    @Test fun `документ без страниц сам говорит, что страниц в нём нет`() = runTest(dispatcher) {
        store.kind = ObjectKind.PDF
        val vm = vm(
            pdf = object : com.point.core.flow.PdfRasterizer {
                override suspend fun rasterize(obj: PointObject) = ScratchRef("/pages")
                override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? =
                    error(com.point.core.flow.READER_NO_PAGES)
            },
        )

        vm.onShared("doc.pdf", "application/pdf"); advanceUntilIdle()

        val obj = vm.ui.value.frame?.obj
        assertNotNull("объект остаётся на экране", obj)
        assertTrue("годность — часть состояния объекта", obj!!.state.has(Feature.UNUSABLE))
        assertEquals(
            "В документе нет ни одной страницы",
            obj.metadata[com.point.core.flow.META_UNUSABLE_REASON],
        )
    }

    /**
     * Живая охота 12.08.2026 (#812): объект остался открытым, а его файл ушёл вместе со
     * scratch — обводка отвечала «Не удалось открыть страницу для выделения», хотя копия
     * того же объекта лежала в истории.
     */
    @Test fun `обводка берёт файл из истории, когда scratch уже убран`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/jpeg"); advanceUntilIdle()
        val kept = java.io.File.createTempFile("kept", ".jpg").apply { writeBytes(ByteArray(8)) }
        history.opened = PointObject(
            id = vm.ui.value.frame!!.obj.id,
            mime = "image/jpeg",
            uri = ScratchRef(kept.absolutePath),
            state = ObjectState(ObjectKind.IMAGE),
        )

        vm.openSelection(); advanceUntilIdle()

        assertEquals("к истории обратились за файлом", kept.absolutePath, noFrames.askedPath)
    }

    @Test fun `выделение открывается и без слоя слов — отказ приходит от картинки, а не от чтения`() =
        runTest(dispatcher) {

            val vm = vm()
            vm.onShared("uri", "image/jpeg"); advanceUntilIdle()

            vm.openSelection(); advanceUntilIdle()

            assertEquals("Не удалось открыть страницу для выделения", vm.ui.value.message)
            assertEquals(Outcome.FAILED, vm.ui.value.messageOutcome)
        }

    @Test fun `long-press pins, second long-press unpins — with a spoken confirmation`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.togglePin(bubble(id = "a", title = "Действие")); advanceUntilIdle()
        assertEquals("a", pins.pinned[ObjectKind.IMAGE]?.value)
        assertTrue(vm.ui.value.message?.contains("Закреплено") == true)
        assertEquals(CapabilityId("a"), vm.ui.value.frame?.pinned)

        vm.togglePin(bubble(id = "a", title = "Действие")); advanceUntilIdle()
        assertNull(pins.pinned[ObjectKind.IMAGE])
        assertTrue(vm.ui.value.message?.contains("Откреплено") == true)
        assertNull(vm.ui.value.frame?.pinned)
    }

    @Test fun `a direct app pick is remembered and trains usage`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onPickApp(com.point.core.flow.AppTarget("Telegram", "org.tg", "org.tg.Main")); advanceUntilIdle()

        assertEquals(listOf("org.tg"), chosenApps.recorded.map { it.packageName })
        assertEquals(ObjectKind.IMAGE, chosenApps.recorded.single().kind)
        assertTrue(usage.recorded.contains(CapabilityId("app:org.tg#IMAGE")))
    }

    @Test fun `a bridged pick is not remembered as a capability seed`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onPickApp(com.point.core.flow.AppTarget("Acrobat · PDF", "com.adobe", "com.adobe.Main", via = "pdf")); advanceUntilIdle()

        assertTrue(chosenApps.recorded.isEmpty())
    }

    @Test fun `a previous crash surfaces once and is forgotten on dismiss`() = runTest(dispatcher) {
        crashLog.report = "Point 0.2.0 crashed"
        val vm = vm(); advanceUntilIdle()

        assertEquals("Point 0.2.0 crashed", vm.crashReport.value)

        vm.dismissCrashReport(); advanceUntilIdle()
        assertNull(vm.crashReport.value)
        assertEquals(1, crashLog.clearedTimes)
    }

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
        val vm = vm(); vm.restoreJourney(); advanceUntilIdle()

        assertEquals(ObjectKind.TEXT, vm.ui.value.frame?.obj?.state?.kind)
        assertEquals(2, vm.ui.value.path.size)
        assertEquals("Распознать текст", vm.ui.value.path.last().via)
        assertEquals("+380671234567", vm.ui.value.path.let { snapshot.frames.first().metadata["entity.phone"] })
    }

    private fun waybill(id: String, value: String, region: String) = PointObject(
        id = id,
        mime = "text/plain",
        uri = ValueRef(value),
        state = ObjectState(com.point.core.flow.KIND_IDENTIFIER),
        metadata = mapOf(
            com.point.core.flow.META_ENTITY_TRACK to value,
            com.point.core.flow.META_AT_REGION to region,
        ),
        sourceObjects = listOf("root"),
    )

    @Test fun `restore returns found objects, relations and focus to the frame`() = runTest(dispatcher) {
        val a = waybill("root:identifier:A", "20 4514 9154 9395", "10.0 20.0 210.0 60.0")
        val b = waybill("root:identifier:B", "59 0012 3456 7890", "10.0 120.0 210.0 160.0")
        snapshot.frames = listOf(
            com.point.core.model.FlowSnapshotFrame(
                "root", ObjectKind.IMAGE, "image/png", tempFile("img"),
                found = listOf(a, b),
                relations = listOf(
                    com.point.core.model.Relation(a.id, com.point.core.model.RelationType.FOUND_IN, "root"),
                    com.point.core.model.Relation(b.id, com.point.core.model.RelationType.FOUND_IN, "root"),
                ),
                focusRegion = "10.0 120.0 210.0 160.0",
                focusIds = "w3 w4",
            ),
        )
        val vm = vm(); vm.restoreJourney(); advanceUntilIdle()

        val frame = vm.ui.value.frame!!
        assertEquals(listOf(a.id, b.id), frame.found.map { it.id })
        assertEquals(2, frame.relations.size)
        assertEquals(listOf("w3", "w4"), frame.focus?.atomIds)
        assertEquals(120f, frame.focus?.region?.top)

        val (first, second) = frame.found
        assertTrue("объекты одного kind различимы по id", first.id != second.id)
        assertTrue(
            "и по месту на источнике",
            first.metadata[com.point.core.flow.META_AT_REGION] !=
                second.metadata[com.point.core.flow.META_AT_REGION],
        )
    }

    // #769, живая охота 11.08.2026 на почтовой наклейке: человека объявляют двое — роль на
    // документе даёт имя, пара «имя + номер» даёт телефон. Второй молча заменял первого, и
    // внутри найденного человека не оставалось ни телефона, ни «Сохранить контакт».
    @Test fun `один человек объявлен дважды — знание складывается, а не заменяется`() = runTest(dispatcher) {
        val named = PointObject(
            id = "root:party:думброван",
            mime = "text/plain",
            uri = ValueRef("Думброван Олександр"),
            state = ObjectState(com.point.core.flow.KIND_PERSON),
            metadata = mapOf(com.point.core.flow.META_GRAPH_ROLE_PREFIX + "receiver" to "Думброван Олександр"),
            sourceObjects = listOf("root"),
        )
        val withPhone = named.copy(
            state = ObjectState(com.point.core.flow.KIND_PERSON, setOf(com.point.core.model.Feature.HAS_PHONE)),
            metadata = mapOf(com.point.core.flow.META_ENTITY_PREFIX + "phone" to "067 636 05 60"),
        )
        enrichment.updates = listOf(
            EnrichmentUpdate(emptySet(), emptyMap(), emptyList(), objects = listOf(named)),
            EnrichmentUpdate(emptySet(), emptyMap(), emptyList(), objects = listOf(withPhone)),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        val person = vm.ui.value.frame!!.found.single { it.id == named.id }
        assertEquals("067 636 05 60", person.metadata[com.point.core.flow.META_ENTITY_PREFIX + "phone"])
        assertEquals(
            named.uri.value,
            person.metadata[com.point.core.flow.META_GRAPH_ROLE_PREFIX + "receiver"],
        )
        assertTrue("телефон объявлен признаком", person.state.has(com.point.core.model.Feature.HAS_PHONE))
    }

    @Test fun `entering a found object carries only its own relations`() = runTest(dispatcher) {
        val a = waybill("root:identifier:A", "20 4514 9154 9395", "10.0 20.0 210.0 60.0")
        val b = waybill("root:identifier:B", "59 0012 3456 7890", "10.0 120.0 210.0 160.0")
        enrichment.updates = listOf(
            EnrichmentUpdate(
                emptySet(), emptyMap(), emptyList(),
                objects = listOf(a, b),
                relations = listOf(
                    com.point.core.model.Relation(a.id, com.point.core.model.RelationType.FOUND_IN, "root"),
                    com.point.core.model.Relation(b.id, com.point.core.model.RelationType.FOUND_IN, "root"),
                ),
            ),
        )
        enrichment.understandsOnce = true
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        val root = vm.ui.value.frame!!
        assertEquals(2, root.found.size)

        vm.onFound(root.found.first { it.id == a.id }); advanceUntilIdle()

        val child = vm.ui.value.frame!!
        assertEquals(a.id, child.obj.id)
        assertEquals("только связи самого объекта", listOf(a.id), child.relations.map { it.fromId })
        assertTrue("чужие находки не тащим", child.found.isEmpty())

        vm.onBack()
        assertEquals("родительский кадр цел", 2, vm.ui.value.frame?.found?.size)
    }

    @Test fun `persist writes found, relations and focus so the journey survives`() = runTest(dispatcher) {
        val a = waybill("root:identifier:A", "20 4514 9154 9395", "10.0 20.0 210.0 60.0")
        enrichment.updates = listOf(
            EnrichmentUpdate(
                emptySet(), emptyMap(), emptyList(),
                objects = listOf(a),
                relations = listOf(
                    com.point.core.model.Relation(a.id, com.point.core.model.RelationType.FOUND_IN, "root"),
                ),
            ),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.focusOn(
            com.point.core.flow.Focus(
                objectId = vm.ui.value.frame!!.obj.id,
                region = com.point.core.flow.Box(10f, 120f, 210f, 160f),
                atomIds = listOf("w3", "w4"),
            ),
        )
        advanceUntilIdle()

        val persisted = snapshot.saved.last().first()
        assertEquals(listOf(a.id), persisted.found.map { it.id })
        assertEquals(1, persisted.relations.size)
        assertEquals("10.0 120.0 210.0 160.0", persisted.focusRegion)
        assertEquals("w3 w4", persisted.focusIds)
    }

    @Test fun `a snapshot does not auto-open without an explicit restore — launcher lands on Home`() = runTest(dispatcher) {
        snapshot.frames = listOf(
            com.point.core.model.FlowSnapshotFrame("root", ObjectKind.IMAGE, "image/png", tempFile("img")),
        )
        val vm = vm(); advanceUntilIdle()
        assertNull(vm.ui.value.frame)
    }

    @Test fun `a frame whose file died is skipped on restore`() = runTest(dispatcher) {
        snapshot.frames = listOf(
            com.point.core.model.FlowSnapshotFrame("root", ObjectKind.IMAGE, "image/png", tempFile("img")),
            com.point.core.model.FlowSnapshotFrame("gone", ObjectKind.TEXT, "text/plain", "/nowhere/gone.txt"),
        )
        val vm = vm(); vm.restoreJourney(); advanceUntilIdle()

        assertEquals(1, vm.ui.value.path.size)
        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind)
    }

    @Test fun `a fresh share wins over a stale snapshot`() = runTest(dispatcher) {
        snapshot.frames = listOf(
            com.point.core.model.FlowSnapshotFrame("old", ObjectKind.TEXT, "text/plain", tempFile("old")),
        )
        val vm = vm()
        vm.onShared("uri", "image/png")
        vm.restoreJourney()
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

    @Test fun `конец флоу стирает рабочую копию объекта, а не только запись о пути`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        val beforeEnd = store.clearedTimes

        vm.endFlow(); advanceUntilIdle()

        assertTrue("копия объекта обязана быть стёрта", store.clearedTimes > beforeEnd)
    }

    @Test fun `конец флоу уносит и расшаренный текст, а не только рабочую копию`() = runTest(dispatcher) {
        val texts = FakeSharedTexts()
        val vm = vm(sharedTexts = texts)
        vm.onSharedText("пароль от почты"); advanceUntilIdle()
        assertTrue("текст обязан лечь файлом — иначе флоу его не примет", texts.files().isNotEmpty())

        vm.endFlow(); advanceUntilIdle()

        assertTrue("на диске не должно остаться расшаренного текста", texts.files().isEmpty())
    }

    @Test fun `уход из флоу снимает начатую работу`() = runTest(dispatcher) {
        val vm = vm(slow = setOf(CapabilityId("a")))
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble("a"))
        assertNotNull("работа обязана идти — иначе снимать нечего", vm.ui.value.busy)

        vm.endFlow(); advanceUntilIdle()

        assertNull("после ухода работа не продолжается", vm.ui.value.busy)
        assertNull("и её результат не приезжает поверх «Недавнего»", vm.ui.value.frame)
    }

    @Test fun `назад с экрана входа возвращает в Point, а не закрывает его`() = runTest(dispatcher) {

        val vm = vm(account = FakeAccountStore(null), accountClient = CountingSignInClient(readyAfter = Int.MAX_VALUE))
        vm.signIn(); dispatcher.scheduler.advanceTimeBy(5_000)
        assertNotNull("дверь входа обязана быть на экране", vm.ui.value.signIn)

        val handled = vm.onBack()

        assertTrue("«назад» обязан быть обработан, а не уйти системе", handled)
        assertNull("экран входа закрылся", vm.ui.value.signIn)
    }

    @Test fun `открытие из истории стирает копию прошлого объекта`() = runTest(dispatcher) {
        history.opened = PointObject("old", "image/png", ScratchRef("/old"), ObjectState(ObjectKind.IMAGE))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        val beforeOpen = store.clearedTimes

        vm.openFromHistory(entry("h")); advanceUntilIdle()

        assertTrue("копия прошлого объекта обязана быть стёрта", store.clearedTimes > beforeOpen)
        assertEquals("old", vm.ui.value.frame?.obj?.id)
    }

    private fun entry(id: String) =
        HistoryEntry(id, "image/png", ObjectKind.IMAGE, "Фото", 0L, ScratchRef("/hist/$id"))

    @Test fun `onShared ingests, pushes a frame and records history`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png")
        advanceUntilIdle()

        val s = vm.ui.value
        assertEquals(ObjectKind.IMAGE, s.frame?.obj?.state?.kind)
        assertNull(s.busy)
        assertEquals(1, history.recorded.size)
    }

    @Test fun `имя от двери доезжает и до экрана, и до «Недавнего»`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "text/plain", name = "Пришлите договор до пятницы")
        advanceUntilIdle()

        assertEquals("Пришлите договор до пятницы", vm.ui.value.frame?.obj?.metadata?.get("name"))
        assertEquals("Пришлите договор до пятницы", history.recorded.single().metadata["name"])
    }

    @Test fun `дверь имени не дала — объект всё равно назван человечески`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png")
        advanceUntilIdle()

        val name = vm.ui.value.frame?.obj?.metadata?.get("name")
        assertNotNull("объект остался без имени — в «Недавнем» его не узнать", name)
        assertFalse(
            "имя осталось машинным: " + name,
            com.point.core.flow.looksMachineName(name),
        )
    }

    @Test fun `пример открывается как обычный объект`() = runTest(dispatcher) {
        val vm = vm()

        vm.openExample(exampleObject("com.point", 42))
        advanceUntilIdle()

        val frame = vm.ui.value.frame
        assertNotNull("тап по примеру не открыл ничего", frame)
        assertEquals("Визитка · пример", frame?.obj?.metadata?.get("name"))
        assertTrue("у примера нет обычных действий — значит это не объект", frame!!.bubbles.isNotEmpty())
        assertEquals("«Недавнее» обязано помнить пример, как любой объект", 1, history.recorded.size)
        assertNull(vm.ui.value.busy)
    }

    @Test fun `пример проходит обычную уборку`() = runTest(dispatcher) {
        val vm = vm()
        vm.openExample(exampleObject("com.point", 42))
        advanceUntilIdle()
        val before = store.clearedTimes

        vm.endFlow()
        advanceUntilIdle()

        assertTrue("копия примера осталась лежать на диске", store.clearedTimes > before)
        assertNull(vm.ui.value.frame)
    }

    @Test fun `адрес примера ведёт в ресурсы самого приложения`() {
        val example = exampleObject("com.point", 42)

        assertEquals("android.resource://com.point/42", example.uri)
        assertEquals("image/jpeg", example.mime)
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

        assertEquals(Outcome.FAILED, s.messageOutcome)

        assertEquals("Попробуйте поделиться объектом в Point ещё раз", shareAgainHint(s.messageOutcome))
    }

    @Test fun `удача после отказа не наследует знак отказа`() = runTest(dispatcher) {
        resolver.throwsOnPerform = IllegalStateException("scratch-файл исчез")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()
        assertEquals(Outcome.FAILED, vm.ui.value.messageOutcome)

        vm.togglePin(bubble(id = "a", title = "Действие")); advanceUntilIdle()

        assertTrue(vm.ui.value.message?.contains("Закреплено") == true)
        assertEquals(Outcome.DONE, vm.ui.value.messageOutcome)
    }

    @Test fun `вход спрашивает имя у системы, а не ждёт копии`() = runTest(dispatcher) {
        store.systemName = "договор.pdf"
        val vm = vm()

        vm.onShared("content://док", "application/pdf")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("имя не спрошено до копии", 1, store.nameAsked)
        assertNotNull("объект не открылся", vm.ui.value.frame)
    }

    @Test fun `отменённый вход убирает недокопированное`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("content://большой", "application/zip")
        val clearedBefore = store.clearedTimes

        vm.cancelAction()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue("scratch не убран после отмены входа", store.clearedTimes > clearedBefore)
    }

    @Test fun `отменённое человеком не выдаёт себя за сделанное`() = runTest(dispatcher) {
        resolver.holdMs = 10_000
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble())
        dispatcher.scheduler.advanceTimeBy(50)
        vm.cancelAction()
        advanceUntilIdle()

        assertEquals("Отменено", vm.ui.value.message)
        assertEquals(Outcome.NONE, vm.ui.value.messageOutcome)

        assertNull(shareAgainHint(vm.ui.value.messageOutcome))
    }

    @Test fun `кнопка отмены есть только там, где есть что отменять`() = runTest(dispatcher) {
        val vm = vm(slow = setOf(CapabilityId("a")))

        vm.onShared("uri", "image/png")
        assertTrue("экран ожидания поднят", showsBusyScreen(vm.ui.value))

        // #640: вход тоже отменяем — большой файл копируется секундами, и человек вправе
        // передумать, не убивая приложение.
        assertTrue("вход обязан быть отменяемым", showsCancel(vm.ui.value))
        advanceUntilIdle()

        resolver.holdMs = 1_000
        vm.onBubble(bubble(id = "a"))
        dispatcher.scheduler.advanceTimeBy(10)
        assertTrue(showsCancel(vm.ui.value))
    }

    @Test fun `отмена снимает идущую работу, а не законченную`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "a")); advanceUntilIdle()
        vm.endFlow(); advanceUntilIdle()
        history.opened = PointObject("hist", "image/png", ScratchRef("/hist"), ObjectState(ObjectKind.IMAGE))

        vm.openFromHistory(entry("h"))
        assertTrue("отсюда возвращаться есть куда — кнопка на месте", showsCancel(vm.ui.value))
        vm.cancelAction()
        advanceUntilIdle()

        assertNull("объект не открылся вопреки отмене", vm.ui.value.frame)
        assertNull("и «Отменено» не осталось висеть без объекта", vm.ui.value.message)
    }

    @Test fun `снятая работа не приземляется, даже если сама об отмене не знает`() = runTest(dispatcher) {
        resolver.result = ActionResult.Success(
            ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/out")),
        )
        resolver.holdMs = 1_000
        resolver.uninterruptible = true
        val vm = vm(slow = setOf(CapabilityId("a")))
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "a"))
        dispatcher.scheduler.advanceTimeBy(10)
        assertTrue("над идущей работой кнопка есть", showsCancel(vm.ui.value))
        vm.cancelAction()
        advanceUntilIdle()

        assertEquals("объект, доработанный после отмены, не приземлился", 1, vm.ui.value.path.size)
        assertEquals("Отменено", vm.ui.value.message)
    }

    @Test fun `отменённый поиск приложений не открывает выбор`() = runTest(dispatcher) {
        appLauncher.apps = listOf(AppTarget("Telegram", "org.tg", "org.tg.Main"))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "open-in"))
        assertTrue(showsCancel(vm.ui.value))
        vm.cancelAction()
        advanceUntilIdle()

        assertNull("выбор приложений не всплыл", vm.ui.value.appPicker)
        assertEquals("Отменено", vm.ui.value.message)
    }

    /**
     * Живой прогон #692: экран «Понять» без связи держался 11 минут, «Отменить» нажали трижды-
     * и ничего. На телефоне работа начинается прямо в потоке нажатия, поэтому шаг, начатый
     * внутри подготовки, успевал увести отслеживание на себя, а конец подготовки стирал его.
     * Экран оставался занят, а отменять было нечего.
     */
    @Test fun `экран занят — значит есть что отменять, даже если работа сменилась внутри`() {
        val onTap = UnconfinedTestDispatcher(dispatcher.scheduler)
        Dispatchers.setMain(onTap)
        runTest(onTap) {
            resolver.holdMs = 10_000
            val vm = vm(slow = setOf(CapabilityId("a")))
            vm.onShared("uri", "image/png"); advanceUntilIdle()

            vm.onBubble(bubble(id = "a"))
            assertTrue("экран ожидания с кнопкой отмены поднят", showsCancel(vm.ui.value))

            vm.cancelAction()
            advanceUntilIdle()

            assertNull("экран ожидания не ушёл по отказу", vm.ui.value.busy)
            assertEquals("Отменено", vm.ui.value.message)
            assertEquals("работа доехала до конца вопреки отказу", 1, vm.ui.value.path.size)
        }
    }

    @Test fun `отказ гасит и саму работу, а не только экран`() {
        val onTap = UnconfinedTestDispatcher(dispatcher.scheduler)
        Dispatchers.setMain(onTap)
        runTest(onTap) {
            resolver.result = ActionResult.Success(
                ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/out")),
            )
            resolver.holdMs = 10_000
            val vm = vm(slow = setOf(CapabilityId("a")))
            vm.onShared("uri", "image/png"); advanceUntilIdle()

            vm.onBubble(bubble(id = "a"))
            vm.cancelAction()
            advanceUntilIdle()

            assertEquals("снятая работа всё-таки приземлилась", 1, vm.ui.value.path.size)
            assertNull(vm.ui.value.busy)
        }
    }

    private fun alreadyAsked(id: String) = listOf(
        EnrichmentUpdate(
            emptySet(),
            com.point.core.flow.withInvestigation(
                emptyMap(),
                CapabilityId(id),
                com.point.core.flow.InvestigationState.FOUND,
            ),
            emptyList(),
        ),
    )

    @Test fun `уход с экрана посреди работы снимает её, а не бросает лететь в пустоту (#668)`() =
        runTest(dispatcher) {
            // Решение владельца: брошенное действие отменяется. Иначе облачный вызов долетает
            // и оплачивается уже после того, как человек ушёл, а исход не увидит никто.
            resolver.result = ActionResult.Success(
                ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/out")),
            )
            resolver.holdMs = 10_000
            resolver.uninterruptible = true
            val vm = vm(slow = setOf(CapabilityId("a")))
            vm.onShared("uri", "image/png"); advanceUntilIdle()

            vm.onBubble(bubble(id = "a"))
            dispatcher.scheduler.advanceTimeBy(10)
            assertTrue("работа идёт и отменяема", showsCancel(vm.ui.value))

            assertTrue("уход обязан быть обработан, а не улететь мимо", vm.onBack())
            advanceUntilIdle()

            assertNull("экран ожидания остался висеть после ухода", vm.ui.value.busy)
            assertEquals("брошенная работа всё-таки приземлилась", 1, vm.ui.value.path.size)
        }

    @Test fun `повторное облачное действие спрашивает, а не жжёт облако молча (#668)`() =
        runTest(dispatcher) {
            enrichment.updates = alreadyAsked("a")
            val vm = vm(cloud = setOf(CapabilityId("a")))
            vm.onShared("uri", "image/png"); advanceUntilIdle()
            consent.granted = true
            val before = resolver.performed.size

            vm.onBubble(bubble(id = "a")); advanceUntilIdle()

            assertNotNull("повторный тап обязан спросить", vm.ui.value.preview)
            assertEquals("а до тех пор — ни одного вызова", before, resolver.performed.size)
        }

    @Test fun `согласие на повтор доводит облачное действие до конца (#668)`() = runTest(dispatcher) {
        enrichment.updates = alreadyAsked("a")
        val vm = vm(cloud = setOf(CapabilityId("a")))
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        consent.granted = true
        vm.onBubble(bubble(id = "a")); advanceUntilIdle()
        assertNotNull("спросить обязаны до того, как соглашаться", vm.ui.value.preview)

        vm.confirmPreview(); advanceUntilIdle()

        assertNull("вопрос остался висеть после согласия", vm.ui.value.preview)
        assertTrue("согласились повторить — а вызова не было", CapabilityId("a") in resolver.performed)
    }

    @Test fun `первый раз облачное действие ничего не переспрашивает (#668)`() = runTest(dispatcher) {
        val vm = vm(cloud = setOf(CapabilityId("a")))
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        consent.granted = true

        vm.onBubble(bubble(id = "a")); advanceUntilIdle()

        assertNull("не спрашивали — не о чем и переспрашивать", vm.ui.value.preview)
        assertTrue(CapabilityId("a") in resolver.performed)
    }

    @Test fun `нечего отменять — нечего и объявлять отменённым`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "a")); advanceUntilIdle()

        vm.cancelAction()

        assertEquals("done", vm.ui.value.message)
    }

    @Test fun `сохранённый ключ AI — это удача, а не сорванный шаринг`() = runTest(dispatcher) {
        val vm = vm()

        vm.saveAiKey(com.point.core.flow.UserAiKey("openrouter", "sk-1")); advanceUntilIdle()

        val s = vm.ui.value
        assertNull(s.frame)
        assertEquals("Ключ сохранён", s.message)
        assertEquals(Outcome.DONE, s.messageOutcome)
        assertNull(shareAgainHint(s.messageOutcome))
    }

    @Test fun `сообщение без объекта убирается, а не запирает человека`() = runTest(dispatcher) {
        val vm = vm()

        vm.saveAiKey(com.point.core.flow.UserAiKey("openrouter", "sk-1")); advanceUntilIdle()
        assertEquals("Ключ сохранён", vm.ui.value.message)

        assertTrue("из состояния-сообщения нет выхода", vm.dismissMessage())
        assertNull(vm.ui.value.message)
        assertEquals(Outcome.NONE, vm.ui.value.messageOutcome)

        assertFalse(vm.dismissMessage())
    }

    @Test fun `недоступный объект из истории тоже отпускает человека`() = runTest(dispatcher) {
        history.opened = null
        val vm = vm()

        vm.openFromHistory(
            HistoryEntry("id", "text/plain", ObjectKind.TEXT, "имя", 0L, ScratchRef("/gone")),
        )
        advanceUntilIdle()

        assertEquals("Объект недоступен", vm.ui.value.message)
        assertTrue(vm.dismissMessage())
        assertNull(vm.ui.value.message)
    }

    @Test fun `сообщение над объектом не считается тупиком`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        assertFalse(vm.dismissMessage())
    }

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
        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind)
    }

    @Test fun `a Failure step shows its reason, not a dead-end`() = runTest(dispatcher) {
        resolver.result = ActionResult.Failure("Не удалось распознать", recoverable = true)
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals("Не удалось распознать", vm.ui.value.message)
        assertNull(vm.ui.value.busy)
    }

    @Test fun `взорвавшийся реализатор доходит до человека сообщением, а не тишиной`() = runTest(dispatcher) {
        resolver.throwsOnPerform = IllegalStateException("scratch-файл исчез")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals("scratch-файл исчез", vm.ui.value.message)
        assertEquals(Outcome.FAILED, vm.ui.value.messageOutcome)
        assertNull("экран ожидания обязан уйти, иначе это «зависло»", vm.ui.value.busy)
    }

    @Test fun `пузырёк без реализатора отвечает отказом на языке человека, а не падением`() = runTest(dispatcher) {
        resolver.noRealizer = true
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals(Outcome.FAILED, vm.ui.value.messageOutcome)
        assertEquals("Действие недоступно", vm.ui.value.message)
        assertEquals(Outcome.FAILED, vm.ui.value.messageOutcome)
        assertNull(vm.ui.value.busy)
    }

    @Test fun `a previewed action shows the preview first, then confirm runs it`() = runTest(dispatcher) {
        resolver.previews = mapOf(CapabilityId("a") to Preview("Добавить в контакты", listOf("Иван")))
        resolver.result = ActionResult.Done("Открываю контакт…")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()
        assertEquals("Добавить в контакты", vm.ui.value.preview?.title)
        assertNull(vm.ui.value.message)

        vm.confirmPreview(); advanceUntilIdle()
        assertNull(vm.ui.value.preview)
        assertEquals("Открываю контакт…", vm.ui.value.message)
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
        assertEquals("content://bg/1", resolver.lastAmendment)
    }

    @Test fun `the busy label is the action title while it runs`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(title = "Распознать текст"))
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

    @Test fun `a fast local action is quiet busy — the screen must not switch away`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble())
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

    @Test fun `a quiet action speaks on the object — its stage reaches the state`() = runTest(dispatcher) {
        resolver.stage = "Распаковываю архив"
        resolver.holdMs = 1_000
        val vm = vm()
        vm.onShared("uri", "application/zip"); advanceUntilIdle()

        vm.onBubble(bubble())
        dispatcher.scheduler.advanceTimeBy(50)

        assertEquals("Распаковываю архив", quietStage(vm.ui.value))
    }

    @Test fun `a quiet action does not raise the busy screen — the object stays on screen`() = runTest(dispatcher) {
        resolver.stage = "Распаковываю архив"
        resolver.holdMs = 1_000
        val vm = vm()
        vm.onShared("uri", "application/zip"); advanceUntilIdle()

        vm.onBubble(bubble())
        dispatcher.scheduler.advanceTimeBy(50)

        assertEquals(false, showsBusyScreen(vm.ui.value))
        assertTrue(objectWorking(vm.ui.value))
    }

    @Test fun `slow work keeps the full busy screen — and the object says nothing over it`() = runTest(dispatcher) {
        resolver.stage = "Читаю текст на устройстве"
        resolver.holdMs = 1_000
        val vm = vm(slow = setOf(CapabilityId("a")))
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble())
        dispatcher.scheduler.advanceTimeBy(50)

        assertTrue(showsBusyScreen(vm.ui.value))
        assertEquals("Читаю текст на устройстве", vm.ui.value.busyStage)
        assertNull(quietStage(vm.ui.value))
    }

    @Test fun `a new action never wears the words of the previous one`() = runTest(dispatcher) {
        resolver.stage = "Распаковываю архив"
        resolver.holdMs = 1_000
        val vm = vm(caps = mapOf(CapabilityId("a") to setOf(Intent.PREPARE), CapabilityId("b") to setOf(Intent.PREPARE)))
        vm.onShared("uri", "application/zip"); advanceUntilIdle()

        vm.onBubble(bubble(id = "a"))
        dispatcher.scheduler.advanceTimeBy(50)
        assertEquals("Распаковываю архив", quietStage(vm.ui.value))

        resolver.stage = null
        vm.onBubble(bubble(id = "b"))
        dispatcher.scheduler.advanceTimeBy(50)

        assertNull(quietStage(vm.ui.value))
    }

    @Test fun `смененная работа замолкает — новое действие не носит её слов`() = runTest(dispatcher) {

        resolver.stage = "Читаю текст на устройстве"
        resolver.lateStage = "Пробую повернуть страницу — 2 из 3"
        resolver.holdMs = 1_000
        val vm = vm(caps = mapOf(CapabilityId("a") to setOf(Intent.PREPARE), CapabilityId("b") to setOf(Intent.PREPARE)))
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "a"))
        dispatcher.scheduler.advanceTimeBy(10)

        resolver.stage = null
        resolver.lateStage = null
        vm.onBubble(bubble(id = "b"))
        dispatcher.scheduler.advanceTimeBy(500)

        assertTrue("объект работает — строке есть где появиться", objectWorking(vm.ui.value))
        assertNull("но слова принадлежали бы снятой работе", quietStage(vm.ui.value))
    }

    @Test fun `остановленная работа не оставляет слов в состоянии`() = runTest(dispatcher) {

        resolver.stage = "Читаю текст на устройстве"
        resolver.lateStage = "Пробую повернуть страницу — 2 из 3"
        resolver.holdMs = 1_000
        val vm = vm(slow = setOf(CapabilityId("a")))
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "a"))
        dispatcher.scheduler.advanceTimeBy(10)
        assertTrue(showsBusyScreen(vm.ui.value))

        vm.cancelAction()
        dispatcher.scheduler.advanceTimeBy(500)

        assertNull(vm.ui.value.busyStage)
    }

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
        vm.onBubble(bubble()); advanceUntilIdle()
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

        assertEquals(false, vm.onBack())
    }

    @Test fun `background enrichment augments the state with the discovered hint`() = runTest(dispatcher) {
        enrichment.features = setOf(Feature.HAS_URL)
        val vm = vm()
        vm.onShared("uri", "text/plain"); advanceUntilIdle()

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
        dispatcher.scheduler.advanceTimeBy(150)

        val mid = vm.ui.value.frame
        assertTrue(mid?.obj?.state?.has(Feature.HAS_URL) == true)
        assertEquals(false, mid?.obj?.state?.has(Feature.HAS_PHONE))
        assertEquals(listOf("Распознаю текст…"), mid?.enriching)

        advanceUntilIdle()
        val end = vm.ui.value.frame
        assertTrue(end?.obj?.state?.has(Feature.HAS_PHONE) == true)
        assertTrue(end?.enriching?.isEmpty() == true)
    }

    private fun humanFindings(key: String, value: String) = com.point.core.model.Findings(
        metadata = mapOf(
            key to value,
            key + com.point.core.flow.META_SOURCE_SUFFIX to com.point.core.model.Provenance.HUMAN.wire,
        ),
    )

    @Test fun `done findings from a user action land in the graph and persist`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(setOf(Feature.HAS_PHONE), mapOf("entity.phone" to "111"), emptyList()),
        )
        resolver.result = ActionResult.Done("Исправлено", humanFindings("entity.phone", "112"))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()

        val meta = vm.ui.value.frame!!.obj.metadata
        assertEquals("человеческое слово стало главным", "112", meta["entity.phone"])
        assertEquals(
            com.point.core.model.Provenance.HUMAN,
            com.point.core.flow.provenanceOf(meta, "entity.phone"),
        )
        assertTrue("машинное чтение осталось историей",
            com.point.core.flow.alternativesOf(meta, "entity.phone").contains("111"))
        assertTrue("спора нет", !com.point.core.flow.isDisputed(meta, "entity.phone"))

        val persisted = snapshot.saved.last().first().metadata
        assertEquals("112", persisted["entity.phone"])
    }

    @Test fun `done without findings behaves exactly as before`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("done")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        val before = vm.ui.value.frame!!.obj.metadata
        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals("done", vm.ui.value.message)
        assertEquals(before, vm.ui.value.frame!!.obj.metadata)
    }

    @Test fun `a correction made inside the found value reaches the parent fact`() = runTest(dispatcher) {
        val node = PointObject(
            id = "in:phone",
            mime = "text/plain",
            uri = ValueRef("111"),
            state = ObjectState(com.point.core.flow.KIND_PHONE),
            metadata = mapOf("entity.phone" to "111"),
            sourceObjects = listOf("in"),
        )
        enrichment.updates = listOf(
            EnrichmentUpdate(
                setOf(Feature.HAS_PHONE), mapOf("entity.phone" to "111"), emptyList(),
                objects = listOf(node),
                relations = listOf(com.point.core.model.Relation(node.id, com.point.core.model.RelationType.FOUND_IN, "in")),
            ),
        )
        enrichment.understandsOnce = true
        resolver.result = ActionResult.Done("Исправлено", humanFindings("entity.phone", "112"))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onFound(vm.ui.value.frame!!.found.single()); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals("правка легла в кадр значения", "112", vm.ui.value.frame!!.obj.metadata["entity.phone"])

        vm.onBack()
        val parent = vm.ui.value.frame!!
        assertEquals("носитель истины — родительский факт", "112", parent.obj.metadata["entity.phone"])
        assertEquals(
            com.point.core.model.Provenance.HUMAN,
            com.point.core.flow.provenanceOf(parent.obj.metadata, "entity.phone"),
        )

        val chip = parent.found.single()
        assertEquals("найденный узел не остался со старым значением", "112", chip.metadata["entity.phone"])
        assertEquals(com.point.core.model.Provenance.HUMAN, chip.provenance)

        val persisted = snapshot.saved.last().first()
        assertEquals("112", persisted.metadata["entity.phone"])
        assertEquals("112", persisted.found.single().metadata["entity.phone"])
    }

    @Test fun `правка человека доезжает до карточки «Недавнего», а не только до журнала`() = runTest(dispatcher) {
        val node = PointObject(
            id = "in:email",
            mime = "text/plain",
            uri = ValueRef("greatfloridaagent321@gmail.com"),
            state = ObjectState(com.point.core.flow.KIND_EMAIL),
            metadata = mapOf("entity.email" to "greatfloridaagent321@gmail.com"),
            sourceObjects = listOf("in"),
        )
        enrichment.updates = listOf(
            EnrichmentUpdate(
                setOf(Feature.HAS_EMAIL), mapOf("entity.email" to "greatfloridaagent321@gmail.com"), emptyList(),
                objects = listOf(node),
                relations = listOf(com.point.core.model.Relation(node.id, com.point.core.model.RelationType.FOUND_IN, "in")),
            ),
        )
        enrichment.understandsOnce = true
        resolver.result = ActionResult.Done("Исправлено", humanFindings("entity.email", "liz321@gmail.com"))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onFound(vm.ui.value.frame!!.found.single()); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        val card = history.updated.last { it.id == history.recorded.single().id }
        assertEquals("карточка несёт слово человека", "liz321@gmail.com", card.metadata["entity.email"])
    }

    @Test fun `human provenance never comes from focus or navigation`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(setOf(Feature.HAS_PHONE), mapOf("entity.phone" to "111"), emptyList()),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.focusOn(com.point.core.flow.Focus(vm.ui.value.frame!!.obj.id, com.point.core.flow.Box(0f, 0f, 5f, 5f)))
        advanceUntilIdle()

        val meta = vm.ui.value.frame!!.obj.metadata
        assertTrue(
            "ни один факт не стал человеческим от жеста",
            meta.keys.none {
                it.endsWith(com.point.core.flow.META_SOURCE_SUFFIX) &&
                    meta[it] == com.point.core.model.Provenance.HUMAN.wire
            },
        )
    }

    @Test fun `a restored journey keeps the human word as primary`() = runTest(dispatcher) {
        snapshot.frames = listOf(
            com.point.core.model.FlowSnapshotFrame(
                "root", ObjectKind.IMAGE, "image/png", tempFile("img"),
                metadata = mapOf(
                    "entity.phone" to "112",
                    "entity.phone" + com.point.core.flow.META_SOURCE_SUFFIX to
                        com.point.core.model.Provenance.HUMAN.wire,
                    "entity.phone" + com.point.core.flow.META_ALT_SUFFIX to "111",
                ),
            ),
        )
        val vm = vm(); vm.restoreJourney(); advanceUntilIdle()

        val meta = vm.ui.value.frame!!.obj.metadata
        assertEquals("112", meta["entity.phone"])
        assertEquals(com.point.core.model.Provenance.HUMAN, com.point.core.flow.provenanceOf(meta, "entity.phone"))
        assertEquals(listOf("111"), com.point.core.flow.alternativesOf(meta, "entity.phone"))
    }

    @Test fun `machine repair of the primary also updates the found node`() = runTest(dispatcher) {
        val ocrRead = "Іваненко 1ван"
        val repaired = "Іваненко Іван"
        val node = PointObject(
            "in:address", "text/plain", ValueRef(ocrRead),
            ObjectState(com.point.core.flow.KIND_ADDRESS), mapOf("entity.address" to ocrRead),
            sourceObjects = listOf("in"),
        )
        enrichment.updates = listOf(
            EnrichmentUpdate(emptySet(), mapOf("entity.address" to ocrRead), emptyList(), objects = listOf(node)),
            EnrichmentUpdate(emptySet(), mapOf("entity.address" to repaired), emptyList()),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        val frame = vm.ui.value.frame!!
        assertEquals("ремонт стал главным значением", repaired, frame.obj.metadata["entity.address"])

        val chip = frame.found.single()
        assertEquals("узел не остался со старым прочтением", repaired, chip.metadata["entity.address"])
        assertEquals("идентичность узла стабильна", "in:address", chip.id)

        // идемпотентность: то же самое ещё раз ничего не меняет
        val again = vm.ui.value.frame!!.found.single()
        assertEquals(repaired, again.metadata["entity.address"])
        assertEquals(1, vm.ui.value.frame!!.found.size)
    }

    @Test fun `a failed look reaches the frame so the human can see it`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(
                emptySet(), emptyMap(), emptyList(),
                failed = listOf(
                    com.point.core.flow.FailedInvestigation(
                        CapabilityId("qr"), "QR", "изображение не открылось",
                    ),
                ),
            ),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        val failed = vm.ui.value.frame!!.failed
        assertEquals(listOf("изображение не открылось"), failed.map { it.reason })
    }

    @Test fun `a successful retry clears the failed note for that question`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(
                emptySet(), emptyMap(), emptyList(),
                failed = listOf(
                    com.point.core.flow.FailedInvestigation(CapabilityId("qr"), "QR", "изображение не открылось"),
                ),
            ),
            EnrichmentUpdate(
                setOf(Feature.HAS_QR),
                com.point.core.flow.withInvestigation(
                    mapOf("entity.qr" to "https://x"),
                    CapabilityId("qr"),
                    com.point.core.flow.InvestigationState.FOUND,
                ),
                emptyList(),
            ),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        assertTrue("получилось — упрёк снят", vm.ui.value.frame!!.failed.isEmpty())
        assertEquals("https://x", vm.ui.value.frame!!.obj.metadata["entity.qr"])
    }

    @Test fun `operation failures are not knowledge and are not persisted`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(
                emptySet(), mapOf("entity.phone" to "111"), emptyList(),
                failed = listOf(
                    com.point.core.flow.FailedInvestigation(CapabilityId("qr"), "QR", "изображение не открылось"),
                ),
            ),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        val persisted = snapshot.saved.last().first()
        assertEquals("знание — в журнале", "111", persisted.metadata["entity.phone"])
        assertTrue(
            "состояние операции журналом не является",
            persisted.metadata.keys.none { it.contains("fail", ignoreCase = true) },
        )
    }

    // ---- Этап 9 G2: знание объекта переживает pull с компьютера (ADR-0001 §20) ----

    private fun outboxEntry(meta: Map<String, String>) =
        com.point.core.flow.PcOutboxEntry(1, meta)

    @Test fun `pull приносит знание объекта, а не только файл`() = runTest(dispatcher) {
        pcLinks.pc = com.point.core.flow.LinkedPc("pc-1", "Рабочий")
        pcTransport.outbox = listOf(
            outboxEntry(
                mapOf(
                    "name" to "накладная.txt",
                    "mime" to "text/plain",
                    "entity.track" to "20 4514 9154 9395",
                    "entity.track" + com.point.core.flow.META_ALT_SUFFIX to "20 4514 9154 9999",
                    "entity.track" + com.point.core.flow.META_SOURCE_SUFFIX to
                        com.point.core.model.Provenance.HUMAN.wire,
                    com.point.core.flow.investigationKey(CapabilityId("qr")) to
                        com.point.core.flow.InvestigationState.NOT_FOUND.wire,
                ),
            ),
        )
        val vm = vm()
        vm.pullFromPc(); advanceUntilIdle()

        val meta = vm.ui.value.frame!!.obj.metadata
        assertEquals("знание доехало", "20 4514 9154 9395", meta["entity.track"])
        assertEquals("история доехала", listOf("20 4514 9154 9999"),
            com.point.core.flow.alternativesOf(meta, "entity.track"))
        assertEquals("слово человека не потеряло происхождение",
            com.point.core.model.Provenance.HUMAN,
            com.point.core.flow.provenanceOf(meta, "entity.track"))
        assertEquals("состояние знания доехало",
            com.point.core.flow.InvestigationState.NOT_FOUND,
            com.point.core.flow.investigationStateOf(meta, CapabilityId("qr")))
        assertEquals("имя работает как раньше", "накладная.txt", meta["name"])
        assertTrue("служебные ключи не мусорят объект",
            meta.keys.none { it == "mime" || it == "pc.action" })

        val persisted = snapshot.saved.last().first().metadata
        assertEquals("20 4514 9154 9395", persisted["entity.track"])
        assertEquals(com.point.core.model.Provenance.HUMAN.wire,
            persisted["entity.track" + com.point.core.flow.META_SOURCE_SUFFIX])
    }

    @Test fun `pull нескольких объектов работает как раньше`() = runTest(dispatcher) {
        pcLinks.pc = com.point.core.flow.LinkedPc("pc-1", "Рабочий")
        pcTransport.outbox = listOf(
            outboxEntry(mapOf("name" to "a.txt", "mime" to "text/plain", "entity.phone" to "111")),
            outboxEntry(mapOf("name" to "b.txt", "mime" to "text/plain")).copy(id = 2),
        )
        val vm = vm()
        vm.pullFromPc(); advanceUntilIdle()

        assertEquals(ObjectKind.COLLECTION, vm.ui.value.frame?.obj?.state?.kind)
        assertEquals(0, vm.fromPcCount.value)
    }

    @Test fun `pc result stays a found chip while its knowledge lands on the source`() = runTest(dispatcher) {
        val born = PointObject(
            "in:pc:read:txt", "text/plain", ScratchRef("/pc-born.txt"),
            ObjectState(ObjectKind.UNKNOWN), mapOf("name" to "страница.txt"),
            sourceObjects = listOf("in"),
        )
        resolver.result = ActionResult.Done(
            "Прочитать — готово: страница.txt",
            com.point.core.model.Findings(
                metadata = mapOf("entity.phone" to "+380671234567"),
                objects = listOf(born),
                relations = listOf(
                    com.point.core.model.Relation(born.id, com.point.core.model.RelationType.FOUND_IN, "in"),
                ),
            ),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()

        val frame = vm.ui.value.frame!!
        assertEquals("человек остаётся на исходном объекте", "in", frame.obj.id)
        assertEquals("знание с компьютера — в исходнике", "+380671234567", frame.obj.metadata["entity.phone"])
        assertEquals("результат — найденный объект", listOf(born.id), frame.found.map { it.id })
        assertEquals(1, vm.ui.value.path.size)

        val persisted = snapshot.saved.last().first()
        assertEquals("+380671234567", persisted.metadata["entity.phone"])
        assertEquals(listOf(born.id), persisted.found.map { it.id })
    }

    @Test fun `понять остаётся на исходнике — знание прирастает, дубль не рождается`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done(
            "Стало понятнее",
            com.point.core.model.Findings(
                metadata = mapOf("semantic.type" to "purchase", "entity.amount" to "128500"),
            ),
        )
        val vm = vm()
        vm.onShared("uri", "text/plain"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        val frame = vm.ui.value.frame!!
        assertEquals("человек остаётся на своём объекте", "in", frame.obj.id)
        assertEquals("кадр один — дубля нет", 1, vm.ui.value.path.size)
        assertEquals("purchase", frame.obj.metadata["semantic.type"])
        assertEquals("128500", frame.obj.metadata["entity.amount"])
    }

    @Test fun `новое знание действия перезапускает исследования над исходником`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done(
            "Стало понятнее",
            com.point.core.model.Findings(metadata = mapOf("entity.phone" to "+79161234567")),
        )
        val vm = vm()
        vm.onShared("uri", "text/plain"); advanceUntilIdle()
        val before = enrichment.runs

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals("пространство пересматривается после обогащения", before + 1, enrichment.runs)
        assertEquals(
            "пересмотр видит новое знание",
            "+79161234567",
            enrichment.seen.last().metadata["entity.phone"],
        )
    }

    @Test fun `узел второго значения не зеркалится под первый — два телефона остаются двумя`() = runTest(dispatcher) {
        val first = PointObject(
            "in:phone", "text/plain", ValueRef("+380111111111"),
            ObjectState(com.point.core.flow.KIND_PHONE), mapOf("entity.phone" to "+380111111111"),
            sourceObjects = listOf("in"),
        )
        val second = PointObject(
            "in:phone:+380222222222", "text/plain", ValueRef("+380222222222"),
            ObjectState(com.point.core.flow.KIND_PHONE), mapOf("entity.phone" to "+380222222222"),
            sourceObjects = listOf("in"),
        )
        enrichment.updates = listOf(
            EnrichmentUpdate(
                setOf(Feature.HAS_PHONE),
                mapOf(
                    "entity.phone" to "+380111111111",
                    "entity.phone" + com.point.core.flow.META_MORE_SUFFIX to "+380222222222",
                ),
                emptyList(),
                objects = listOf(first, second),
            ),
        )
        val vm = vm()
        vm.onShared("uri", "text/plain"); advanceUntilIdle()

        val chips = vm.ui.value.frame!!.found
        assertEquals(
            "второй узел хранит своё значение, не первое",
            "+380222222222",
            chips.single { it.id == second.id }.metadata["entity.phone"],
        )
    }

    @Test fun `подтверждение того же значения делает узел подтверждённым вами`() = runTest(dispatcher) {
        val node = PointObject(
            "in:phone", "text/plain", ValueRef("111"),
            ObjectState(com.point.core.flow.KIND_PHONE), mapOf("entity.phone" to "111"),
            sourceObjects = listOf("in"),
        )
        enrichment.updates = listOf(
            EnrichmentUpdate(emptySet(), mapOf("entity.phone" to "111"), emptyList(), objects = listOf(node)),
        )
        resolver.result = ActionResult.Done("Подтверждено вами", humanFindings("entity.phone", "111"))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        val chip = vm.ui.value.frame!!.found.single()
        assertEquals(
            "слово человека видно на узле даже при том же значении",
            com.point.core.model.Provenance.HUMAN,
            chip.provenance,
        )
    }

    @Test fun `результат с компьютера исследуется сразу — знание видно у находки без входа`() = runTest(dispatcher) {
        val born = PointObject(
            "in:pc:read:txt", "text/plain", ScratchRef("/pc-born.txt"),
            ObjectState(ObjectKind.TEXT), mapOf("name" to "Текст со снимка"),
            sourceObjects = listOf("in"),
        )
        resolver.result = ActionResult.Done(
            "Прочитать — готово: Текст со снимка",
            com.point.core.model.Findings(
                objects = listOf(born),
                relations = listOf(
                    com.point.core.model.Relation(born.id, com.point.core.model.RelationType.FOUND_IN, "in"),
                ),
            ),
        )
        enrichment.updates = listOf(
            EnrichmentUpdate(setOf(Feature.HAS_PHONE), mapOf("entity.phone" to "+380671234567"), emptyList()),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertTrue("прибывший объект прошёл цикл понимания", enrichment.seen.any { it.id == born.id })
        val chip = vm.ui.value.frame!!.found.single()
        assertEquals("знание видно у находки без входа", "+380671234567", chip.metadata["entity.phone"])
        assertEquals(
            "журнал хранит знание находки",
            "+380671234567",
            snapshot.saved.last().first().found.single().metadata["entity.phone"],
        )
    }

    @Test fun `повторный результат с компьютера обновляет chip, а не остаётся первым`() = runTest(dispatcher) {
        fun run(uri: String) = ActionResult.Done(
            "Прочитать — готово: страница.txt",
            com.point.core.model.Findings(
                objects = listOf(
                    PointObject(
                        "in:pc:read:txt", "text/plain", ScratchRef(uri),
                        ObjectState(ObjectKind.UNKNOWN), mapOf("name" to "страница.txt"),
                        sourceObjects = listOf("in"),
                    ),
                ),
                relations = listOf(
                    com.point.core.model.Relation("in:pc:read:txt", com.point.core.model.RelationType.FOUND_IN, "in"),
                ),
            ),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        resolver.result = run("/pc-born-1.txt")
        vm.onBubble(bubble()); advanceUntilIdle()
        resolver.result = run("/pc-born-2.txt")
        vm.onBubble(bubble()); advanceUntilIdle()

        val chips = vm.ui.value.frame!!.found
        assertEquals("тот же результат — один chip", 1, chips.size)
        assertEquals("chip показывает свежие байты", ScratchRef("/pc-born-2.txt"), chips.single().uri)
        assertEquals(
            "журнал хранит свежий результат",
            "/pc-born-2.txt",
            snapshot.saved.last().first().found.single().uri.value,
        )
    }

    @Test fun `navigation alone does not change the offered order`() = runTest(dispatcher) {
        val node = PointObject(
            "in:phone", "text/plain", ValueRef("111"),
            ObjectState(com.point.core.flow.KIND_PHONE), mapOf("entity.phone" to "111"),
            sourceObjects = listOf("in"),
        )
        enrichment.updates = listOf(
            EnrichmentUpdate(emptySet(), emptyMap(), emptyList(), objects = listOf(node)),
        )
        enrichment.understandsOnce = true
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        val before = vm.ui.value.frame!!.bubbles

        vm.onFound(node); advanceUntilIdle()
        vm.onBack()

        assertEquals("вход и возврат не меняют порядок действий", before, vm.ui.value.frame!!.bubbles)
    }

    @Test fun `picking a bubble does not change the offered order`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("done")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        val before = vm.ui.value.frame!!.bubbles

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals("выбор действия — не намерение", before, vm.ui.value.frame!!.bubbles)
    }

    @Test fun `intent is never persisted — it is derived, not stored`() = runTest(dispatcher) {

        assertTrue(
            "у кадра журнала нет поля intent",
            com.point.core.model.FlowSnapshotFrame::class.java.declaredFields.none {
                it.name.contains("intent", ignoreCase = true)
            },
        )

        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.focusOn(com.point.core.flow.Focus(vm.ui.value.frame!!.obj.id, com.point.core.flow.Box(0f, 0f, 5f, 5f)))
        advanceUntilIdle()

        val persisted = snapshot.saved.last().first()
        assertTrue(
            "intent не просочился в metadata журнала",
            persisted.metadata.keys.none { it.contains("intent", ignoreCase = true) },
        )
    }

    @Test fun `focusOn hands the captured area to the background cycle`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(setOf(Feature.HAS_PHONE), mapOf("entity.phone" to "+380671234567"), emptyList()),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        assertEquals(1, enrichment.runs)

        vm.focusOn(
            com.point.core.flow.Focus(
                objectId = vm.ui.value.frame!!.obj.id,
                region = com.point.core.flow.Box(10f, 20f, 110f, 60f),
            ),
        )
        advanceUntilIdle()

        assertEquals("Focus запускает ровно один новый проход", 2, enrichment.runs)
        val handed = enrichment.seen.last()
        assertEquals("10.0 20.0 110.0 60.0", handed.metadata[com.point.core.flow.META_FOCUS_REGION])
        assertEquals(
            "накопленное знание едет вместе с областью",
            "+380671234567",
            handed.metadata["entity.phone"],
        )
        assertEquals("объект тот же, не новый", vm.ui.value.frame!!.obj.id, handed.id)
    }

    @Test fun `clearFocus does not start another cycle`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.focusOn(com.point.core.flow.Focus(vm.ui.value.frame!!.obj.id, com.point.core.flow.Box(0f, 0f, 5f, 5f)))
        advanceUntilIdle()
        val runsAfterFocus = enrichment.runs

        vm.clearFocus(); advanceUntilIdle()

        assertEquals("clearFocus не запускает исследования", runsAfterFocus, enrichment.runs)
        assertNull(vm.ui.value.frame?.focus)

        // Снятый фокус уходит с экрана вместе со своей картинкой (#757): иначе человек
        // видит область, в которую Point уже не смотрит.
        assertNull("превью снятой области осталось на экране", vm.ui.value.focusPreview)
    }

    @Test fun `a late result of area A lands while the current focus is already B`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(setOf(Feature.HAS_PHONE), mapOf("entity.phone" to "+380671234567"), emptyList()),
        )
        enrichment.stepDelayMs = 200
        val vm = vm()
        vm.onShared("uri", "image/png")
        dispatcher.scheduler.advanceTimeBy(300)

        val id = vm.ui.value.frame!!.obj.id
        val areaA = com.point.core.flow.Focus(id, com.point.core.flow.Box(0f, 0f, 50f, 50f))
        val areaB = com.point.core.flow.Focus(id, com.point.core.flow.Box(0f, 100f, 50f, 150f))
        vm.focusOn(areaA)
        dispatcher.scheduler.advanceTimeBy(50)
        vm.focusOn(areaB)
        advanceUntilIdle()

        assertEquals("текущий Focus остаётся B", 100f, vm.ui.value.frame?.focus?.region?.top)

        assertEquals("поздний результат A всё равно знание объекта", "+380671234567",
            vm.ui.value.frame?.obj?.metadata?.get("entity.phone"))
    }

    @Test fun `restore with focus does not run an unexpected focused cycle`() = runTest(dispatcher) {
        snapshot.frames = listOf(
            com.point.core.model.FlowSnapshotFrame(
                "root", ObjectKind.IMAGE, "image/png", tempFile("img"),
                metadata = mapOf("entity.phone" to "+380671234567"),
                focusRegion = "10.0 20.0 110.0 60.0",
                focusIds = "w3 w4",
            ),
        )
        val vm = vm(); vm.restoreJourney(); advanceUntilIdle()

        assertEquals("только обычный проход кадра, focused не самозапускается", 1, enrichment.runs)
        assertEquals(listOf("w3", "w4"), vm.ui.value.frame?.focus?.atomIds)
        assertEquals("+380671234567", vm.ui.value.frame?.obj?.metadata?.get("entity.phone"))
    }

    @Test fun `a second reading that disagrees is kept beside the first, not dropped`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(setOf(Feature.HAS_PHONE), mapOf("entity.phone" to "+380671234567"), emptyList()),
            EnrichmentUpdate(setOf(Feature.HAS_PHONE), mapOf("entity.phone" to "+380671234599"), emptyList()),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        val metadata = vm.ui.value.frame!!.obj.metadata
        val kept = com.point.core.flow.alternativesOf(metadata, "entity.phone") + metadata.getValue("entity.phone")

        assertTrue("оба прочтения обязаны остаться-" + kept, kept.contains("+380671234567"))
        assertTrue("оба прочтения обязаны остаться-" + kept, kept.contains("+380671234599"))
    }

    @Test fun `focus reaches the executor without losing what the object already knows`() = runTest(dispatcher) {
        enrichment.updates = listOf(
            EnrichmentUpdate(setOf(Feature.HAS_PHONE), mapOf("entity.phone" to "+380671234567"), emptyList()),
        )
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.focusOn(
            com.point.core.flow.Focus(
                objectId = vm.ui.value.frame!!.obj.id,
                region = com.point.core.flow.Box(10f, 20f, 110f, 60f),
                atomIds = listOf("w3", "w4"),
            ),
        )
        vm.onBubble(bubble()); advanceUntilIdle()

        val seen = resolver.lastInput!!

        assertEquals("+380671234567", seen.metadata["entity.phone"])

        assertEquals("10.0 20.0 110.0 60.0", seen.metadata[com.point.core.flow.META_FOCUS_REGION])
        assertEquals("w3 w4", seen.metadata[com.point.core.flow.META_FOCUS_IDS])
    }

    @Test fun `focus does not replace the object with a new one`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        val before = vm.ui.value.frame!!.obj

        vm.focusOn(com.point.core.flow.Focus(before.id, com.point.core.flow.Box(0f, 0f, 5f, 5f)))

        assertEquals(before.id, vm.ui.value.frame?.obj?.id)
        assertEquals(1, vm.ui.value.path.size)
        assertEquals(before.uri, vm.ui.value.frame?.obj?.uri)
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
        enrichment.stepDelayMs = 500
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))
        val vm = vm()
        vm.onShared("uri", "image/png")
        dispatcher.scheduler.advanceTimeBy(50)
        vm.onBubble(bubble())
        dispatcher.scheduler.advanceTimeBy(100)
        assertEquals(ObjectKind.TEXT, vm.ui.value.frame?.obj?.state?.kind)

        advanceUntilIdle()

        vm.onBack()
        assertTrue(vm.ui.value.frame?.obj?.state?.has(Feature.HAS_PHONE) == true)
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

        vm.endFlow(); advanceUntilIdle()

        assertNull(vm.ui.value.frame)
    }

    @Test fun `«Выйти» стирает всё, что устройство знало про аккаунт и про свой компьютер (#472)`() = runTest(dispatcher) {

        val store = FakeAccountStore(TEST_ACCOUNT)
        pcLinks.pc = com.point.core.flow.LinkedPc("d-pc", "Ноутбук", "ключ-ПК")
        val vm = vm(account = store)

        vm.openDevices(); advanceUntilIdle()
        vm.signOut(); advanceUntilIdle()

        assertNull(store.current())
        assertNull(pcLinks.pc)
        assertTrue(pcCaps.cleared)
        assertTrue(vm.ui.value.signIn is com.point.core.flow.SignIn.SignedOut)
    }

    @Test fun `ключ с компьютера доезжает до телефона и включает AI`() = runTest(dispatcher) {

        pcLinks.pc = com.point.core.flow.LinkedPc("d-pc", "Ноутбук", "ключ-ПК")
        pcTransport.secretsReply = com.point.core.flow.SharedSecrets(aiKey = "sk-с-компьютера", at = 900)
        val vm = vm(
            account = FakeAccountStore(TEST_ACCOUNT),
            accountClient = FakeCircleClient(listOf(
                com.point.core.flow.CircleDevice("d-pc", com.point.core.flow.DeviceKind.PC, "Ноутбук", key = "ключ-ПК"),
            )),
        )

        vm.openDevices(); advanceUntilIdle()

        assertEquals("sk-с-компьютера", userKeys.saved?.apiKey)
        assertTrue("экран не узнал, что ключ появился", vm.ui.value.aiKeySet)
    }

    @Test fun `свой ключ не затирается пустым ответом компьютера`() = runTest(dispatcher) {

        userKeys.stored = com.point.core.flow.UserAiKeys.NONE
            .with(com.point.core.flow.UserAiKey("openrouter", "sk-мой", savedAt = 100))
        pcLinks.pc = com.point.core.flow.LinkedPc("d-pc", "Ноутбук", "ключ-ПК")
        pcTransport.secretsReply = com.point.core.flow.SharedSecrets(at = 900)
        val vm = vm(
            account = FakeAccountStore(TEST_ACCOUNT),
            accountClient = FakeCircleClient(listOf(
                com.point.core.flow.CircleDevice("d-pc", com.point.core.flow.DeviceKind.PC, "Ноутбук", key = "ключ-ПК"),
            )),
        )

        vm.openDevices(); advanceUntilIdle()

        assertEquals("ключ перезаписан пустым", null, userKeys.saved)
    }

    @Test fun `свой ключ уезжает на компьютер вместе с меткой`() = runTest(dispatcher) {
        userKeys.stored = com.point.core.flow.UserAiKeys.NONE
            .with(com.point.core.flow.UserAiKey("openrouter", "sk-мой", savedAt = 777))
        pcLinks.pc = com.point.core.flow.LinkedPc("d-pc", "Ноутбук", "ключ-ПК")
        val vm = vm(
            account = FakeAccountStore(TEST_ACCOUNT),
            accountClient = FakeCircleClient(listOf(
                com.point.core.flow.CircleDevice("d-pc", com.point.core.flow.DeviceKind.PC, "Ноутбук", key = "ключ-ПК"),
            )),
        )

        vm.openDevices(); advanceUntilIdle()

        assertEquals("sk-мой", pcTransport.sentSecrets?.aiKey)
        assertEquals(777L, pcTransport.sentSecrets?.at)
    }

    @Test fun `«Удалить аккаунт» уносит и аккаунт, и память об этом устройстве`() = runTest(dispatcher) {

        val client = FakeCircleClient()
        val store = FakeAccountStore(TEST_ACCOUNT)
        pcLinks.pc = com.point.core.flow.LinkedPc("d-pc", "Ноутбук", "ключ-ПК")
        val vm = vm(account = store, accountClient = client)

        vm.openDevices(); advanceUntilIdle()
        vm.deleteAccount(); advanceUntilIdle()

        assertTrue("сервер не получил приказа удалить аккаунт", client.deleted)
        assertNull(store.current())
        assertNull(pcLinks.pc)
        assertTrue(vm.ui.value.signIn is com.point.core.flow.SignIn.SignedOut)
    }

    @Test fun `не удалили — значит не удалили, и молчание сервера не стирает пропуск`() = runTest(dispatcher) {

        val client = FakeCircleClient().apply { deleteFails = true }
        val store = FakeAccountStore(TEST_ACCOUNT)
        val vm = vm(account = store, accountClient = client)

        vm.openDevices(); advanceUntilIdle()
        vm.deleteAccount(); advanceUntilIdle()

        assertNotNull("пропуск стёрт, хотя аккаунт цел", store.current())
        assertNotNull("экран промолчал о неудаче", vm.ui.value.devicesScreen?.error)
        assertNull(vm.ui.value.signIn)
    }

    @Test fun `круг устройств приезжает с сервера, а до него экран говорит о себе правду (#472)`() = runTest(dispatcher) {
        val client = FakeCircleClient(
            circle = listOf(
                com.point.core.flow.CircleDevice("d1", com.point.core.flow.DeviceKind.PHONE, "Pixel", self = true),
                com.point.core.flow.CircleDevice("d2", com.point.core.flow.DeviceKind.PC, "Ноутбук"),
            ),
        )
        val vm = vm(accountClient = client)

        vm.openDevices()

        assertEquals(1, vm.ui.value.devicesScreen?.devices?.size)
        assertEquals(true, vm.ui.value.devicesScreen?.loading)

        advanceUntilIdle()

        assertEquals(listOf("Pixel", "Ноутбук"), vm.ui.value.devicesScreen?.devices?.map { it.name })
        assertEquals(false, vm.ui.value.devicesScreen?.loading)
        vm.closeDevices()
    }

    @Test fun `молчание сервера не стирает того, что устройство знает про себя (#472)`() = runTest(dispatcher) {
        val vm = vm(accountClient = FakeCircleClient(circle = null))

        vm.openDevices(); advanceUntilIdle()

        assertEquals(1, vm.ui.value.devicesScreen?.devices?.size)
        assertTrue(vm.ui.value.devicesScreen?.error != null)
        assertEquals(false, vm.ui.value.devicesScreen?.loading)
        vm.closeDevices()
    }

    @Test fun `круг устройств встаёт поверх настроек, а не вместо них (#544)`() = runTest(dispatcher) {

        val vm = vm(account = FakeAccountStore(TEST_ACCOUNT))

        vm.openKeySettings(); advanceUntilIdle()
        vm.openDevices(); advanceUntilIdle()

        assertNotNull("настройки погасли под кругом устройств", vm.ui.value.keyScreen)
        assertNotNull(vm.ui.value.devicesScreen)
        vm.closeDevices()
    }

    @Test fun `«назад» из круга устройств возвращает в настройки, а из них — на «Недавнее» (#544)`() =
        runTest(dispatcher) {

            val vm = vm(account = FakeAccountStore(TEST_ACCOUNT))
            vm.openKeySettings(); advanceUntilIdle()
            vm.openDevices(); advanceUntilIdle()

            assertTrue(vm.onBack())
            assertNull("круг не закрылся — «назад» ушёл мимо верхнего экрана", vm.ui.value.devicesScreen)
            assertNotNull("настройки закрылись вместе с кругом", vm.ui.value.keyScreen)

            assertTrue(vm.onBack())
            assertNull(vm.ui.value.keyScreen)
        }

    @Test fun `без аккаунта раздел устройств поднимает вход, не теряя настроек (#544)`() = runTest(dispatcher) {

        val vm = vm(account = FakeAccountStore(null))
        vm.openKeySettings(); advanceUntilIdle()

        vm.openDevices(); advanceUntilIdle()

        assertTrue(vm.ui.value.signIn is com.point.core.flow.SignIn.SignedOut)
        assertNotNull(vm.ui.value.keyScreen)

        assertTrue(vm.onBack())
        assertNull(vm.ui.value.signIn)
        assertNotNull("вход увёл человека из настроек вместо возврата в них", vm.ui.value.keyScreen)
    }

    @Test fun `начатый вход спрашивает сервер, чем он кончился (#561)`() = runTest(dispatcher) {
        val client = CountingSignInClient(readyAfter = 3)
        val vm = vm(account = FakeAccountStore(null), accountClient = client)
        vm.openKeySettings(); advanceUntilIdle()
        vm.openDevices(); advanceUntilIdle()

        vm.signIn(); advanceUntilIdle()

        assertEquals("вход начат один раз", 1, client.starts)
        assertTrue("опрос сессии не ушёл ни разу", client.polls > 0)
    }

    @Test fun `удачный вход сохраняет пропуск и закрывает свой экран сам (#561)`() = runTest(dispatcher) {
        val store = FakeAccountStore(null)
        val vm = vm(account = store, accountClient = CountingSignInClient(readyAfter = 2))
        vm.openKeySettings(); advanceUntilIdle()
        vm.openDevices(); advanceUntilIdle()
        assertTrue("дверь входа обязана стоять — иначе нечего закрывать", vm.ui.value.signIn is com.point.core.flow.SignIn.SignedOut)

        vm.signIn(); advanceUntilIdle()

        assertNotNull("пропуск устройства обязан лечь в хранилище", store.current())
        assertNull("экран входа обязан закрыться сам", vm.ui.value.signIn)
        assertNotNull("под дверью — круг устройств, ради которого её открывали", vm.ui.value.devicesScreen)
    }

    @Test fun `вход, начатый до смерти экрана, дожимается вернувшимся человеком (#561)`() = runTest(dispatcher) {
        val store = FakeAccountStore(null)
        val logins = com.point.core.flow.InMemoryPendingLogins()
        val client = CountingSignInClient(readyAfter = Int.MAX_VALUE)
        val first = vm(account = store, accountClient = client, pendingLogins = logins)
        first.openDevices(); advanceUntilIdle()
        first.signIn(); dispatcher.scheduler.advanceTimeBy(5_000)
        first.endFlow(); advanceUntilIdle()
        assertNull(store.current())
        assertNotNull("начатый вход обязан пережить экран", logins.current())

        client.readyNow()
        val returned = vm(account = store, accountClient = client, pendingLogins = logins)
        returned.resumeSignIn(); advanceUntilIdle()

        assertEquals("второго входа человек не начинал", 1, client.starts)
        assertNotNull("вернувшийся человек обязан оказаться вошедшим", store.current())
        assertNull("законченный вход не остаётся лежать на устройстве", logins.current())
        assertNull("своего экрана дожатый вход не поднимает", returned.ui.value.signIn)
    }

    @Test fun `возврат без начатого входа не идёт в сеть (#561)`() = runTest(dispatcher) {
        val client = CountingSignInClient()
        val vm = vm(account = FakeAccountStore(null), accountClient = client)

        vm.resumeSignIn(); advanceUntilIdle()

        assertEquals(0, client.starts)
        assertEquals(0, client.polls)
        assertNull(vm.ui.value.signIn)
    }

    @Test fun `отмена входа гасит опрос и снимает начатый вход (#561)`() = runTest(dispatcher) {
        val logins = com.point.core.flow.InMemoryPendingLogins()
        val client = CountingSignInClient(readyAfter = Int.MAX_VALUE)
        val vm = vm(account = FakeAccountStore(null), accountClient = client, pendingLogins = logins)
        vm.openDevices(); advanceUntilIdle()
        vm.signIn(); dispatcher.scheduler.advanceTimeBy(5_000)
        val pollsWhenCancelled = client.polls
        assertTrue("опрос обязан идти — иначе гасить нечего", pollsWhenCancelled > 0)

        vm.cancelSignIn(); advanceUntilIdle()

        assertEquals("опрос продолжился после отмены", pollsWhenCancelled, client.polls)
        assertNull("снятый вход не дожимается на возврате", logins.current())
        assertTrue(vm.ui.value.signIn is com.point.core.flow.SignIn.SignedOut)

        vm.resumeSignIn(); advanceUntilIdle()
        assertEquals("отменённый вход ожил на возврате", pollsWhenCancelled, client.polls)
    }

    @Test fun `отозванное устройство узнаёт об этом от сервера и показывает вход (#472)`() = runTest(dispatcher) {

        val store = FakeAccountStore(TEST_ACCOUNT)
        val vm = vm(account = store, accountClient = FakeCircleClient(gone = true))

        vm.openDevices(); advanceUntilIdle()

        assertNull(store.current())
        assertEquals(
            com.point.core.flow.ACCOUNT_REVOKED,
            vm.ui.value.signIn,
        )
    }

    @Test fun `отключили само это устройство — дверь входа поднимается тут же (#472)`() = runTest(dispatcher) {

        val store = FakeAccountStore(TEST_ACCOUNT)
        val client = FakeCircleClient()
        val vm = vm(account = store, accountClient = client)
        vm.openDevices(); advanceUntilIdle()

        vm.revokeDevice(TEST_ACCOUNT.deviceId); advanceUntilIdle()

        assertEquals(TEST_ACCOUNT.deviceId, client.revoked)
        assertNull(store.current())
        assertTrue(vm.ui.value.signIn is com.point.core.flow.SignIn.SignedOut)
    }

    @Test fun `чужое устройство отключается, а это остаётся в аккаунте (#472)`() = runTest(dispatcher) {
        val store = FakeAccountStore(TEST_ACCOUNT)
        val client = FakeCircleClient(
            circle = listOf(
                com.point.core.flow.CircleDevice("d1", com.point.core.flow.DeviceKind.PHONE, "Pixel", self = true),
                com.point.core.flow.CircleDevice("d2", com.point.core.flow.DeviceKind.PC, "Ноутбук"),
            ),
        )
        val vm = vm(account = store, accountClient = client)
        vm.openDevices(); advanceUntilIdle()

        vm.revokeDevice("d2"); advanceUntilIdle()

        assertEquals("d2", client.revoked)
        assertNotNull(store.current())
        assertEquals(listOf("Pixel"), vm.ui.value.devicesScreen?.devices?.map { it.name })
        vm.closeDevices()
    }

    @Test fun `убранная запись уходит из «Недавнего», соседняя остаётся (#543)`() = runTest(dispatcher) {
        history.entries += historyEntry("a", "чек.jpg")
        history.entries += historyEntry("b", "смета.pdf")
        val vm = vm()
        vm.loadRecent(); advanceUntilIdle()

        vm.removeFromHistory("a"); advanceUntilIdle()

        assertEquals(listOf("a"), history.removed)
        assertEquals(listOf("b"), vm.recent.value.map { it.id })
    }

    private fun historyEntry(id: String, name: String) = HistoryEntry(
        id = id,
        mime = "image/jpeg",
        kind = ObjectKind.IMAGE,
        name = name,
        epochMillis = 1L,
        ref = ScratchRef("/scratch/$id"),
    )

    @Test fun `a paired Home visit lights the from-PC banner, throttled, and pull opens the flow (#161)`() = runTest(dispatcher) {
        pcLinks.pc = com.point.core.flow.LinkedPc("d-pc", "Ноутбук", "ключ-ПК")
        pcTransport.outbox = listOf(com.point.core.flow.PcOutboxEntry(1, mapOf("name" to "чек.jpg", "mime" to "image/jpeg")))
        val vm = vm()

        vm.loadRecent(); advanceUntilIdle()
        assertEquals(1, vm.fromPcCount.value)
        vm.loadRecent(); advanceUntilIdle()
        assertEquals(1, pcTransport.outboxFetches)

        vm.pullFromPc(); advanceUntilIdle()
        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind)
        assertEquals(listOf(1), pcTransport.acked)
        assertEquals(0, vm.fromPcCount.value)
    }

    @Test fun `pull uses the CURRENT PC outbox, not a stale throttled snapshot (#161)`() = runTest(dispatcher) {
        pcLinks.pc = com.point.core.flow.LinkedPc("d-pc", "Ноутбук", "ключ-ПК")
        pcTransport.outbox = listOf(com.point.core.flow.PcOutboxEntry(1, mapOf("name" to "old.txt", "mime" to "text/plain")))
        val vm = vm()
        vm.loadRecent(); advanceUntilIdle()

        pcTransport.outbox = listOf(
            com.point.core.flow.PcOutboxEntry(1, mapOf("name" to "old.txt", "mime" to "text/plain")),
            com.point.core.flow.PcOutboxEntry(2, mapOf("name" to "new.txt", "mime" to "text/plain")),
        )
        vm.pullFromPc(); advanceUntilIdle()

        assertEquals(listOf(1, 2), pcTransport.acked)
    }

    @Test fun `closing the devices screen refreshes the from-PC banner for Home (#161)`() = runTest(dispatcher) {
        pcLinks.pc = com.point.core.flow.LinkedPc("d-pc", "Ноутбук", "ключ-ПК")
        pcTransport.outbox = listOf(com.point.core.flow.PcOutboxEntry(2, mapOf("name" to "a.txt", "mime" to "text/plain")))
        val vm = vm()

        vm.openDevices(); advanceUntilIdle()
        vm.closeDevices(); advanceUntilIdle()

        assertEquals(1, vm.fromPcCount.value)
    }

    @Test fun `a pulled entry carrying a PC intent runs that action after ingest (#161 v2)`() = runTest(dispatcher) {
        pcLinks.pc = com.point.core.flow.LinkedPc("d-pc", "Ноутбук", "ключ-ПК")
        pcTransport.outbox = listOf(
            com.point.core.flow.PcOutboxEntry(1, mapOf("name" to "т.txt", "mime" to "text/plain", "pc.action" to "call")),
        )
        val vm = vm(caps = mapOf(CapabilityId("call") to setOf(Intent.OPEN)))
        vm.loadRecent(); advanceUntilIdle()

        resolver.lastAmendment = "__unset__"
        vm.pullFromPc(); advanceUntilIdle()

        assertEquals(null, resolver.lastAmendment)
        assertEquals(listOf(1), pcTransport.acked)
    }

    @Test fun `названного компьютером действия на телефоне нет — объект открыт, человек предупреждён`() = runTest(dispatcher) {
        pcLinks.pc = com.point.core.flow.LinkedPc("d-pc", "Ноутбук", "ключ-ПК")
        pcTransport.outbox = listOf(
            com.point.core.flow.PcOutboxEntry(1, mapOf("name" to "т.txt", "mime" to "text/plain", "pc.action" to "видеомонтаж")),
        )
        val vm = vm()
        vm.loadRecent(); advanceUntilIdle()

        vm.pullFromPc(); advanceUntilIdle()

        assertTrue("объект обязан остаться открытым", vm.ui.value.frame != null)
        assertEquals(Outcome.FAILED, vm.ui.value.messageOutcome)
        assertEquals("Компьютер попросил действие, которого в Point нет", vm.ui.value.message)
    }

    @Test fun `компьютер из круга становится известен сам — сопряжения нет вовсе`() = runTest(dispatcher) {

        val vm = vm(
            accountClient = FakeCircleClient(
                circle = listOf(
                    com.point.core.flow.CircleDevice("d1", com.point.core.flow.DeviceKind.PHONE, "Pixel", self = true),
                    com.point.core.flow.CircleDevice(
                        "d2", com.point.core.flow.DeviceKind.PC, "Ноутбук", lastSeenMillis = 5, key = "ключ-ПК",
                    ),
                ),
            ),
        )

        vm.openDevices(); advanceUntilIdle()

        assertEquals("d2", pcLinks.pc?.deviceId)
        assertEquals("ключ-ПК", pcLinks.pc?.key)
        assertEquals(listOf("pc-open"), pcCaps.saved?.map { it.id })
        assertTrue(pcTransport.pushedPhoneCaps.any { it.id == "call" })
        vm.closeDevices()
    }

    @Test fun `компьютер ушёл из круга — память о нём уходит вместе с ним`() = runTest(dispatcher) {

        pcLinks.pc = com.point.core.flow.LinkedPc("d2", "Ноутбук", "ключ-ПК")
        val vm = vm(
            accountClient = FakeCircleClient(
                circle = listOf(
                    com.point.core.flow.CircleDevice("d1", com.point.core.flow.DeviceKind.PHONE, "Pixel", self = true),
                ),
            ),
        )

        vm.openDevices(); advanceUntilIdle()

        assertNull(pcLinks.pc)
        assertTrue(pcCaps.cleared)
        assertNull("это не отказ, а факт круга", vm.ui.value.devicesScreen?.error)
        vm.closeDevices()
    }

    @Test fun `круг не приехал — то, что телефон знает про компьютер, не стирается`() = runTest(dispatcher) {

        pcLinks.pc = com.point.core.flow.LinkedPc("d2", "Ноутбук", "ключ-ПК")
        val vm = vm(accountClient = FakeCircleClient(circle = null))

        vm.openDevices(); advanceUntilIdle()

        assertEquals("d2", pcLinks.pc?.deviceId)
        vm.closeDevices()
    }

    @Test fun `a failed download keeps the entries un-acked (#161)`() = runTest(dispatcher) {
        pcLinks.pc = com.point.core.flow.LinkedPc("d-pc", "Ноутбук", "ключ-ПК")
        pcTransport.outbox = listOf(com.point.core.flow.PcOutboxEntry(1, mapOf("name" to "a.txt", "mime" to "text/plain")))
        pcTransport.downloadOk = false
        val vm = vm()
        vm.loadRecent(); advanceUntilIdle()

        vm.pullFromPc(); advanceUntilIdle()

        assertTrue(pcTransport.acked.isEmpty())
        assertEquals(1, vm.fromPcCount.value)
    }

    @Test fun `opening the devices screen refreshes the cached remote actions (#80 v2)`() = runTest(dispatcher) {
        val vm = vm()
        pcLinks.pc = com.point.core.flow.LinkedPc("d-pc", "Ноутбук", "ключ-ПК")

        vm.openDevices(); advanceUntilIdle()

        assertEquals(listOf("pc-open"), pcCaps.saved?.map { it.id })
        vm.closeDevices()
    }

    @Test fun `обрезанный набор доносит до экрана настоящее число файлов`() = runTest(dispatcher) {

        store.content = CollectionContent(
            shown = (1..2).map { PointObject("f$it", "text/plain", ScratchRef("/f$it"), ObjectState(ObjectKind.TEXT)) },
            total = 1340,
        )
        val vm = vm()

        vm.onSharedMultiple(listOf("a", "b")); advanceUntilIdle()

        assertEquals(2, vm.ui.value.frame?.items?.size)
        assertEquals(1340, vm.ui.value.frame?.itemsTotal)
        assertEquals(false, vm.ui.value.frame?.itemsTotalAtLeast)
    }

    @Test fun `onItem drills into a collection item as a new frame`() = runTest(dispatcher) {
        val vm = vm()
        vm.onSharedMultiple(listOf("a", "b")); advanceUntilIdle()
        assertEquals(ObjectKind.COLLECTION, vm.ui.value.frame?.obj?.state?.kind)

        vm.onItem(PointObject("item", "image/png", ScratchRef("/i"), ObjectState(ObjectKind.IMAGE)))
        advanceUntilIdle()

        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind)
    }

    @Test fun `objects found by enrichment land on the frame`() = runTest(dispatcher) {
        val vm = vm()
        val waybill = PointObject(
            "o:id", "text/plain", ValueRef("20 4514 9154 9395"),
            ObjectState(ObjectKind.of("Identifier")),
        )
        enrichment.updates = listOf(
            EnrichmentUpdate(emptySet(), emptyMap(), emptyList(), listOf(waybill), emptyList()),
        )

        vm.onShared("/parcel.jpg", "image/jpeg"); advanceUntilIdle()

        assertEquals(listOf("20 4514 9154 9395"), vm.ui.value.frame?.found?.map { it.uri.value })
    }

    @Test fun `the same object arriving twice stays one node`() = runTest(dispatcher) {

        val vm = vm()
        val addr = PointObject(
            "o:address", "text/plain", ValueRef("Київ"),
            ObjectState(ObjectKind.of("Address"), setOf(Feature.HAS_ADDRESS)),
        )
        enrichment.updates = listOf(
            EnrichmentUpdate(emptySet(), emptyMap(), emptyList(), listOf(addr), emptyList()),
            EnrichmentUpdate(emptySet(), emptyMap(), emptyList(), listOf(addr, addr), emptyList()),
        )

        vm.onShared("/parcel.jpg", "image/jpeg"); advanceUntilIdle()

        assertEquals(1, vm.ui.value.frame?.found?.size)
    }

    @Test fun `tapping a found object opens it as a frame of its own`() = runTest(dispatcher) {
        val vm = vm()
        val addr = PointObject(
            "o:address", "text/plain", ValueRef("Відділення №9"),
            ObjectState(ObjectKind.of("Address"), setOf(Feature.HAS_ADDRESS)),
        )
        enrichment.updates = listOf(
            EnrichmentUpdate(emptySet(), emptyMap(), emptyList(), listOf(addr), emptyList()),
        )
        vm.onShared("/parcel.jpg", "image/jpeg"); advanceUntilIdle()

        vm.onFound(addr); advanceUntilIdle()

        assertEquals("Відділення №9", vm.ui.value.frame?.obj?.uri?.value)
        assertEquals(ObjectKind.of("Address"), vm.ui.value.frame?.obj?.state?.kind)
    }

    @Test fun `an object the frame never found is not an open door`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("/parcel.jpg", "image/jpeg"); advanceUntilIdle()
        val before = vm.ui.value.frame?.obj?.id

        vm.onFound(PointObject("stranger", "text/plain", ValueRef("x"), ObjectState(ObjectKind.of("Address"))))
        advanceUntilIdle()

        assertEquals(before, vm.ui.value.frame?.obj?.id)
    }

    @Test fun `a fact the object already knows is not overwritten by a later reading`() = runTest(dispatcher) {

        val vm = vm()
        enrichment.updates = listOf(
            EnrichmentUpdate(emptySet(), mapOf("entity.address" to "первое"), emptyList()),
            EnrichmentUpdate(emptySet(), mapOf("entity.address" to "второе"), emptyList()),
        )

        vm.onShared("/x.jpg", "image/jpeg"); advanceUntilIdle()

        assertEquals("первое", vm.ui.value.frame?.obj?.metadata?.get("entity.address"))
    }

    @Test fun `the pointer to recognised text still refreshes`() = runTest(dispatcher) {

        val vm = vm()
        enrichment.updates = listOf(
            EnrichmentUpdate(emptySet(), mapOf(com.point.core.flow.META_OCR_TEXT_REF to "/scratch/a.txt"), emptyList()),
            EnrichmentUpdate(emptySet(), mapOf(com.point.core.flow.META_OCR_TEXT_REF to "/scratch/b.txt"), emptyList()),
        )

        vm.onShared("/x.jpg", "image/jpeg"); advanceUntilIdle()

        assertEquals("/scratch/b.txt", vm.ui.value.frame?.obj?.metadata?.get(com.point.core.flow.META_OCR_TEXT_REF))
    }

    private fun cardWithQr(
        objects: List<PointObject> = emptyList(),
        relations: List<com.point.core.model.Relation> = emptyList(),
    ) = EnrichmentUpdate(
        setOf(Feature.HAS_QR),
        mapOf("entity.qr" to "https://example.org/vcard/17"),
        emptyList(),
        objects,
        relations,
    )

    private fun understood(vararg facts: Pair<String, String>) = ActionResult.Success(
        ResultObject(
            ObjectKind.IMAGE, "image/jpeg", ScratchRef("/in"),
            mapOf(*facts) + ("op" to "understand"),
        ),
    )

    @Test fun `локально найденный QR остаётся при объекте после облачного «Понять»`() = runTest(dispatcher) {

        enrichment.updates = listOf(cardWithQr())
        enrichment.understandsOnce = true
        resolver.result = understood("entity.phone" to "+380671234567")
        val vm = vm()
        vm.onShared("/card.jpg", "image/jpeg"); advanceUntilIdle()
        assertTrue(vm.ui.value.frame?.obj?.state?.has(Feature.HAS_QR) == true)

        vm.onBubble(bubble(title = "Понять")); advanceUntilIdle()

        val obj = vm.ui.value.frame?.obj
        assertTrue("QR обязан пережить шаг", obj?.state?.has(Feature.HAS_QR) == true)
        assertEquals("https://example.org/vcard/17", obj?.metadata?.get("entity.qr"))

        assertEquals("+380671234567", obj?.metadata?.get("entity.phone"))
    }

    @Test fun `найденное внутри объекта переживает шаг вместе со связями`() = runTest(dispatcher) {
        val waybill = PointObject(
            "o:id", "text/plain", ValueRef("20 4514 9154 9395"),
            ObjectState(ObjectKind.of("Identifier")),
        )
        val foundIn = com.point.core.model.Relation(
            "o:id", com.point.core.model.RelationType.FOUND_IN, "in",
        )
        enrichment.updates = listOf(cardWithQr(listOf(waybill), listOf(foundIn)))
        enrichment.understandsOnce = true
        resolver.result = understood("entity.phone" to "+380671234567")
        val vm = vm()
        vm.onShared("/parcel.jpg", "image/jpeg"); advanceUntilIdle()

        vm.onBubble(bubble(title = "Понять")); advanceUntilIdle()

        assertEquals(listOf("20 4514 9154 9395"), vm.ui.value.frame?.found?.map { it.uri.value })
        assertEquals(listOf(foundIn), vm.ui.value.frame?.relations)

        vm.onFound(waybill); advanceUntilIdle()
        assertEquals("20 4514 9154 9395", vm.ui.value.frame?.obj?.uri?.value)
    }

    @Test fun `понятое шагом доезжает до «Недавнего» — объект остаётся собой`() = runTest(dispatcher) {
        enrichment.updates = listOf(cardWithQr())
        enrichment.understandsOnce = true
        resolver.result = understood("entity.phone" to "+380671234567")
        val vm = vm()
        vm.onShared("/card.jpg", "image/jpeg"); advanceUntilIdle()

        vm.onBubble(bubble(title = "Понять")); advanceUntilIdle()

        assertEquals(history.recorded.single().id, history.updated.last().id)
        assertEquals("+380671234567", history.updated.last().metadata["entity.phone"])
        assertTrue(history.updated.last().state.has(Feature.HAS_QR))
    }

    @Test fun `спор двух чтений виден и после шага`() = runTest(dispatcher) {

        enrichment.updates = listOf(
            EnrichmentUpdate(
                emptySet(),
                mapOf(
                    "entity.address" to "вул. Сонячна, 15",
                    "entity.address" + com.point.core.flow.META_ALT_SUFFIX to
                        com.point.core.flow.altValue(listOf("вул. Сонячна, 15", "вул. Сонячна, 51")),
                ),
                emptyList(),
            ),
        )
        enrichment.understandsOnce = true
        resolver.result = understood("entity.phone" to "+380671234567")
        val vm = vm()
        vm.onShared("/card.jpg", "image/jpeg"); advanceUntilIdle()

        vm.onBubble(bubble(title = "Понять")); advanceUntilIdle()

        assertEquals("вул. Сонячна, 15", vm.ui.value.frame?.obj?.metadata?.get("entity.address"))
        assertEquals(
            listOf("вул. Сонячна, 15", "вул. Сонячна, 51"),
            com.point.core.flow.alternativesOf(vm.ui.value.frame?.obj?.metadata.orEmpty(), "entity.address"),
        )
    }

    @Test fun `новый объект чужого понятого не наследует`() = runTest(dispatcher) {

        enrichment.updates = listOf(cardWithQr())
        enrichment.understandsOnce = true
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/out.txt")))
        val vm = vm()
        vm.onShared("/card.jpg", "image/jpeg"); advanceUntilIdle()

        vm.onBubble(bubble(title = "Распознать текст")); advanceUntilIdle()

        assertFalse(vm.ui.value.frame?.obj?.state?.has(Feature.HAS_QR) == true)
        assertNull(vm.ui.value.frame?.obj?.metadata?.get("entity.qr"))
    }

    @Test fun `«нового нет» — не отказ, и объект остаётся со всем, что о нём знали`() = runTest(dispatcher) {
        enrichment.updates = listOf(cardWithQr())
        enrichment.understandsOnce = true
        resolver.result = ActionResult.Done("Point уже прочитал всё, что здесь есть")
        val vm = vm()
        vm.onShared("/card.jpg", "image/jpeg"); advanceUntilIdle()

        vm.onBubble(bubble(title = "Понять")); advanceUntilIdle()

        assertEquals("Point уже прочитал всё, что здесь есть", vm.ui.value.message)
        assertEquals(Outcome.DONE, vm.ui.value.messageOutcome)
        assertTrue(vm.ui.value.frame?.obj?.state?.has(Feature.HAS_QR) == true)
    }

    @Test fun `экран ключей открывается всеми известными сервисами списком`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()

        val screen = vm.ui.value.keyScreen
        assertNotNull(screen)
        assertEquals(AI_PROVIDERS.map { it.id }, screen?.services?.map { it.providerId })
        assertEquals("Ещё не проверяли", screen?.checkedLine)
    }

    @Test fun `в строке сервиса стоит и то, что он умеет, и последний факт о нём`() = runTest(dispatcher) {
        val groq = AI_PROVIDERS.first { it.id == "groq" }
        aiFacts.facts["groq"] = com.point.core.flow.AiFact(com.point.core.flow.AiOutcome.LIMIT, 1L)
        builtInKeys.ours = mapOf("groq" to "встроенный")
        val vm = vm()

        vm.openKeySettings(); advanceUntilIdle()

        val line = vm.ui.value.keyScreen?.services?.single { it.providerId == "groq" }
        assertEquals(groq.what, line?.what)
        assertEquals("работает на ключе Point", line?.keyLine)
        assertTrue("последний факт пропал из строки", line?.factLine?.startsWith("лимит исчерпан") == true)
    }

    @Test fun `«Проверить все» спрашивает каждый сервис с ключом ровно один раз`() = runTest(dispatcher) {
        builtInKeys.ours = mapOf("groq" to "встроенный", "cerebras" to "встроенный")
        userKeys.stored = com.point.core.flow.UserAiKeys.NONE
            .with(com.point.core.flow.UserAiKey("openrouter", "sk-мой"))
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()

        vm.checkAllAiKeys(); advanceUntilIdle()

        assertEquals(setOf("openrouter", "groq", "cerebras"), aiFacts.facts.keys)
        assertNull("кнопка осталась бы в «Проверяю…»", vm.ui.value.keyChecking)
        assertTrue(
            "возраст сведений не обновился",
            vm.ui.value.keyScreen?.checkedLine?.startsWith("Проверено") == true,
        )
    }

    @Test fun `сервис без единого ключа проверять нечем — сеть туда не идёт`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()

        vm.checkAllAiKeys(); advanceUntilIdle()

        assertTrue("проверка ушла туда, где нет ключа", aiFacts.facts.isEmpty())
        assertNull(keyCheck.asked)
    }

    @Test fun `отказ «нет ключа» остаётся сказанным, а не подменяется экраном настроек`() = runTest(dispatcher) {
        resolver.result = ActionResult.Failure("AI недоступен — задайте свой ключ", recoverable = true)
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals("AI недоступен — задайте свой ключ", vm.ui.value.message)
        assertEquals(Outcome.FAILED, vm.ui.value.messageOutcome)
        assertNull("экран ключей больше не открывается сам за человека", vm.ui.value.keyScreen)
        assertEquals("Задать свой ключ AI", keyOfferLabel(vm.ui.value.message))
    }

    @Test fun `обычный отказ ключа не предлагает`() = runTest(dispatcher) {
        resolver.result = ActionResult.Failure("Не удалось прочитать страницу", recoverable = true)
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertNull(keyOfferLabel(vm.ui.value.message))
    }

    @Test fun `отказ переживает поход за ключом и «Отмену»`() = runTest(dispatcher) {
        resolver.result = ActionResult.Failure("AI недоступен — задайте свой ключ", recoverable = true)
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()

        vm.openKeySettings(); advanceUntilIdle()
        assertEquals("AI недоступен — задайте свой ключ", vm.ui.value.message)

        vm.closeKeySettings()

        assertEquals("AI недоступен — задайте свой ключ", vm.ui.value.message)
        assertEquals(Outcome.FAILED, vm.ui.value.messageOutcome)
    }

    @Test fun `постороннее сообщение экран ключей всё так же стирает`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("Готово")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()
        assertEquals("Готово", vm.ui.value.message)

        vm.openKeySettings(); advanceUntilIdle()

        assertNull(vm.ui.value.message)
        assertEquals(Outcome.NONE, vm.ui.value.messageOutcome)
    }

    @Test fun `отказ расшифровки тоже получает предложение задать ключ`() = runTest(dispatcher) {
        val why = "Расшифровать некому: Whisper слушает по ключу Groq. " +
            com.point.core.flow.KEY_SETTINGS_CALL
        resolver.result = ActionResult.Failure(why, recoverable = true)
        val vm = vm()
        vm.onShared("voice.ogg", "audio/ogg"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals(why, vm.ui.value.message)
        assertNull("экран ключей открывает человек, а не отказ за него", vm.ui.value.keyScreen)
        assertEquals("Задать свой ключ AI", keyOfferLabel(vm.ui.value.message))
    }

    @Test fun `причина доезжает до экрана ключей вместе с человеком`() = runTest(dispatcher) {
        val why = "Расшифровать некому: Whisper слушает по ключу Groq. " +
            com.point.core.flow.KEY_SETTINGS_CALL
        resolver.result = ActionResult.Failure(why, recoverable = true)
        val vm = vm()
        vm.onShared("voice.ogg", "audio/ogg"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()

        vm.openKeySettings(); advanceUntilIdle()

        assertEquals(why, vm.ui.value.keyScreenNote)
    }

    @Test fun `пришедший сам не видит на экране ключей чужой причины`() = runTest(dispatcher) {
        val vm = vm()

        vm.openKeySettings(); advanceUntilIdle()

        assertNull(vm.ui.value.keyScreenNote)
    }

    @Test fun `сохранённый ключ ложится к своему сервису, а экран остаётся на месте`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()

        vm.saveAiKey(com.point.core.flow.UserAiKey("groq", "gsk-1")); advanceUntilIdle()

        assertEquals("gsk-1", userKeys.saved?.apiKey)
        assertEquals("groq", userKeys.saved?.providerId)
        assertEquals("gsk-1", vm.ui.value.keyScreen?.keys?.keyFor("groq"))
    }

    @Test fun `«Забыть ключ» стирает его с устройства и возвращает приглашение подключить AI`() = runTest(dispatcher) {
        userKeys.stored = com.point.core.flow.UserAiKeys.NONE
            .with(com.point.core.flow.UserAiKey("openrouter", "sk-1"))
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        assertTrue("ключ был задан — иначе забывать нечего", vm.ui.value.aiKeySet)

        vm.forgetAiKey("openrouter"); advanceUntilIdle()

        assertEquals("", userKeys.stored.keyFor("openrouter"))
        assertFalse("«Недавнее» обязано снова звать подключить AI", vm.ui.value.aiKeySet)
    }

    @Test fun `забытый ключ не уносит с собой экран и остальные сервисы`() = runTest(dispatcher) {
        val openRouter = AI_PROVIDERS.first()
        userKeys.stored = com.point.core.flow.UserAiKeys.NONE
            .with(com.point.core.flow.UserAiKey(openRouter.id, "sk-1"))
            .with(com.point.core.flow.UserAiKey("groq", "gsk-1"))
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        vm.checkAiKey(com.point.core.flow.UserAiKey(openRouter.id, "sk-1")); advanceUntilIdle()
        assertNotNull("проверка сказала «работает»", vm.ui.value.keyVerdict)

        vm.forgetAiKey(openRouter.id); advanceUntilIdle()

        val screen = vm.ui.value.keyScreen
        assertNotNull("человек остался бы гадать, случилось ли что-нибудь", screen)
        assertEquals("ключа на экране больше нет", "", screen?.keys?.keyFor(openRouter.id))

        assertEquals("чужой ключ ушёл заодно", "gsk-1", screen?.keys?.keyFor("groq"))

        assertNull(vm.ui.value.keyVerdict)
    }

    @Test fun `удачная проверка сохраняет ключ и показывает слова сервиса`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        val key = com.point.core.flow.UserAiKey("groq", "gsk-1")
        keyCheck.probe = com.point.core.flow.KeyProbe(status = 200, reply = "Готово")

        vm.checkAiKey(key); advanceUntilIdle()

        assertEquals("проверять надо ровно то, что человек набрал", "gsk-1", keyCheck.asked?.apiKey)
        assertEquals(com.point.core.flow.KeyVerdict.Works("Готово"), vm.ui.value.keyVerdict)
        assertEquals("приговор потерял, к чьей строке он относится", "groq", vm.ui.value.keyVerdictFor)
        assertEquals("доказанный ключ обязан сохраниться сам", "gsk-1", userKeys.saved?.apiKey)
        assertEquals(
            "исход проверки не запомнился за сервисом",
            com.point.core.flow.AiOutcome.ANSWERED,
            aiFacts.facts["groq"]?.outcome,
        )

        assertNotNull(vm.ui.value.keyScreen)
        assertTrue(vm.ui.value.aiKeySet)
    }

    @Test fun `непрошедший проверку ключ не сохраняется`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        keyCheck.probe = com.point.core.flow.KeyProbe(status = 401, error = "unauthorized")

        vm.checkAiKey(com.point.core.flow.UserAiKey("groq", "не-тот")); advanceUntilIdle()

        assertNull("отказавший ключ не имеет права осесть на диске", userKeys.saved)
        assertEquals(
            "«ключ не подошёл» обязан пережить перезапуск",
            com.point.core.flow.AiOutcome.BAD_KEY,
            aiFacts.facts["groq"]?.outcome,
        )
        val verdict = vm.ui.value.keyVerdict as com.point.core.flow.KeyVerdict.Refused
        assertTrue(verdict.what.contains("не подошёл"))
        assertNotNull("с отказом человек остаётся на экране, где стоит его ключ", vm.ui.value.keyScreen)
    }

    @Test fun `упавшая проверка — это отказ, а не тишина`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        keyCheck.explode = true

        vm.checkAiKey(com.point.core.flow.UserAiKey("groq", "gsk-1")); advanceUntilIdle()

        assertNull("кнопка осталась бы в «Проверяю…» навсегда", vm.ui.value.keyChecking)
        assertTrue(vm.ui.value.keyVerdict is com.point.core.flow.KeyVerdict.Refused)
    }

    @Test fun `приговор не переживает закрытие экрана`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        vm.checkAiKey(com.point.core.flow.UserAiKey("groq", "gsk-1")); advanceUntilIdle()
        assertNotNull(vm.ui.value.keyVerdict)

        vm.closeKeySettings()
        vm.openKeySettings(); advanceUntilIdle()

        assertNull(vm.ui.value.keyVerdict)
        assertNull(vm.ui.value.keyChecking)
    }

    @Test fun `пустой ключ не гоняет сеть`() = runTest(dispatcher) {
        val vm = vm()
        vm.checkAiKey(com.point.core.flow.UserAiKey("groq", "   ")); advanceUntilIdle()

        assertNull("сеть не имеет права уйти без ключа", keyCheck.asked)
        assertNull(vm.ui.value.keyVerdict)
    }

    private val needsKey = CapabilityId("a")

    private fun keyErrandVm() = vm(
        caps = mapOf(needsKey to setOf(Intent.UNDERSTAND)),
        keyNeeding = setOf(needsKey),
    )

    private fun FlowViewModel.needsKeyBubble(): Bubble =
        ui.value.frame!!.bubbles.single { com.point.core.flow.labelNeedsKey(it.title) }

    /**
     * Пропажа сети видна на открытом экране (#758).
     *
     * Причина «нет интернета» появлялась только при следующем открытии объекта: список
     * действий собирается один раз, а состояние мира спрашивается в момент сборки. Здесь
     * под открытым экраном меняется то, чем действие может работать, — и подписи обязаны
     * догнать это без перезахода. Ключ вместо сети взят потому, что оба живут одинаково:
     * список пересобирается тем же ходом.
     */
    @Test fun `смена мира под открытым экраном пересобирает действия`() = runTest(dispatcher) {
        val vm = keyErrandVm()
        vm.onShared("card.jpg", "image/jpeg"); advanceUntilIdle()
        assertTrue("до правки нечего проверять", vm.ui.value.frame!!.bubbles.any {
            com.point.core.flow.labelNeedsKey(it.title)
        })

        userKeys.stored = com.point.core.flow.UserAiKeys(
            listOf(com.point.core.flow.UserAiKey(providerId = "openai", apiKey = "sk-появился")),
        )
        vm.networkChanged(); advanceUntilIdle()

        assertTrue(
            "экран остался с прежним обещанием до перезахода",
            vm.ui.value.frame!!.bubbles.none { com.point.core.flow.labelNeedsKey(it.title) },
        )
    }

    @Test fun `пересборка без изменений не трогает экран`() = runTest(dispatcher) {
        val vm = keyErrandVm()
        vm.onShared("card.jpg", "image/jpeg"); advanceUntilIdle()
        val before = vm.ui.value.frame

        vm.networkChanged(); advanceUntilIdle()

        assertSame("кадр пересоздан впустую", before, vm.ui.value.frame)
    }

    @Test fun `тап по действию с «нужен ключ» ведёт за ключом, а не в реализатор`() = runTest(dispatcher) {
        val vm = keyErrandVm()
        vm.onShared("card.jpg", "image/jpeg"); advanceUntilIdle()

        vm.onBubble(vm.needsKeyBubble()); advanceUntilIdle()

        assertNotNull("человек остался перед действием, которому нечем работать", vm.ui.value.keyScreen)

        assertTrue("действие ушло в реализатор впустую", resolver.performed.isEmpty())
    }

    @Test fun `экран ключа называет и действие, и объект, к которому вернуться`() = runTest(dispatcher) {
        val vm = keyErrandVm()
        vm.onShared("card.jpg", "image/jpeg", name = "чек.jpg"); advanceUntilIdle()

        vm.onBubble(vm.needsKeyBubble()); advanceUntilIdle()

        val errand = vm.ui.value.keyErrand
        assertNotNull("без поручения экран снова безымянный", errand)

        assertEquals("Action a", errand?.action)
        assertEquals("чек.jpg", errand?.objectName)
    }

    @Test fun `безымянный объект зовётся видом, а не пустой строкой`() = runTest(dispatcher) {
        val vm = keyErrandVm()
        vm.onShared("card.jpg", "image/jpeg"); advanceUntilIdle()

        vm.onBubble(vm.needsKeyBubble()); advanceUntilIdle()

        val name = vm.ui.value.keyErrand?.objectName
        assertTrue("объект назван не своим видом: " + name, name?.startsWith("Изображение") == true)
    }

    @Test fun `после удачной проверки человек возвращается к объекту, и действие ждёт его тапа`() = runTest(dispatcher) {
        val vm = keyErrandVm()
        vm.onShared("card.jpg", "image/jpeg", name = "чек.jpg"); advanceUntilIdle()
        vm.onBubble(vm.needsKeyBubble()); advanceUntilIdle()

        keyCheck.probe = com.point.core.flow.KeyProbe(status = 200, reply = "Готово")
        vm.checkAiKey(com.point.core.flow.UserAiKey(AI_PROVIDERS.first().id, "sk-1")); advanceUntilIdle()
        assertEquals(com.point.core.flow.KeyVerdict.Works("Готово"), vm.ui.value.keyVerdict)

        vm.closeKeySettings()

        assertNull("экран ключей остался поверх объекта", vm.ui.value.keyScreen)
        val frame = vm.ui.value.frame
        assertNotNull("человек вернулся в пустоту, а не к своему объекту", frame)
        assertEquals("чек.jpg", frame?.obj?.metadata?.get("name"))

        assertEquals("Action a", frame?.bubbles?.single()?.title)
    }

    @Test fun `прерванное действие не выполняется само после починки ключа`() = runTest(dispatcher) {
        val vm = keyErrandVm()
        vm.onShared("card.jpg", "image/jpeg"); advanceUntilIdle()
        vm.onBubble(vm.needsKeyBubble()); advanceUntilIdle()

        vm.checkAiKey(com.point.core.flow.UserAiKey(AI_PROVIDERS.first().id, "sk-1")); advanceUntilIdle()
        vm.closeKeySettings(); advanceUntilIdle()

        assertTrue("Point сделал выбор за человека", resolver.performed.isEmpty())
        assertNull("и даже не начал", vm.ui.value.busy)
    }

    @Test fun `пришедший дверью «AI-ключ» приходит без поручения`() = runTest(dispatcher) {
        val vm = vm()

        vm.openKeySettings(); advanceUntilIdle()

        assertNull(vm.ui.value.keyErrand)
    }

    @Test fun `поручение уходит вместе с экраном`() = runTest(dispatcher) {
        val vm = keyErrandVm()
        vm.onShared("card.jpg", "image/jpeg"); advanceUntilIdle()
        vm.onBubble(vm.needsKeyBubble()); advanceUntilIdle()
        assertNotNull(vm.ui.value.keyErrand)

        vm.closeKeySettings()

        assertNull(vm.ui.value.keyErrand)
    }

    @Test fun `«Недавнее» знает, задан ли ключ`() = runTest(dispatcher) {
        val vm = vm()
        vm.loadRecent(); advanceUntilIdle()
        assertFalse("приглашение подключить AI должно быть видно", vm.ui.value.aiKeySet)

        userKeys.stored = com.point.core.flow.UserAiKeys.NONE
            .with(com.point.core.flow.UserAiKey("openrouter", "sk-1"))
        vm.loadRecent(); advanceUntilIdle()
        assertTrue("ключ есть — звать больше некуда", vm.ui.value.aiKeySet)
    }

    private fun cloudVm() = vm(
        caps = mapOf(
            CapabilityId("ai") to setOf(Intent.UNDERSTAND),
            CapabilityId("cloudx") to setOf(Intent.UNDERSTAND),
        ),
        cloud = setOf(CapabilityId("ai"), CapabilityId("cloudx")),
    )

    @Test fun `a cloud action asks for consent before anything leaves the device`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "cloudx")); advanceUntilIdle()

        assertTrue(vm.ui.value.cloudConsent)
        assertNull(vm.ui.value.message)
        assertEquals("__unset__", resolver.lastAmendment)
    }

    @Test fun `confirming consent runs the pending cloud action and persists the grant`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "cloudx")); advanceUntilIdle()

        vm.confirmCloud(); advanceUntilIdle()

        assertEquals("готово", vm.ui.value.message)
        assertEquals(false, vm.ui.value.cloudConsent)
        assertTrue(consent.granted)
    }

    @Test fun `declining consent cancels the cloud action and sends nothing`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "cloudx")); advanceUntilIdle()

        vm.declineCloud()

        assertEquals(FlowViewModel.CLOUD_DECLINED, vm.ui.value.message)
        assertEquals(Outcome.NONE, vm.ui.value.messageOutcome)
        assertEquals("__unset__", resolver.lastAmendment)
        assertEquals(false, vm.ui.value.cloudConsent)
        assertEquals(false, consent.granted)
    }

    @Test fun `отказ от отправки говорит и что случилось, и что дальше`() {

        val said = FlowViewModel.CLOUD_DECLINED

        assertTrue(said, "Ничего не отправлено" in said)
        assertTrue(said, "объект остался на телефоне" in said)
        assertTrue("нет выхода — человеку некуда деться", "тапните ещё раз" in said)
    }

    @Test fun `an already-granted consent lets a cloud action run without asking`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        consent.granted = true
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "cloudx")); advanceUntilIdle()

        assertEquals("готово", vm.ui.value.message)
        assertEquals(false, vm.ui.value.cloudConsent)
    }

    @Test fun `местная способность с облачным запасным всё равно спрашивает согласие`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        resolver.leavesDevice = true
        val vm = vm(caps = mapOf(CapabilityId("ocr") to setOf(Intent.UNDERSTAND)))
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "ocr")); advanceUntilIdle()

        assertTrue("согласие обязано спрашиваться по факту, а не по объявлению", vm.ui.value.cloudConsent)
        assertNull(vm.ui.value.message)
        assertEquals("__unset__", resolver.lastAmendment)
    }

    @Test fun `полностью местная цепочка не спрашивает ничего`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        resolver.leavesDevice = false
        val vm = vm(caps = mapOf(CapabilityId("ocr") to setOf(Intent.UNDERSTAND)))
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "ocr")); advanceUntilIdle()

        assertEquals(false, vm.ui.value.cloudConsent)
        assertEquals("готово", vm.ui.value.message)
    }

    @Test fun `tapping AI opens the multi-turn chat, not a one-shot action (#4)`() = runTest(dispatcher) {
        consent.granted = true
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()

        assertTrue(vm.ui.value.chat != null)
        assertTrue(vm.ui.value.chatOpen)
        assertEquals("__unset__", resolver.lastAmendment)
    }

    private fun kotlinx.coroutines.test.TestScope.chattingVm(): FlowViewModel {
        consent.granted = true
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()
        vm.sendChatMessage("что тут написано?"); advanceUntilIdle()
        return vm
    }

    // Прежде «назад» лишь сворачивало разговор и сказанное оставалось. Решение владельца
    // 11.08.2026 (#794): «назад -> ai = заново» — уход человека завершает разговор.
    @Test fun `«назад» из разговора завершает разговор`() = runTest(dispatcher) {
        val vm = chattingVm()
        assertEquals(2, vm.ui.value.chat?.messages?.size)

        assertTrue(vm.onBack())

        assertNull("экрана разговора нет", openChatOf(vm.ui.value))
        assertNull("разговор завершён", vm.ui.value.chat)
    }

    // Решение владельца 11.08.2026 (#794), дословно: «назад -> ai = заново». Вместе с прежней
    // перепиской возвращался пустой экран без вариантов вопросов — единственного места, где
    // сказано, о чём вообще можно спросить объект.
    @Test fun `после «назад» разговор начинается заново`() = runTest(dispatcher) {
        val vm = chattingVm()
        vm.onBack()

        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()

        assertEquals(0, openChatOf(vm.ui.value)?.messages?.size)
        assertTrue("варианты вопросов вернулись", openChatOf(vm.ui.value)?.suggestions?.isNotEmpty() == true)
    }

    @Test fun `новый объект начинает разговор заново`() = runTest(dispatcher) {
        val vm = chattingVm()
        vm.onBack()
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))
        vm.onBubble(bubble(id = "cloudx")); advanceUntilIdle()

        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()

        assertEquals(0, openChatOf(vm.ui.value)?.messages?.size)
    }

    @Test fun `идущий вопрос можно остановить, и остановка сказана словами`() = runTest(dispatcher) {
        consent.granted = true
        chatResponder.inFlight = kotlinx.coroutines.CompletableDeferred()
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()
        vm.sendChatMessage("что тут написано?"); advanceUntilIdle()
        assertTrue("вопрос в пути", vm.ui.value.chat?.pending == true)

        vm.cancelChatMessage(); advanceUntilIdle()

        assertEquals(false, vm.ui.value.chat?.pending)
        assertEquals("Ответ остановлен", vm.ui.value.chat?.notice)

        assertEquals(1, vm.ui.value.chat?.messages?.size)
    }

    // Уход из разговора — отказ от работы, а не согласие ждать её в пустоте (#668): вопрос
    // отменяется, и облачный вызов не оплачивается после того, как человек ушёл. Вместе с
    // решением «назад -> ai = заново» (#794) это значит, что ответу некуда и незачем
    // возвращаться.
    @Test fun `уход из разговора отменяет заданный вопрос`() = runTest(dispatcher) {
        consent.granted = true
        val late = kotlinx.coroutines.CompletableDeferred<String>()
        chatResponder.inFlight = late
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()
        vm.sendChatMessage("что тут написано?"); advanceUntilIdle()
        vm.onBack()

        late.complete("ответ издалека"); advanceUntilIdle()

        assertNull(openChatOf(vm.ui.value))
        assertNull("разговор завершён, ответу некуда лечь", vm.ui.value.chat)
    }

    @Test fun `забрать ответ — и разговор кончился объектом`() = runTest(dispatcher) {

        val vm = chattingVm()

        vm.takeChatAnswer(); advanceUntilIdle()

        assertNull("экран разговора закрылся", openChatOf(vm.ui.value))
        val frame = vm.ui.value.frame!!
        assertEquals(ObjectKind.TEXT, frame.obj.state.kind)
        assertEquals("ответ", java.io.File(frame.obj.uri.value).readText())

        assertEquals(com.point.core.model.Provenance.MODEL, frame.obj.provenance)
        assertEquals("Ответ AI", frame.viaTitle)
    }

    // Забранный ответ стал объектом и живёт в «Недавнем» — разговор на этом кончился (#794).
    @Test fun `забирание ответа завершает разговор`() = runTest(dispatcher) {
        val vm = chattingVm()

        vm.takeChatAnswer(); advanceUntilIdle()

        assertNull("разговор завершён", vm.ui.value.chat)
    }

    @Test fun `забирать нечего, пока модель не ответила`() = runTest(dispatcher) {
        consent.granted = true
        chatResponder.inFlight = kotlinx.coroutines.CompletableDeferred()
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()
        vm.sendChatMessage("что тут написано?"); advanceUntilIdle()

        vm.takeChatAnswer(); advanceUntilIdle()

        assertNotNull("экран разговора на месте", openChatOf(vm.ui.value))
        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind)
    }

    @Test fun `строка «Забрать ответ» появляется ровно тогда, когда есть что забирать`() {
        val obj = PointObject("o", "image/png", ScratchRef("/o"), ObjectState(ObjectKind.IMAGE))
        val asked = ChatState(obj, listOf(com.point.core.model.ChatMessage(com.point.core.model.ChatRole.USER, "?")))
        val answered = asked.copy(
            messages = asked.messages + com.point.core.model.ChatMessage(com.point.core.model.ChatRole.ASSISTANT, "вот"),
        )

        assertNull("пустой разговор забирать нечем", takeableAnswer(ChatState(obj)))
        assertNull("свой же вопрос объектом не становится", takeableAnswer(asked))
        assertEquals("вот", takeableAnswer(answered))
        assertNull("пока идёт ответ, предыдущий устарел", takeableAnswer(answered.copy(pending = true)))
    }

    @Test fun `вышло не то, что обещала строка, — и Point говорит об этом`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.OFFICE, "application/vnd", ScratchRef("/o")))

        vm.onBubble(
            bubble(id = "a").copy(yields = com.point.core.model.ActionYield.New(ObjectKind.TEXT)),
        )
        advanceUntilIdle()

        assertEquals("Ожидался текст — вышел документ", vm.ui.value.message)
        assertEquals(Outcome.NONE, vm.ui.value.messageOutcome)
    }

    @Test fun `вид совпал, а внутри другое — Point и об этом говорит`() = runTest(dispatcher) {

        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        resolver.result = ActionResult.Success(
            ResultObject(
                ObjectKind.PDF, "application/pdf", ScratchRef("/o"),
                mapOf(com.point.core.flow.META_YIELD_NOUN to "снимок страницы"),
            ),
        )

        vm.onBubble(
            bubble(id = "a").copy(
                yields = com.point.core.model.ActionYield.New(ObjectKind.PDF, "PDF с текстом документа · без оформления"),
            ),
        )
        advanceUntilIdle()

        assertEquals("Обещали PDF с текстом документа — вышло снимок страницы", vm.ui.value.message)
        assertEquals(Outcome.NONE, vm.ui.value.messageOutcome)
    }

    @Test fun `реализатор назвал сделанное теми же словами — молчим`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        resolver.result = ActionResult.Success(
            ResultObject(
                ObjectKind.PDF, "application/pdf", ScratchRef("/o"),
                mapOf(com.point.core.flow.META_YIELD_NOUN to "PDF с текстом документа"),
            ),
        )

        vm.onBubble(
            bubble(id = "a").copy(
                yields = com.point.core.model.ActionYield.New(ObjectKind.PDF, "PDF с текстом документа · без оформления"),
            ),
        )
        advanceUntilIdle()

        assertNull(vm.ui.value.message)
    }

    @Test fun `вышло обещанное — лишних слов нет`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))

        vm.onBubble(
            bubble(id = "a").copy(yields = com.point.core.model.ActionYield.New(ObjectKind.TEXT)),
        )
        advanceUntilIdle()

        assertNull(vm.ui.value.message)
    }

    private fun linkVm() = vm(
        caps = mapOf(
            CapabilityId("ai") to setOf(Intent.UNDERSTAND),
            CapabilityId("drop-link") to setOf(Intent.SEND),
        ),
        cloud = setOf(CapabilityId("ai"), CapabilityId("drop-link")),
    )

    @Test fun `разрешение для моделей не выкладывает файл по открытой ссылке`() = runTest(dispatcher) {
        consent.granted = true
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "drop-link", title = "Дать ссылку")); advanceUntilIdle()

        assertTrue("про открытую ссылку спрашивают отдельно", vm.ui.value.cloudConsent)
        assertEquals("файл никуда не уехал", "__unset__", resolver.lastAmendment)
    }

    @Test fun `цена открытой ссылки названа до отправки, а не после`() = runTest(dispatcher) {
        consent.granted = true
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "drop-link", title = "Дать ссылку")); advanceUntilIdle()

        val ask = vm.ui.value
        assertTrue("не сказано, что заберёт любой: ${ask.cloudDestination}", ask.cloudDestination.contains("любому"))
        assertTrue("не сказано, сколько живёт: ${ask.cloudDestination}", ask.cloudDestination.contains("сутк"))
        assertTrue("вопрос звучит про ссылку: ${ask.cloudTitle}", ask.cloudTitle.contains("ссылке"))
        assertEquals("Выложить", ask.cloudConfirm)
    }

    @Test fun `второе «Дать ссылку» спрашивает заново`() = runTest(dispatcher) {
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "drop-link", title = "Дать ссылку")); advanceUntilIdle()
        vm.confirmCloud(); advanceUntilIdle()
        assertEquals("первый файл выложен", "done", vm.ui.value.message)

        vm.onBubble(bubble(id = "drop-link", title = "Дать ссылку")); advanceUntilIdle()

        assertTrue("следующий файл — следующее решение", vm.ui.value.cloudConsent)
    }

    @Test fun `согласие на модели остаётся однократным`() = runTest(dispatcher) {
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()
        vm.confirmCloud(); advanceUntilIdle()

        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()

        assertEquals("второй раз не спрашиваем", false, vm.ui.value.cloudConsent)
    }

    @Test fun `отозванное согласие возвращает вопрос`() = runTest(dispatcher) {
        consent.granted = true
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.setCloudAllowed(false); advanceUntilIdle()
        assertEquals(false, vm.ui.value.cloudEnabled)

        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()
        assertTrue("отозвали — значит спрашиваем снова", vm.ui.value.cloudConsent)
    }

    @Test fun `вопрос про облако называет тот сервис, ключ которого задан`() = runTest(dispatcher) {
        val openRouter = AI_PROVIDERS.first()
        userKeys.stored = com.point.core.flow.UserAiKeys.NONE
            .with(com.point.core.flow.UserAiKey(openRouter.id, "sk-1"))
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()

        val said = vm.ui.value.cloudDestination
        assertTrue("адресат не назван: $said", said.contains(openRouter.name))
        assertFalse("класс адресатов вместо адресата: $said", said.contains("AI-провайдера"))
    }

    @Test fun `незнакомому адресу имя не выдумывается`() = runTest(dispatcher) {
        userKeys.stored = com.point.core.flow.UserAiKeys.NONE
            .with(com.point.core.flow.UserAiKey(com.point.core.flow.OWN_SERVICE_ID, "sk-1", baseUrl = "https://мой.прокси/v1"))
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()

        assertTrue(vm.ui.value.cloudDestination.contains("AI-провайдера"))
    }

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

    @Test fun `open-in also offers apps reachable via one transform`() = runTest(dispatcher) {

        appLauncher.apps = emptyList()
        appLauncher.mimeApps = mapOf("text/plain" to listOf(AppTarget("Notepad", "com.np", "A")))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "open-in")); advanceUntilIdle()

        val picker = vm.ui.value.appPicker
        assertEquals(1, picker?.size)
        assertEquals("a", picker?.first()?.via)
        assertTrue(picker?.first()?.label?.contains("текст") == true)
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
        assertEquals(ObjectKind.TEXT, appLauncher.launchedObj?.state?.kind)
    }

    @Test fun `offers any new clipboard text, ignores blank and re-dismissed text`() {
        val vm = vm()

        vm.offerClipboard("любой скопированный текст")
        assertEquals("любой скопированный текст", vm.clipboard.value)

        vm.offerClipboard("   ")
        assertNull(vm.clipboard.value)

        vm.offerClipboard("ещё текст")
        assertEquals("ещё текст", vm.clipboard.value)
        vm.dismissClipboard()
        assertNull(vm.clipboard.value)
        vm.offerClipboard("ещё текст")
        assertNull(vm.clipboard.value)
    }

    @Test fun `refreshClipboard offers when idle but stays silent while a flow is active`() = runTest(dispatcher) {
        val vm = vm()

        vm.refreshClipboard { "скопировано до флоу" }
        assertEquals("скопировано до флоу", vm.clipboard.value)
        vm.dismissClipboard()

        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.refreshClipboard { "новый текст" }
        assertNull(vm.clipboard.value)

        vm.endFlow()
        vm.refreshClipboard { "новый текст" }
        assertEquals("новый текст", vm.clipboard.value)
    }

    @Test fun `the app picker never lists an app twice — direct and bridged dedup`() = runTest(dispatcher) {
        val files = AppTarget("Files", "com.files", "A")
        appLauncher.apps = listOf(files)
        appLauncher.mimeApps = mapOf("text/plain" to listOf(files))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "open-in")); advanceUntilIdle()

        assertEquals(1, vm.ui.value.appPicker?.size)
    }

    @Test fun `тап по поиску не выполняет реализатор и называет причину, если искать не в чем`() = runTest(dispatcher) {
        val vm = vm(caps = mapOf(CapabilityId("find") to setOf(Intent.UNDERSTAND)))
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "find", title = "Найти в документе")); advanceUntilIdle()

        assertEquals("__unset__", resolver.lastAmendment)
        assertNull(vm.ui.value.find)
        assertEquals("Страница ещё не прочитана — искать не в чем", vm.ui.value.message)
        assertEquals(com.point.core.ui.Outcome.FAILED, vm.ui.value.messageOutcome)
    }
}

private class FakeStore : ObjectStore {
    var failIngest = false

    /** Как вещь зовётся у системы: спрашивается до копии (#640). */
    var systemName: String? = null

    var nameAsked = 0

    override suspend fun nameOf(sourceUri: String): String? {
        nameAsked++
        return systemName
    }

    /** Приём отдаёт снимок, пока тест не скажет иначе. Для PDF важен именно вид объекта. */
    var kind = ObjectKind.IMAGE

    var clearedTimes = 0
    override suspend fun ingest(sourceUri: String, mime: String): PointObject =
        if (failIngest) error("boom") else PointObject("in", mime, ScratchRef("/in"), ObjectState(kind))
    override suspend fun ingestMultiple(sources: List<String>): PointObject =
        PointObject("coll", "inode/directory", ScratchRef("/coll"), ObjectState(ObjectKind.COLLECTION))
    override suspend fun put(result: ResultObject): PointObject =
        PointObject("out", result.mime, result.uri, ObjectState(result.type), result.metadata)

    var content: CollectionContent<PointObject> = CollectionContent.empty()
    override suspend fun children(collection: PointObject, limit: Int) = content
    override suspend fun readText(obj: PointObject, limit: Int): String = ""

    override suspend fun newScratchFile(extension: String): ScratchRef =
        ScratchRef(java.io.File.createTempFile("point-test-", ".$extension").absolutePath)
    override suspend fun clear() { clearedTimes++ }
}

private class FakeChatResponder : com.point.core.flow.AiChatResponder {
    var text = "ответ"
    var inFlight: kotlinx.coroutines.CompletableDeferred<String>? = null
    var calls = 0
    override suspend fun reply(obj: PointObject, history: List<com.point.core.model.ChatMessage>, message: String): String {
        calls++
        return inFlight?.await() ?: text
    }
}

private class FakeResolver : Resolver {
    var result: ActionResult = ActionResult.Done("done")

    var leavesDevice = false
    var lastAmendment: String? = "__unset__"
    var previews: Map<CapabilityId, Preview> = emptyMap()

    var throwsOnPerform: Throwable? = null

    var noRealizer = false

    var stage: String? = null

    var holdMs: Long = 0

    var uninterruptible = false

    var lateStage: String? = null
    var lateAfterMs: Long = 100

    val performed = mutableListOf<CapabilityId>()

    var lastInput: PointObject? = null
    override fun leavesDevice(capabilityId: CapabilityId): Boolean = leavesDevice

    override fun realizerFor(capabilityId: CapabilityId): Realizer {
        if (noRealizer) error("No realizer for capability=${capabilityId.value}")
        return object : Realizer {
            override val capabilityId = capabilityId
            override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
                performed += capabilityId
                lastInput = input
                lastAmendment = amendment
                throwsOnPerform?.let { throw it }
                stage?.let { com.point.core.flow.reportStage(it) }
                lateStage?.let { late ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        kotlinx.coroutines.delay(lateAfterMs)
                        com.point.core.flow.reportStage(late)
                    }
                }
                if (holdMs > 0) {
                    if (uninterruptible) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            kotlinx.coroutines.delay(holdMs)
                        }
                    } else {
                        kotlinx.coroutines.delay(holdMs)
                    }
                }
                return result
            }
            override suspend fun preview(input: PointObject): Preview? = previews[capabilityId]
        }
    }
}

private class FakeCapability(
    override val id: CapabilityId,
    private val served: Set<Intent>,
    network: Boolean = false,
    slow: Boolean = false,
) : Capability {
    override val icon = "x"
    override val meta = CapabilityMeta(
        latency = if (slow) Latency.SLOW else Latency.INSTANT,
        network = network,
    )
    override fun label(state: ObjectState) = "Action ${id.value}"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    override fun intents(state: ObjectState) = served
}

private class FakeRegistry(
    private val caps: Map<CapabilityId, Set<Intent>>,
    private val cloud: Set<CapabilityId> = emptySet(),
    private val slow: Set<CapabilityId> = emptySet(),

    private val keyNeeding: Set<CapabilityId> = emptySet(),

    private val keySet: () -> Boolean = { true },
) : CapabilityRegistry {
    override fun bubblesFor(state: ObjectState): List<Bubble> =
        caps.keys.map { id ->

            val label = com.point.core.flow.labelNeedingKey(
                "Action ${id.value}",
                keySet = id !in keyNeeding || keySet(),
            )
            Bubble("x", label, id, ObjectState(ObjectKind.TEXT))
        }

    override fun all(): Collection<com.point.core.flow.Capability> = emptyList()

    override fun latentBubblesFor(state: ObjectState) = emptyList<com.point.core.model.LatentBubble>()

    override fun byId(id: CapabilityId): Capability =
        caps[id]?.let { FakeCapability(id, it, id in cloud, id in slow) }
            ?: error("No capability registered for id=${id.value}")
}

private class FakeEnrichment(var features: Set<Feature> = emptySet()) : Enrichment {

    var updates: List<EnrichmentUpdate>? = null
    var stepDelayMs: Long = 0

    var understandsOnce = false
    var runs = 0
    val seen = mutableListOf<PointObject>()
    override fun enrich(obj: PointObject): kotlinx.coroutines.flow.Flow<EnrichmentUpdate> =
        kotlinx.coroutines.flow.flow {
            runs++
            seen += obj
            val script = when {
                understandsOnce && runs > 1 -> listOf(EnrichmentUpdate(emptySet(), emptyMap(), emptyList()))
                else -> updates ?: listOf(EnrichmentUpdate(features, emptyMap(), emptyList()))
            }
            for (u in script) {
                if (stepDelayMs > 0) kotlinx.coroutines.delay(stepDelayMs)
                emit(u)
            }
        }
}

private class FakeHistory : HistoryStore {
    val recorded = mutableListOf<PointObject>()
    val updated = mutableListOf<PointObject>()

    /** Что лежит в «Недавнем»: [remove] уносит запись отсюда, как настоящий store — с диска. */
    val entries = mutableListOf<HistoryEntry>()
    val removed = mutableListOf<String>()

    var opened: PointObject? = null
    override suspend fun record(obj: PointObject) { recorded += obj }
    override suspend fun update(obj: PointObject) { updated += obj }
    override suspend fun recent(limit: Int): List<HistoryEntry> = entries.toList()
    override suspend fun open(entryId: String): PointObject? = opened
    override suspend fun remove(entryId: String) {
        removed += entryId
        entries.removeAll { it.id == entryId }
    }
    override suspend fun clearAll() { entries.clear() }
}

private class FakeUsage : CapabilityUsage {
    val recorded = mutableListOf<CapabilityId>()
    var counts: Map<CapabilityId, Int> = emptyMap()
    override fun counts(): Map<CapabilityId, Int> = counts
    override suspend fun record(id: CapabilityId) { recorded += id }
}

private class FakeUserKeys(var stored: com.point.core.flow.UserAiKeys = com.point.core.flow.UserAiKeys.NONE) :
    UserKeyStore {
    var saved: com.point.core.flow.UserAiKey? = null
    var forgotten: String? = null
    override fun keys() = stored
    override suspend fun save(key: com.point.core.flow.UserAiKey) { saved = key; stored = stored.with(key) }
    override suspend fun forget(providerId: String) { forgotten = providerId; stored = stored.without(providerId) }
    override suspend fun clear() { stored = com.point.core.flow.UserAiKeys.NONE }
}

private class FakeAiFacts : com.point.core.flow.AiFacts {
    var facts = mutableMapOf<String, com.point.core.flow.AiFact>()
    var now = 1_000_000L
    override fun all(): Map<String, com.point.core.flow.AiFact> = facts
    override fun remember(providerId: String, outcome: com.point.core.flow.AiOutcome) {
        facts[providerId] = com.point.core.flow.AiFact(outcome, now)
    }
}

private class FakeBuiltInKeys(var ours: Map<String, String> = emptyMap()) : com.point.core.flow.BuiltInAiKeys {
    override fun key(providerId: String) = ours[providerId].orEmpty()
    override fun have(): Set<String> = ours.filterValues { it.isNotBlank() }.keys
}

private class FakePrivacyConsent(var granted: Boolean = false) : PrivacyConsent {
    val asked = mutableListOf<com.point.core.flow.CloudScope>()
    override suspend fun allowed(scope: com.point.core.flow.CloudScope): Boolean {
        asked += scope
        return com.point.core.flow.remembersConsent(scope) && granted
    }
    override suspend fun allow(scope: com.point.core.flow.CloudScope) {
        if (com.point.core.flow.remembersConsent(scope)) granted = true
    }
    override suspend fun revoke(scope: com.point.core.flow.CloudScope) {
        if (com.point.core.flow.remembersConsent(scope)) granted = false
    }
}

private class FakeSensoryFeedback : com.point.core.flow.SensoryFeedback {
    val events = mutableListOf<String>()
    override fun tap() { events += "tap" }
    override fun success() { events += "success" }
    override fun failure() { events += "failure" }
}

private class FakePinnedActions : com.point.core.flow.PinnedActions {
    val pinned = mutableMapOf<ObjectKind, CapabilityId?>()
    override fun pinnedFor(kind: ObjectKind) = pinned[kind]
    override suspend fun pin(kind: ObjectKind, id: CapabilityId) { pinned[kind] = id }
    override suspend fun unpin(kind: ObjectKind) { pinned[kind] = null }
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

internal val TEST_KEYS = com.point.core.flow.DeviceKeys.generate()

internal val TEST_ACCOUNT = com.point.core.flow.PointAccount(
    deviceId = "d1",
    deviceToken = "tok-1",
    email = "me@example.com",
    deviceName = "Pixel",
    kind = com.point.core.flow.DeviceKind.PHONE,
)

internal class FakeAccountStore(private var account: com.point.core.flow.PointAccount?) : com.point.core.flow.AccountStore {
    override fun current() = account
    override suspend fun save(account: com.point.core.flow.PointAccount) { this.account = account }
    override suspend fun clear() { account = null }
}

internal class FakeCircleClient(
    private var circle: List<com.point.core.flow.CircleDevice>? = emptyList(),

    private val gone: Boolean = false,
) : com.point.core.flow.AccountClient {
    var revoked: String? = null
    var signedOut = false
    var enrolledKey: String? = null
    override suspend fun start(deviceName: String, kind: com.point.core.flow.DeviceKind) =
        com.point.core.flow.LoginStart("l1", "claim-1", "K7-42Q", "https://point.example/login?d=l1")
    override suspend fun poll(loginId: String, claimToken: String): com.point.core.flow.LoginPoll =
        com.point.core.flow.LoginPoll.Ready(TEST_ACCOUNT)
    override suspend fun circle(account: com.point.core.flow.PointAccount): com.point.core.flow.CircleAnswer =
        when {
            gone -> com.point.core.flow.CircleAnswer.Revoked
            circle == null -> com.point.core.flow.CircleAnswer.Unreachable
            else -> com.point.core.flow.CircleAnswer.Circle(circle!!)
        }
    override suspend fun enroll(account: com.point.core.flow.PointAccount, publicKey: String): Boolean {
        enrolledKey = publicKey
        return true
    }
    override suspend fun revoke(account: com.point.core.flow.PointAccount, deviceId: String): Boolean {
        revoked = deviceId
        circle = circle?.filterNot { it.id == deviceId }
        return true
    }
    override suspend fun signOut(account: com.point.core.flow.PointAccount): Boolean {
        signedOut = true
        return revoke(account, account.deviceId)
    }

    var deleteFails = false
    var deleted = false
        private set

    override suspend fun deleteAccount(account: com.point.core.flow.PointAccount): Boolean {
        if (deleteFails) return false
        deleted = true
        return true
    }
}

internal class CountingSignInClient(
    private var readyAfter: Int = 1,
    private val startFails: Boolean = false,
) : com.point.core.flow.AccountClient {
    var starts = 0
    var polls = 0

    fun readyNow() { readyAfter = polls + 1 }

    override suspend fun start(deviceName: String, kind: com.point.core.flow.DeviceKind): com.point.core.flow.LoginStart? {
        starts++
        return if (startFails) null
        else com.point.core.flow.LoginStart("l1", "claim-1", "K7-42Q", "https://point.example/login?d=l1")
    }
    override suspend fun poll(loginId: String, claimToken: String): com.point.core.flow.LoginPoll {
        polls++
        return if (polls >= readyAfter) com.point.core.flow.LoginPoll.Ready(TEST_ACCOUNT)
        else com.point.core.flow.LoginPoll.Pending
    }
    override suspend fun circle(account: com.point.core.flow.PointAccount) =
        com.point.core.flow.CircleAnswer.Circle(emptyList())
    override suspend fun enroll(account: com.point.core.flow.PointAccount, publicKey: String) = true
    override suspend fun revoke(account: com.point.core.flow.PointAccount, deviceId: String) = true
    override suspend fun deleteAccount(account: com.point.core.flow.PointAccount) = true
}

private class FakePcLinks : com.point.core.flow.PcLinks {
    var pc: com.point.core.flow.LinkedPc? = null
    override fun current() = pc
    override suspend fun save(pc: com.point.core.flow.LinkedPc) { this.pc = pc }
    override suspend fun clear() { pc = null }
}

private class FakePcTransport : com.point.core.flow.PcTransport {
    var outbox: List<com.point.core.flow.PcOutboxEntry> = emptyList()
    var outboxFetches = 0
    var downloadOk = true

    var capsDelayMs = 0L
    val acked = mutableListOf<Int>()
    var pushedPhoneCaps: List<com.point.core.flow.PcRemoteAction> = emptyList()
    override suspend fun send(
        pc: com.point.core.flow.LinkedPc,
        obj: com.point.core.model.PointObject,
        fileName: String,
        meta: Map<String, String>,
        action: String?,
    ): com.point.core.flow.PcSendOutcome = com.point.core.flow.PcSendOutcome.Sent()
    override suspend fun fetchCaps(pc: com.point.core.flow.LinkedPc): List<com.point.core.flow.PcRemoteAction>? {
        if (capsDelayMs > 0) kotlinx.coroutines.delay(capsDelayMs)
        return listOf(com.point.core.flow.PcRemoteAction("pc-open", "Открыть на компьютере"))
    }
    override suspend fun fetchOutbox(pc: com.point.core.flow.LinkedPc): List<com.point.core.flow.PcOutboxEntry>? {
        outboxFetches++
        return outbox
    }
    override suspend fun downloadOutboxFile(pc: com.point.core.flow.LinkedPc, id: Int, targetPath: String): Boolean {
        if (!downloadOk) return false
        java.io.File(targetPath).apply { parentFile?.mkdirs(); writeText("pulled-$id") }
        return true
    }
    override suspend fun ackOutbox(pc: com.point.core.flow.LinkedPc, id: Int) { acked += id }
    override suspend fun pushPhoneCaps(pc: com.point.core.flow.LinkedPc, caps: List<com.point.core.flow.PcRemoteAction>): Boolean {
        pushedPhoneCaps = caps
        return true
    }

    var sentSecrets: com.point.core.flow.SharedSecrets? = null
        private set
    var secretsReply: com.point.core.flow.SharedSecrets? = null

    override suspend fun exchangeSecrets(
        pc: com.point.core.flow.LinkedPc,
        mine: com.point.core.flow.SharedSecrets,
    ): com.point.core.flow.SharedSecrets? {
        sentSecrets = mine
        return secretsReply
    }
}

private class FakeChosenApps : com.point.core.flow.ChosenApps {
    val recorded = mutableListOf<com.point.core.flow.ChosenApp>()
    override fun all() = recorded.toList()
    override suspend fun record(app: com.point.core.flow.ChosenApp) { recorded += app }
}
