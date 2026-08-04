package com.point

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
import com.point.core.model.ValueRef
import com.point.core.ui.Outcome
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private val chosenApps = FakeChosenApps()
    private val userKeys = FakeUserKeys()
    private val journal = FakeUsageJournal()
    private val consent = FakePrivacyConsent()
    private val appLauncher = FakeAppLauncher()
    private val sensory = FakeSensoryFeedback()
    private val sensorySettings = FakeSensorySettings()

    /** «Куда можно отправлять» (#280): умолчание — максимум бесплатного, как у человека,
     *  не открывавшего настройки. */
    private val cloudPrivacy = object : com.point.core.flow.CloudPrivacySettings {
        var level = com.point.core.flow.PrivacyLevel.DEFAULT
        override fun level() = level
        override suspend fun setLevel(level: com.point.core.flow.PrivacyLevel) { this.level = level }
    }
    private val snapshot = FakeFlowSnapshotStore()
    private val crashLog = FakeCrashLog()
    private val pins = FakePinnedActions()

    /** Кадра нет: JVM не умеет `android.graphics`. Тест судит решения выделения, а не декодер. */
    private val noFrames = object : SelectionFrames {
        override fun frame(path: String, maxPx: Int) = null
        override fun crop(path: String, left: Int, top: Int, right: Int, bottom: Int) = null
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** A view model over the fakes, whose registry offers [caps] (id → the intents it serves),
     *  treats the ids in [cloud] as network capabilities (gated by consent) and the ids in
     *  [slow] as declared-slow ones (which keep the full busy screen instead of working quietly). */
    private fun vm(
        caps: Map<CapabilityId, Set<Intent>> = mapOf(CapabilityId("a") to setOf(Intent.PREPARE)),
        cloud: Set<CapabilityId> = emptySet(),
        slow: Set<CapabilityId> = emptySet(),
        discovery: com.point.core.flow.PcDiscovery = com.point.core.flow.PcDiscovery { kotlinx.coroutines.flow.flowOf(emptyList()) },
        linkMonitor: com.point.core.flow.LinkMonitor = com.point.core.flow.RememberingLinkMonitor(),
        // По умолчанию тестовый телефон УЖЕ вошёл (#472): дверь входа — отдельная история со
        // своими проверками, и ставить её перед каждой чужой значило бы мерять вход в сорока местах.
        account: com.point.core.flow.AccountStore = FakeAccountStore(TEST_ACCOUNT),
        accountClient: com.point.core.flow.AccountClient = FakeCircleClient(),
        browser: com.point.core.flow.BrowserOpener = com.point.core.flow.BrowserOpener { },
    ) = FlowViewModel(store, FakeRegistry(caps, cloud, slow), resolver, chatResponder, enrichment, history, favorites, usage, chosenApps, userKeys, journal, consent, appLauncher, FakePdfRasterizer(), sensory, sensorySettings, cloudPrivacy, snapshot, crashLog, dispatcher, pins, AppIconResolver { null }, pcPairings, pcTransport, discovery, basket, pcCaps, linkMonitor, PulledFileFactory { name -> java.io.File(java.io.File(System.getProperty("java.io.tmpdir")), "pulled-" + name).absolutePath }, noFrames, keyCheck, account, accountClient, browser)

    /** Проверка ключа (#465): что «ответил сервис», решает тест, а не сеть. */
    private val keyCheck = FakeAiKeyCheck()

    private class FakeAiKeyCheck : com.point.core.flow.AiKeyCheck {
        var probe = com.point.core.flow.KeyProbe(status = 200, reply = "Готово")
        var asked: UserAiConfig? = null
        /** Проверка сама может упасть — и это тоже обязано кончиться словами, а не тишиной. */
        var explode = false
        override suspend fun check(config: UserAiConfig): com.point.core.flow.KeyProbe {
            asked = config
            if (explode) error("что-то сломалось внутри проверки")
            return probe
        }
    }

    private val chatResponder = FakeChatResponder()
    private val basket = FakeBasket()
    private val pcCaps = FakePcCaps()
    private val pcPairings = FakePcPairings()
    private val pcTransport = FakePcTransport()

    private class FakePcCaps : com.point.core.flow.PcCapsStore {
        var saved: List<com.point.core.flow.PcRemoteAction>? = null
        var cleared = false
        override fun all(): List<com.point.core.flow.PcRemoteAction> = saved.orEmpty()
        override suspend fun save(caps: List<com.point.core.flow.PcRemoteAction>) { saved = caps }
        override suspend fun clear() { cleared = true; saved = null }
    }

    private class FakeBasket : com.point.core.flow.Basket {
        val added = mutableListOf<String>()
        override suspend fun add(obj: PointObject): Int { added += obj.uri.value; return added.size }
        override suspend fun items(): List<String> = added.toList()
        override suspend fun clear() = added.clear()
    }

    private fun bubble(id: String = "a", title: String = "Действие") =
        Bubble("x", title, CapabilityId(id), ObjectState(ObjectKind.TEXT))

    // --- Выделение области (#259): обвести можно, не распознавая ---

    @Test fun `выделение открывается и без слоя слов — отказ приходит от картинки, а не от чтения`() =
        runTest(dispatcher) {
            // Объект-картинка без META_OCR_ATOMS_REF: раньше openSelection выходил молча, потому
            // что требовал слой. Теперь он доходит до самой картинки; фейк кадра возвращает null,
            // и человек слышит причину — вместо тишины в ответ на тап.
            val vm = vm()
            vm.onShared("uri", "image/jpeg"); advanceUntilIdle()

            vm.openSelection(); advanceUntilIdle()

            assertEquals("Не удалось открыть страницу для выделения", vm.ui.value.message)
            assertEquals(Outcome.FAILED, vm.ui.value.messageOutcome)
        }

    // --- User rules (#66): a long-press pins the action for this object kind ---

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

    // --- #66 slice 4: a direct app pick joins the graph via ChosenApps + usage ---

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
        val vm = vm(); vm.restoreJourney(); advanceUntilIdle()

        assertEquals(ObjectKind.TEXT, vm.ui.value.frame?.obj?.state?.kind) // back on the same step
        assertEquals(2, vm.ui.value.path.size)                             // the whole journey
        assertEquals("Распознать текст", vm.ui.value.path.last().via)
        assertEquals("+380671234567", vm.ui.value.path.let { snapshot.frames.first().metadata["entity.phone"] })
    }

    @Test fun `a snapshot does not auto-open without an explicit restore — launcher lands on Home`() = runTest(dispatcher) {
        snapshot.frames = listOf(
            com.point.core.model.FlowSnapshotFrame("root", ObjectKind.IMAGE, "image/png", tempFile("img")),
        )
        val vm = vm(); advanceUntilIdle() // HomeActivity path: never calls restoreJourney()
        assertNull(vm.ui.value.frame)     // Home, not the restored object
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
        vm.onShared("uri", "image/png") // a fresh share sets the guard...
        vm.restoreJourney()             // ...so the opt-in restore self-skips
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

    // --- Рабочая копия объекта: чужие байты не остаются на устройстве ---

    /**
     * Инвариант проекта, который до ревизии #239 не проверял никто: по окончании флоу
     * `ObjectStore.clear()` обязателен. Цена пропуска — не абстрактная: в корпусе владельца
     * это фото карты, платёжка и военная ведомость, и они остались бы лежать копией в scratch
     * после того, как человек закрыл Point. Журнал пути (`snapshot`) чистился и проверялся,
     * а сами байты — нет.
     */
    @Test fun `конец флоу стирает рабочую копию объекта, а не только запись о пути`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        val beforeEnd = store.clearedTimes

        vm.endFlow(); advanceUntilIdle()

        assertTrue("копия объекта обязана быть стёрта", store.clearedTimes > beforeEnd)
    }

    /** Открыли объект из «Недавнего» — предыдущая копия уходит ДО того, как появится новая:
     *  иначе scratch копит объекты всех прошлых флоу, а обещано «работаем с одной копией». */
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
        // Отказ обязан выглядеть отказом: карточка исхода рисует знак по этому полю, и без него
        // «Не удалось открыть» встало бы под галочкой «Готово».
        assertEquals(Outcome.FAILED, s.messageOutcome)
        // Этот отказ человек видит НЕ на экране объекта: объекта нет (frame == null). Значит
        // исход рисует экран «объекта ещё нет» — и совет повторить шаринг уместен там ровно
        // потому, что сорвался именно приём.
        assertEquals("Попробуйте поделиться объектом в Point ещё раз", shareAgainHint(s.messageOutcome))
    }

    /**
     * Исход не наследуется. `copy` сохраняет прошлое значение, поэтому удача, пришедшая после
     * отказа, легко получала бы чужой знак «✕» — пока баннер красился в один цвет всегда, этого
     * не было видно, а карточка исхода (#358) показывает такую ложь сразу.
     */
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

    /**
     * Отмена — третий исход, которого не было в паре «удача/отказ» (#288, #358).
     *
     * Человек сам остановил долгую работу. «✕» ему ставить нельзя — ничего не отказывало; но и
     * «✓ Готово» нельзя тем более: работа не дошла до конца, а голос экрана прочитал бы вслух
     * «Готово. Отменено». Пока поле было `Boolean`, третьего ответа просто не существовало, и
     * отмена доставалась успеху — вместе со всяким сообщением, которому флаг забыли поставить.
     */
    @Test fun `отменённое человеком не выдаёт себя за сделанное`() = runTest(dispatcher) {
        resolver.holdMs = 10_000 // работа ещё идёт, когда человек жмёт «Отмена»
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble())
        dispatcher.scheduler.advanceTimeBy(50)
        vm.cancelAction()
        advanceUntilIdle()

        assertEquals("Отменено", vm.ui.value.message)
        assertEquals(Outcome.NONE, vm.ui.value.messageOutcome)
        // И совета «поделитесь ещё раз» тут тоже нет: ничего не ломалось.
        assertNull(shareAgainHint(vm.ui.value.messageOutcome))
    }

    // --- «Отменить» либо отменяет, либо её нет (#114) ---

    /**
     * Кнопка стоит только над той работой, которую отмена действительно снимает.
     *
     * Было: `onCancel` передавался экрану ожидания всегда, а задачу держало одно действие по
     * пузырю. Над «Открываю…» кнопка была нарисована и не отменяла ничего.
     */
    @Test fun `кнопка отмены есть только там, где есть что отменять`() = runTest(dispatcher) {
        val vm = vm(slow = setOf(CapabilityId("a")))

        vm.onShared("uri", "image/png") // приём расшаренного идёт, объекта ещё нет
        assertTrue("экран ожидания поднят", showsBusyScreen(vm.ui.value))
        assertFalse("а отменять нечем — кнопки нет", showsCancel(vm.ui.value))
        advanceUntilIdle()

        resolver.holdMs = 1_000 // работа ещё идёт, когда человек смотрит на экран
        vm.onBubble(bubble(id = "a")) // действие над объектом — вот его отменить можно
        dispatcher.scheduler.advanceTimeBy(10)
        assertTrue(showsCancel(vm.ui.value))
    }

    /**
     * Отмена снимает ту работу, что идёт сейчас, — а не ту, что давно закончилась.
     *
     * Ровно этот путь и врал человеку: задача хранилась от действия по пузырю и не обнулялась по
     * завершении. Тап «Отменить» во время «Открываю…» снимал уже сделанное, печатал «Отменено» —
     * и объект открывался секундой позже, потому что открытие никто не останавливал.
     */
    @Test fun `отмена снимает идущую работу, а не законченную`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "a")); advanceUntilIdle() // задача действия появилась и закончилась
        vm.endFlow(); advanceUntilIdle() // человек вернулся на «Недавнее»
        history.opened = PointObject("hist", "image/png", ScratchRef("/hist"), ObjectState(ObjectKind.IMAGE))

        vm.openFromHistory(entry("h")) // идёт «Открываю…»
        assertTrue("отсюда возвращаться есть куда — кнопка на месте", showsCancel(vm.ui.value))
        vm.cancelAction()
        advanceUntilIdle()

        assertNull("объект не открылся вопреки отмене", vm.ui.value.frame)
        assertNull("и «Отменено» не осталось висеть без объекта", vm.ui.value.message)
    }

    /** Цепочка — самая долгая работа в Point (несколько сетевых шагов). Отмена обязана
     *  остановить её, а не позволить следующему шагу приземлиться поверх «Отменено». */
    @Test fun `отменённая цепочка не делает следующий шаг`() = runTest(dispatcher) {
        favorites.chains = listOf(FavoriteChain("c", "Цепочка", listOf(CapabilityId("a"), CapabilityId("a"))))
        resolver.result = ActionResult.Success(
            ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/out")),
        )
        resolver.holdMs = 1_000
        resolver.uninterruptible = true
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.applyFavorite(favorites.chains.first())
        dispatcher.scheduler.advanceTimeBy(10)
        assertTrue("над цепочкой кнопка есть", showsCancel(vm.ui.value))
        vm.cancelAction()
        advanceUntilIdle()

        assertEquals("ни один шаг не приземлился", 1, vm.ui.value.path.size)
        assertEquals("Отменено", vm.ui.value.message)
    }

    /** «Ищу приложения…» — тоже занятость с кнопкой; отменённый поиск не смеет открыть список. */
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

    /** Законченная работа не отменяется задним числом: раньше задача не обнулялась, и тап
     *  «Отменить» над следующей занятостью объявлял отменённым уже сделанное. */
    @Test fun `нечего отменять — нечего и объявлять отменённым`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "a")); advanceUntilIdle() // действие уже завершилось

        vm.cancelAction()

        assertEquals("done", vm.ui.value.message) // исход законченной работы уцелел
    }

    /** Удача с домашнего экрана попадает на тот же экран «объекта ещё нет» — и не смеет
     *  выглядеть сбоем: ни знака «✕», ни совета чинить несломанное. */
    @Test fun `сохранённый ключ AI — это удача, а не сорванный шаринг`() = runTest(dispatcher) {
        val vm = vm()

        vm.saveAiConfig(UserAiConfig.DEFAULT); advanceUntilIdle()

        val s = vm.ui.value
        assertNull(s.frame) // объекта нет — рисует экран без объекта
        assertEquals("Ключ AI сохранён", s.message)
        assertEquals(Outcome.DONE, s.messageOutcome)
        assertNull(shareAgainHint(s.messageOutcome))
    }

    /**
     * Из состояния «сообщение без объекта» есть выход (#114).
     *
     * Прежний тест доводил до этого состояния и на нём останавливался — а выхода из него не было
     * вовсе: `onBack()` возвращал false, дверь отдавала «назад» системе, и Point закрывался сразу
     * после удачного сохранения ключа.
     */
    @Test fun `сообщение без объекта убирается, а не запирает человека`() = runTest(dispatcher) {
        val vm = vm()

        vm.saveAiConfig(UserAiConfig.DEFAULT); advanceUntilIdle()
        assertEquals("Ключ AI сохранён", vm.ui.value.message)

        assertTrue("из состояния-сообщения нет выхода", vm.dismissMessage())
        assertNull(vm.ui.value.message)
        assertEquals(Outcome.NONE, vm.ui.value.messageOutcome)
        // Убирать больше нечего — дальше «назад» честно уходит двери.
        assertFalse(vm.dismissMessage())
    }

    /** Тот же тупик приезжает историей: объект из «Недавнего» пропал с диска. */
    @Test fun `недоступный объект из истории тоже отпускает человека`() = runTest(dispatcher) {
        history.opened = null // запись есть, а файла за ней уже нет
        val vm = vm()

        vm.openFromHistory(
            HistoryEntry("id", "text/plain", ObjectKind.TEXT, "имя", 0L, ScratchRef("/gone")),
        )
        advanceUntilIdle()

        assertEquals("Объект недоступен", vm.ui.value.message)
        assertTrue(vm.dismissMessage())
        assertNull(vm.ui.value.message)
    }

    /** Сообщение поверх объекта — не тупик: там есть и пузырьки, и «назад» по стеку. */
    @Test fun `сообщение над объектом не считается тупиком`() = runTest(dispatcher) {
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        assertFalse(vm.dismissMessage())
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

    @Test fun `a Success step journals the traversed graph edge`() = runTest(dispatcher) {
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals(mapOf("IMAGE>a>TEXT" to 1), journal.graph())
    }

    @Test fun `a Failure step journals a FAILED event`() = runTest(dispatcher) {
        resolver.result = ActionResult.Failure("Не удалось", recoverable = true)
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals(1, journal.events.count { it.type == UsageEventType.FAILED })
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

    /**
     * Пятый канал, которого нет в `ActionResult`: реализатор не вернул ничего, а взорвался
     * (NPE в чужой библиотеке, оборванный поток, отсутствующий файл). До ревизии #239 весь
     * набор проверял только четыре объявленных исхода, и ветка «неучтённый сбой» — та самая,
     * что превращает «ничего не теряется молча» в обещание — не выполнялась ни разу.
     */
    @Test fun `взорвавшийся реализатор доходит до человека сообщением, а не тишиной`() = runTest(dispatcher) {
        resolver.throwsOnPerform = IllegalStateException("scratch-файл исчез")
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertEquals("scratch-файл исчез", vm.ui.value.message)
        assertEquals(Outcome.FAILED, vm.ui.value.messageOutcome)
        assertNull("экран ожидания обязан уйти, иначе это «зависло»", vm.ui.value.busy)
    }

    /** Пузырёк нарисован, а реализатора для него нет (потеряли `@IntoSet` при добавлении
     *  действия): тап обязан сказать об этом, а не уронить приложение и не сделать вид.
     *
     *  Что именно сказано — тоже проверка, а не мелочь: текст исключения написан для
     *  разработчика («No realizer for capability=excel» — так падает настоящий
     *  `DefaultResolver`), и попасть на экран человека он не должен. На пути избранной
     *  цепочки та же беда давно говорит по-человечески («Шаг цепочки недоступен») —
     *  одиночный тап обязан звучать так же. */
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

    // --- #288: тихая работа тоже говорит — стадия живёт на объекте, а не пропадает ---

    @Test fun `a quiet action speaks on the object — its stage reaches the state`() = runTest(dispatcher) {
        resolver.stage = "Распаковываю архив"
        resolver.holdMs = 1_000 // действие ещё идёт, когда мы смотрим на экран
        val vm = vm()
        vm.onShared("uri", "application/zip"); advanceUntilIdle()

        vm.onBubble(bubble())
        dispatcher.scheduler.advanceTimeBy(50) // стадия сказана, работа не кончилась

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
        assertNull(quietStage(vm.ui.value)) // одна строка, один хозяин — экран ИЛИ объект
    }

    @Test fun `a new action never wears the words of the previous one`() = runTest(dispatcher) {
        resolver.stage = "Распаковываю архив"
        resolver.holdMs = 1_000
        val vm = vm(caps = mapOf(CapabilityId("a") to setOf(Intent.PREPARE), CapabilityId("b") to setOf(Intent.PREPARE)))
        vm.onShared("uri", "application/zip"); advanceUntilIdle()

        vm.onBubble(bubble(id = "a"))
        dispatcher.scheduler.advanceTimeBy(50)
        assertEquals("Распаковываю архив", quietStage(vm.ui.value))

        // Второе действие молчит: над ним не должно висеть сказанное первым.
        resolver.stage = null
        vm.onBubble(bubble(id = "b"))
        dispatcher.scheduler.advanceTimeBy(50)

        assertNull(quietStage(vm.ui.value))
    }

    @Test fun `смененная работа замолкает — новое действие не носит её слов`() = runTest(dispatcher) {
        // Обнулить стадию в начале новой работы мало: снятое действие продолжает идти. Нативный
        // проход движка и отрисовка страниц об отмене не знают, договаривают своё — и фраза
        // прошлой работы приземлялась НА живой экран уже над другой. Начать второе действие,
        // пока первое идёт, человек может: тап по самому объекту (#290) списком не гасится.
        resolver.stage = "Читаю текст на устройстве"
        resolver.lateStage = "Пробую повернуть страницу — 2 из 3"
        resolver.holdMs = 1_000
        val vm = vm(caps = mapOf(CapabilityId("a") to setOf(Intent.PREPARE), CapabilityId("b") to setOf(Intent.PREPARE)))
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "a"))
        dispatcher.scheduler.advanceTimeBy(10)

        resolver.stage = null
        resolver.lateStage = null
        vm.onBubble(bubble(id = "b")) // второе действие о себе молчит
        dispatcher.scheduler.advanceTimeBy(500) // снятая работа договаривает своё

        assertTrue("объект работает — строке есть где появиться", objectWorking(vm.ui.value))
        assertNull("но слова принадлежали бы снятой работе", quietStage(vm.ui.value))
    }

    @Test fun `остановленная работа не оставляет слов в состоянии`() = runTest(dispatcher) {
        // Человек нажал «Отменить» на экране ожидания. Работа снята, но её хвост ещё идёт: то,
        // что он скажет, не должно осесть в состоянии и всплыть над следующей занятостью.
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

    @Test fun `«Выйти» стирает всё, что устройство знало про аккаунт и про свой компьютер (#472)`() = runTest(dispatcher) {
        // Уйти и оставить на телефоне память о чужом компьютере — та же ловушка, что с отвязанным
        // пейрингом: следующий человек увидел бы чужие умения и чужую связь.
        val store = FakeAccountStore(TEST_ACCOUNT)
        pcPairings.pairing = com.point.core.flow.PcPairing("10.0.2.2", 8391, "tok")
        val vm = vm(account = store)

        vm.openDevices(); advanceUntilIdle()
        vm.signOut(); advanceUntilIdle()

        assertNull(store.current())
        assertNull(pcPairings.pairing)
        assertTrue(pcCaps.cleared)
        assertTrue(vm.ui.value.signIn is com.point.core.flow.SignIn.SignedOut)
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
        // До ответа сервера на экране уже стоит само устройство: пустой список был бы враньём.
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

    @Test fun `отозванное устройство узнаёт об этом от сервера и показывает вход (#472)`() = runTest(dispatcher) {
        // Отключили этот телефон с другого устройства. Молчаливо сломанный Point человек прочитал
        // бы как поломку — а сервер уже сказал «вас тут нет».
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
        // Молчаливый выход человек прочитал бы как поломку.
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

    @Test fun `a paired Home visit lights the from-PC banner, throttled, and pull opens the flow (#161)`() = runTest(dispatcher) {
        pcPairings.pairing = com.point.core.flow.PcPairing("10.0.2.2", 8391, "tok")
        pcTransport.outbox = listOf(com.point.core.flow.PcOutboxEntry(1, mapOf("name" to "чек.jpg", "mime" to "image/jpeg")))
        val vm = vm()

        vm.loadRecent(); advanceUntilIdle()
        assertEquals(1, vm.fromPcCount.value)
        vm.loadRecent(); advanceUntilIdle()
        assertEquals(1, pcTransport.outboxFetches) // throttled: one wire call for two visits

        vm.pullFromPc(); advanceUntilIdle()
        assertEquals(ObjectKind.IMAGE, vm.ui.value.frame?.obj?.state?.kind) // ingested and opened
        assertEquals(listOf(1), pcTransport.acked)
        assertEquals(0, vm.fromPcCount.value)
    }

    @Test fun `pull uses the CURRENT PC outbox, not a stale throttled snapshot (#161)`() = runTest(dispatcher) {
        pcPairings.pairing = com.point.core.flow.PcPairing("10.0.2.2", 8391, "tok")
        pcTransport.outbox = listOf(com.point.core.flow.PcOutboxEntry(1, mapOf("name" to "old.txt", "mime" to "text/plain")))
        val vm = vm()
        vm.loadRecent(); advanceUntilIdle() // throttled fetch → snapshot [1]

        // The PC queued a new object AFTER the last (throttled) fetch — the phone's cached list is stale.
        pcTransport.outbox = listOf(
            com.point.core.flow.PcOutboxEntry(1, mapOf("name" to "old.txt", "mime" to "text/plain")),
            com.point.core.flow.PcOutboxEntry(2, mapOf("name" to "new.txt", "mime" to "text/plain")),
        )
        vm.pullFromPc(); advanceUntilIdle()

        // Must pull what is ACTUALLY on the PC now — both entries — not the stale [1] that misses the new object.
        assertEquals(listOf(1, 2), pcTransport.acked)
    }

    @Test fun `closing the devices screen refreshes the from-PC banner for Home (#161)`() = runTest(dispatcher) {
        pcPairings.pairing = com.point.core.flow.PcPairing("10.0.2.2", 8391, "tok")
        pcTransport.outbox = listOf(com.point.core.flow.PcOutboxEntry(2, mapOf("name" to "a.txt", "mime" to "text/plain")))
        val vm = vm()

        vm.openDevices(); advanceUntilIdle()
        vm.closeDevices(); advanceUntilIdle()

        assertEquals(1, vm.fromPcCount.value)
    }

    @Test fun `a pulled entry carrying a PC intent runs that action after ingest (#161 v2)`() = runTest(dispatcher) {
        pcPairings.pairing = com.point.core.flow.PcPairing("10.0.2.2", 8391, "tok")
        pcTransport.outbox = listOf(
            com.point.core.flow.PcOutboxEntry(1, mapOf("name" to "т.txt", "mime" to "text/plain", "pc.action" to "call")),
        )
        val vm = vm(caps = mapOf(CapabilityId("call") to setOf(Intent.OPEN)))
        vm.loadRecent(); advanceUntilIdle()

        resolver.lastAmendment = "__unset__"
        vm.pullFromPc(); advanceUntilIdle()

        assertEquals(null, resolver.lastAmendment) // the call realizer actually ran
        assertEquals(listOf(1), pcTransport.acked)
    }

    /**
     * Обратная половина того же пути (#161 v2): компьютер называет действие по имени, а имена
     * у двух половинок расходятся — на ПК действие есть, на телефоне ещё (или уже) нет.
     *
     * Объект к этому моменту уже скачан и открыт, и потерять его из-за незнакомого названия
     * нельзя: человек обязан увидеть объект и фразу о том, что действия здесь нет. До этой
     * правки `registry.byId` в `onShared` был единственным вызовом реестра без страховки: на
     * телефоне исключению из `viewModelScope` деваться некуда — процесс умирает; на JVM оно
     * теряется тихо, и видно только последствие, которое здесь и проверяется, — ни действия,
     * ни слова человеку. Фейковый реестр отдавал заглушку на любое имя и прятал это вовсе.
     */
    @Test fun `названного компьютером действия на телефоне нет — объект открыт, человек предупреждён`() = runTest(dispatcher) {
        pcPairings.pairing = com.point.core.flow.PcPairing("10.0.2.2", 8391, "tok")
        pcTransport.outbox = listOf(
            com.point.core.flow.PcOutboxEntry(1, mapOf("name" to "т.txt", "mime" to "text/plain", "pc.action" to "видеомонтаж")),
        )
        val vm = vm() // такого действия на телефоне нет
        vm.loadRecent(); advanceUntilIdle()

        vm.pullFromPc(); advanceUntilIdle()

        assertTrue("объект обязан остаться открытым", vm.ui.value.frame != null)
        assertEquals(Outcome.FAILED, vm.ui.value.messageOutcome)
        assertEquals("Компьютер попросил действие, которого в Point нет", vm.ui.value.message)
    }

    @Test fun `компьютер из сети подхватывается сам, без QR и без адреса руками (#472)`() = runTest(dispatcher) {
        // Быстрый путь по локальной сети остался, но шагом человека быть перестал: экрана
        // пейринга нет, адрес приносит mDNS, согласие даёт сам компьютер своим окном.
        val found = com.point.core.flow.DiscoveredPc("Рабочий ноутбук", "192.168.1.42", 8391)
        val vm = vm(discovery = com.point.core.flow.PcDiscovery { kotlinx.coroutines.flow.flowOf(listOf(found)) })

        vm.openDevices(); advanceUntilIdle()

        assertEquals("192.168.1.42", pcPairings.pairing?.host)
        assertEquals(listOf("pc-open"), pcCaps.saved?.map { it.id })
        assertTrue(pcTransport.pushedPhoneCaps.any { it.id == "call" })
        vm.closeDevices()
    }

    @Test fun `неудачное рукопожатие по сети никого не тревожит (#472)`() = runTest(dispatcher) {
        // Шуметь отказом того, чего человек не заказывал, значило бы врать, будто он что-то сделал не так.
        val found = com.point.core.flow.DiscoveredPc("Чужой ПК", "192.168.1.77", 8391)
        pcTransport.pairOk = false
        val vm = vm(discovery = com.point.core.flow.PcDiscovery { kotlinx.coroutines.flow.flowOf(listOf(found)) })

        vm.openDevices(); advanceUntilIdle()

        assertNull(pcPairings.pairing)
        assertNull(vm.ui.value.devicesScreen?.error)
        vm.closeDevices()
    }

    @Test fun `a failed download keeps the entries un-acked (#161)`() = runTest(dispatcher) {
        pcPairings.pairing = com.point.core.flow.PcPairing("10.0.2.2", 8391, "tok")
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
        pcPairings.pairing = com.point.core.flow.PcPairing("10.0.2.2", 8391, "tok")

        vm.openDevices(); advanceUntilIdle()

        assertEquals(listOf("pc-open"), pcCaps.saved?.map { it.id })
        vm.closeDevices()
    }

    @Test fun `the basket opens as one collection flow and its count reaches Home (#96)`() = runTest(dispatcher) {
        basket.added += listOf("/b/1-a.txt", "/b/2-b.jpg")
        val vm = vm()

        vm.loadRecent(); advanceUntilIdle()
        assertEquals(2, vm.basketCount.value)

        vm.openBasket(); advanceUntilIdle()
        assertEquals(ObjectKind.COLLECTION, vm.ui.value.frame?.obj?.state?.kind)

        vm.endFlow()
        vm.clearBasket(); advanceUntilIdle()
        assertEquals(0, vm.basketCount.value)
    }

    @Test fun `обрезанный набор доносит до экрана настоящее число файлов`() = runTest(dispatcher) {
        // Набор больше предела обхода (#460): показать всё нельзя, но промолчать об этом — соврать.
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

    // --- Граф объектов (#222): найденное доезжает до кадра и открывается ---

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
        // The live extractor and stored metadata build the same id on purpose (#222).
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

    // --- Обогащение не затирает установленное действием (#243) ---

    @Test fun `a fact the object already knows is not overwritten by a later reading`() = runTest(dispatcher) {
        // Сценарий #243: «Понять глубже» чинит адрес, кадр выталкивается, фоновое обогащение
        // распознаёт ту же картинку заново и возвращает повреждённую версию. Она не должна
        // победить: перевывод факта из неизменившихся байтов не может дать ничего нового.
        val vm = vm()
        enrichment.updates = listOf(
            EnrichmentUpdate(emptySet(), mapOf("entity.address" to "первое"), emptyList()),
            EnrichmentUpdate(emptySet(), mapOf("entity.address" to "второе"), emptyList()),
        )

        vm.onShared("/x.jpg", "image/jpeg"); advanceUntilIdle()

        assertEquals("первое", vm.ui.value.frame?.obj?.metadata?.get("entity.address"))
    }

    @Test fun `the pointer to recognised text still refreshes`() = runTest(dispatcher) {
        // Иначе «Распознать текст» уедет в файл из прошлого прогона.
        val vm = vm()
        enrichment.updates = listOf(
            EnrichmentUpdate(emptySet(), mapOf(com.point.core.flow.META_OCR_TEXT_REF to "/scratch/a.txt"), emptyList()),
            EnrichmentUpdate(emptySet(), mapOf(com.point.core.flow.META_OCR_TEXT_REF to "/scratch/b.txt"), emptyList()),
        )

        vm.onShared("/x.jpg", "image/jpeg"); advanceUntilIdle()

        assertEquals("/scratch/b.txt", vm.ui.value.frame?.obj?.metadata?.get(com.point.core.flow.META_OCR_TEXT_REF))
    }

    // --- Bring-your-own AI key (#19) ---

    @Test fun `openKeySettings shows the key screen prefilled`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        assertEquals(UserAiConfig.DEFAULT, vm.ui.value.keyScreen)
    }

    /**
     * #452: отказ «нет ключа» подменялся экраном настроек, и причина при этом стиралась. Человек
     * тапал «Понять», ждал и видел экран про ключи без единого слова о том, почему тот открылся.
     */
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

    /** Предложение есть только у того отказа, который ключом и чинится: предложить ключ там, где
     *  он ни при чём, — выдумать человеку причину. */
    @Test fun `обычный отказ ключа не предлагает`() = runTest(dispatcher) {
        resolver.result = ActionResult.Failure("Не удалось прочитать страницу", recoverable = true)
        val vm = vm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble()); advanceUntilIdle()

        assertNull(keyOfferLabel(vm.ui.value.message))
    }

    /** «Отмена» на экране ключей возвращает к объекту, где причина по-прежнему сказана словами:
     *  иначе человек остаётся ни с чем, а это неотличимо от «действие ничего не сделало» (#452). */
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

    /** Обратная половина: всё прочее сказанное экран ключей стирает, как и раньше, — «Ключ AI
     *  сохранён» из прошлого захода к этому отношения не имеет. */
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

    /**
     * #467: отказ расшифровки зовёт задать ключ СВОИМИ словами, не говоря «задайте свой ключ». По
     * одной марке предложение под ним не появлялось бы вовсе — человек с голосовым и без ключей
     * остался бы ровно там, откуда всё началось.
     */
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

    /** Пришедший ПО ПРЕДЛОЖЕНИЮ приходит с вопросом «какой из семи ключей задать» — и ответ на него
     *  стоит на экране ключей, а не остаётся позади (#467). */
    @Test fun `причина доезжает до экрана ключей вместе с человеком`() = runTest(dispatcher) {
        val why = "Расшифровать некому: Whisper слушает по ключу Groq. " +
            com.point.core.flow.KEY_SETTINGS_CALL
        resolver.result = ActionResult.Failure(why, recoverable = true)
        val vm = vm()
        vm.onShared("voice.ogg", "audio/ogg"); advanceUntilIdle()
        vm.onBubble(bubble()); advanceUntilIdle()

        vm.openKeySettings(); advanceUntilIdle() // тап по предложению

        assertEquals(why, vm.ui.value.keyScreenNote)
    }

    /** Пришедшему шестерёнкой объяснять нечего — и чужая причина за ним не тянется. */
    @Test fun `пришедший сам не видит на экране ключей чужой причины`() = runTest(dispatcher) {
        val vm = vm()

        vm.openKeySettings(); advanceUntilIdle()

        assertNull(vm.ui.value.keyScreenNote)
    }

    @Test fun `saveAiConfig stores the key and closes the screen`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        val config = UserAiConfig("sk-1", "https://h/v1", "m")

        vm.saveAiConfig(config); advanceUntilIdle()

        assertEquals(config, userKeys.saved)
        assertNull(vm.ui.value.keyScreen)
    }

    // --- Доведение до работающего ключа (#465) ---

    @Test fun `удачная проверка сохраняет ключ и показывает слова сервиса`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        val config = UserAiConfig("sk-1", "https://h/v1", "m")
        keyCheck.probe = com.point.core.flow.KeyProbe(status = 200, reply = "Готово")

        vm.checkAiKey(config); advanceUntilIdle()

        assertEquals("проверять надо ровно то, что человек набрал", config, keyCheck.asked)
        assertEquals(com.point.core.flow.KeyVerdict.Works("Готово"), vm.ui.value.keyVerdict)
        assertEquals("доказанный ключ обязан сохраниться сам", config, userKeys.saved)
        // Экран остаётся: человек должен УВИДЕТЬ «работает», а не догадаться по его исчезновению.
        assertNotNull(vm.ui.value.keyScreen)
        assertTrue(vm.ui.value.aiKeySet)
    }

    @Test fun `непрошедший проверку ключ не сохраняется`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        keyCheck.probe = com.point.core.flow.KeyProbe(status = 401, error = "unauthorized")

        vm.checkAiKey(UserAiConfig("не-тот", "https://h/v1", "m")); advanceUntilIdle()

        // Записать ключ, про который уже известно, что он не подошёл, значит подготовить человеку
        // следующий необъяснимый отказ.
        assertNull("отказавший ключ не имеет права осесть на диске", userKeys.saved)
        val verdict = vm.ui.value.keyVerdict as com.point.core.flow.KeyVerdict.Refused
        assertTrue(verdict.what.contains("не подошёл"))
        assertNotNull("с отказом человек остаётся на экране, где стоит его ключ", vm.ui.value.keyScreen)
    }

    @Test fun `упавшая проверка — это отказ, а не тишина`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        keyCheck.explode = true

        vm.checkAiKey(UserAiConfig("sk-1", "https://h/v1", "m")); advanceUntilIdle()

        assertFalse("кнопка осталась бы в «Проверяю…» навсегда", vm.ui.value.keyChecking)
        assertTrue(vm.ui.value.keyVerdict is com.point.core.flow.KeyVerdict.Refused)
    }

    @Test fun `приговор не переживает закрытие экрана`() = runTest(dispatcher) {
        val vm = vm()
        vm.openKeySettings(); advanceUntilIdle()
        vm.checkAiKey(UserAiConfig("sk-1", "https://h/v1", "m")); advanceUntilIdle()
        assertNotNull(vm.ui.value.keyVerdict)

        vm.closeKeySettings()
        vm.openKeySettings(); advanceUntilIdle()

        // «Работает», висящее над другим ключом, — ровно та ложь, против которой вся проверка.
        assertNull(vm.ui.value.keyVerdict)
        assertFalse(vm.ui.value.keyChecking)
    }

    @Test fun `пустой ключ не гоняет сеть`() = runTest(dispatcher) {
        val vm = vm()
        vm.checkAiKey(UserAiConfig("   ", "https://h/v1", "m")); advanceUntilIdle()

        assertNull("сеть не имеет права уйти без ключа", keyCheck.asked)
        assertNull(vm.ui.value.keyVerdict)
    }

    @Test fun `«Недавнее» знает, задан ли ключ`() = runTest(dispatcher) {
        val vm = vm()
        vm.loadRecent(); advanceUntilIdle()
        assertFalse("приглашение подключить AI должно быть видно", vm.ui.value.aiKeySet)

        userKeys.config = UserAiConfig("sk-1", "https://h/v1", "m")
        vm.loadRecent(); advanceUntilIdle()
        assertTrue("ключ есть — звать больше некуда", vm.ui.value.aiKeySet)
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

    // "ai" is cloud but now opens the chat (#4), so the generic consent-then-run tests below drive a
    // neutral cloud action ("cloudx"); "ai" stays cloud for the favorite-chain gating test.
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

        assertTrue(vm.ui.value.cloudConsent)                  // asked
        assertNull(vm.ui.value.message)                       // nothing ran
        assertEquals("__unset__", resolver.lastAmendment)     // the realizer was never invoked
    }

    @Test fun `confirming consent runs the pending cloud action and persists the grant`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "cloudx")); advanceUntilIdle()

        vm.confirmCloud(); advanceUntilIdle()

        assertEquals("готово", vm.ui.value.message)           // the gated action finally ran
        assertEquals(false, vm.ui.value.cloudConsent)         // prompt dismissed
        assertTrue(consent.granted)                           // remembered for next time
    }

    @Test fun `declining consent cancels the cloud action and sends nothing`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "cloudx")); advanceUntilIdle()

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

        vm.onBubble(bubble(id = "cloudx")); advanceUntilIdle()

        assertEquals("готово", vm.ui.value.message)
        assertEquals(false, vm.ui.value.cloudConsent)         // no prompt
    }

    /**
     * #325, дыра, которую до ревизии #239 держал только тест `DefaultResolver` в `:executors`:
     * «Распознать текст» объявлена местной и бесплатной, а за ней цепочка, где на неудаче
     * движка объект уходит в облако. На корпусе владельца движок не справляется на шести
     * кадрах из двадцати двух — путь обычный, не редкий.
     *
     * Экран судит по факту (`Resolver.leavesDevice`), а не по объявлению способности. Ручка
     * у фейка была, и её никто не поворачивал: убери из `isCloud` вторую половину — весь
     * набор оставался зелёным, а объект уезжал бы в чужой сервис без спроса.
     */
    @Test fun `местная способность с облачным запасным всё равно спрашивает согласие`() = runTest(dispatcher) {
        resolver.result = ActionResult.Done("готово")
        resolver.leavesDevice = true                          // цепочка «устройство → облако»
        val vm = vm(caps = mapOf(CapabilityId("ocr") to setOf(Intent.UNDERSTAND)))  // объявлена местной
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "ocr")); advanceUntilIdle()

        assertTrue("согласие обязано спрашиваться по факту, а не по объявлению", vm.ui.value.cloudConsent)
        assertNull(vm.ui.value.message)
        assertEquals("__unset__", resolver.lastAmendment)     // до реализатора дело не дошло
    }

    /** Обратная половина той же развилки: цепочка целиком местная — спрашивать не о чем,
     *  иначе «Point всё время просит разрешение» и согласие обесценивается. */
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

        assertTrue(vm.ui.value.chat != null)                  // the chat opened
        assertTrue(vm.ui.value.chatOpen)
        assertEquals("__unset__", resolver.lastAmendment)     // no one-shot realizer ran
    }

    // --- Разговор переживает «назад», а идущий вопрос отменяем (#453) ---

    /** Открыть разговор, спросить и получить ответ — исходное состояние для тестов ниже. */
    private fun kotlinx.coroutines.test.TestScope.chattingVm(): FlowViewModel {
        consent.granted = true
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()
        vm.sendChatMessage("что тут написано?"); advanceUntilIdle()
        return vm
    }

    @Test fun `«назад» из разговора закрывает экран, а не стирает разговор`() = runTest(dispatcher) {
        val vm = chattingVm()
        assertEquals(2, vm.ui.value.chat?.messages?.size)      // вопрос и ответ

        assertTrue(vm.onBack())                               // «назад» — к объекту

        assertNull("экрана разговора нет", openChatOf(vm.ui.value))
        assertEquals("сказанное осталось", 2, vm.ui.value.chat?.messages?.size)
    }

    @Test fun `повторное «Спросить AI» возвращает в тот же разговор`() = runTest(dispatcher) {
        val vm = chattingVm()
        vm.onBack()

        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()

        assertEquals(2, openChatOf(vm.ui.value)?.messages?.size)
    }

    /** Разговор принадлежит своему объекту: перенести сказанное на другой было бы хуже, чем
     *  начать с чистого листа. */
    @Test fun `новый объект начинает разговор заново`() = runTest(dispatcher) {
        val vm = chattingVm()
        vm.onBack()
        resolver.result = ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/o")))
        vm.onBubble(bubble(id = "cloudx")); advanceUntilIdle()  // получился новый объект

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
        // Один только вопрос: остановленное не договаривает за собеседника — ни ответом, ни отказом.
        assertEquals(1, vm.ui.value.chat?.messages?.size)
    }

    /** Квота уже потрачена: пришедший ответ ложится в разговор, даже если экран закрыт (#453).
     *  Раньше он выбрасывался молча — `s.chat ?: return@update s`. */
    @Test fun `ответ, пришедший после выхода, не пропадает`() = runTest(dispatcher) {
        consent.granted = true
        val late = kotlinx.coroutines.CompletableDeferred<String>()
        chatResponder.inFlight = late
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()
        vm.sendChatMessage("что тут написано?"); advanceUntilIdle()
        vm.onBack()                                           // ушёл, не дождавшись

        late.complete("ответ издалека"); advanceUntilIdle()

        assertNull(openChatOf(vm.ui.value))                   // экран не всплыл сам
        assertEquals(2, vm.ui.value.chat?.messages?.size)
        assertEquals("ответ издалека", vm.ui.value.chat?.messages?.last()?.text)
    }

    @Test fun `a favorite chain hiding a cloud step is gated too — not a back door`() = runTest(dispatcher) {
        val vm = cloudVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.applyFavorite(FavoriteChain("c", "Цепочка", listOf(CapabilityId("ai"))))
        advanceUntilIdle()

        assertTrue(vm.ui.value.cloudConsent)                  // asked before replaying
        assertEquals("__unset__", resolver.lastAmendment)     // no step reached the cloud
    }

    // --- «Показать модели» ≠ «выложить в открытый доступ» (#114) ---

    /** Способности с разной ценой: «Понять» показывает объект модели, «Дать ссылку» кладёт файл
     *  на сервер открытым. Обе сетевые — и до сих пор их разрешал один флаг. */
    private fun linkVm() = vm(
        caps = mapOf(
            CapabilityId("ai") to setOf(Intent.UNDERSTAND),
            CapabilityId("drop-link") to setOf(Intent.SEND),
        ),
        cloud = setOf(CapabilityId("ai"), CapabilityId("drop-link")),
    )

    /**
     * Разрешение, данное ради моделей, не выкладывает файл в открытый доступ.
     *
     * Было: человек однажды разрешил облако для «Понять» — и «Дать ссылку» молча уводило файл на
     * сервер, откуда его заберёт любой, кому переслали ссылку. Про цену он узнавал ПОСЛЕ загрузки,
     * с уже выданной карточки.
     */
    @Test fun `разрешение для моделей не выкладывает файл по открытой ссылке`() = runTest(dispatcher) {
        consent.granted = true // облако для AI разрешено давно
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "drop-link", title = "Дать ссылку")); advanceUntilIdle()

        assertTrue("про открытую ссылку спрашивают отдельно", vm.ui.value.cloudConsent)
        assertEquals("файл никуда не уехал", "__unset__", resolver.lastAmendment)
    }

    /** Цена называется ДО отправки: текст вопроса — про открытость файла и срок жизни ссылки. */
    @Test fun `цена открытой ссылки названа до отправки, а не после`() = runTest(dispatcher) {
        consent.granted = true
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "drop-link", title = "Дать ссылку")); advanceUntilIdle()

        val ask = vm.ui.value
        assertTrue("не сказано, что заберёт любой: ${ask.cloudDestination}", ask.cloudDestination.contains("любому"))
        assertTrue("не сказано, сколько живёт: ${ask.cloudDestination}", ask.cloudDestination.contains("суток"))
        assertTrue("вопрос звучит про ссылку: ${ask.cloudTitle}", ask.cloudTitle.contains("ссылке"))
        assertEquals("Выложить", ask.cloudConfirm)
    }

    /** Согласие на открытую ссылку не запоминается: следующий файл — следующее решение. */
    @Test fun `второе «Дать ссылку» спрашивает заново`() = runTest(dispatcher) {
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "drop-link", title = "Дать ссылку")); advanceUntilIdle()
        vm.confirmCloud(); advanceUntilIdle()
        assertEquals("первый файл выложен", "done", vm.ui.value.message)

        vm.onBubble(bubble(id = "drop-link", title = "Дать ссылку")); advanceUntilIdle()

        assertTrue("следующий файл — следующее решение", vm.ui.value.cloudConsent)
    }

    /** А обычное облако допросом не становится: разрешили один раз — работает. */
    @Test fun `согласие на модели остаётся однократным`() = runTest(dispatcher) {
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()
        vm.confirmCloud(); advanceUntilIdle()

        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()

        assertEquals("второй раз не спрашиваем", false, vm.ui.value.cloudConsent)
    }

    /** Согласие, которое нельзя отозвать, — не согласие. Тумблер в настройках возвращает вопрос. */
    @Test fun `отозванное согласие возвращает вопрос`() = runTest(dispatcher) {
        consent.granted = true
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.setCloudAllowed(false); advanceUntilIdle()
        assertEquals(false, vm.ui.value.cloudEnabled)

        vm.onBubble(bubble(id = "ai")); advanceUntilIdle()
        assertTrue("отозвали — значит спрашиваем снова", vm.ui.value.cloudConsent)
    }

    /** Шаг «Дать ссылку», спрятанный в избранной цепочке, не проезжает под текстом про AI. */
    @Test fun `цепочка со ссылкой спрашивает про ссылку, а не про AI`() = runTest(dispatcher) {
        consent.granted = true
        val vm = linkVm()
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.applyFavorite(FavoriteChain("c", "Цепочка", listOf(CapabilityId("ai"), CapabilityId("drop-link"))))
        advanceUntilIdle()

        assertTrue(vm.ui.value.cloudConsent)
        assertTrue(
            "спросили не про то: ${vm.ui.value.cloudDestination}",
            vm.ui.value.cloudDestination.contains("любому"),
        )
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

    @Test fun `refreshClipboard offers when idle but stays silent while a flow is active`() = runTest(dispatcher) {
        val vm = vm()

        vm.refreshClipboard { "скопировано до флоу" }
        assertEquals("скопировано до флоу", vm.clipboard.value)
        vm.dismissClipboard()

        vm.onShared("uri", "image/png"); advanceUntilIdle()
        vm.refreshClipboard { "новый текст" }
        assertNull(vm.clipboard.value) // a flow is on screen — the Home banner does not exist there

        vm.endFlow()
        vm.refreshClipboard { "новый текст" }
        assertEquals("новый текст", vm.clipboard.value) // back on Home → the copied text is offered
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

    // --- #279: «Найти в документе» показывает места на странице, а не выполняет реализатор ---

    /**
     * Тап по поиску открывает экран, а не запускает действие: находки живут подсветкой на
     * странице, и провести их через `perform` нечем. Здесь у объекта слоя слов нет вовсе —
     * значит, человек обязан услышать причину, а не получить тишину в ответ на нажатие (#290).
     */
    @Test fun `тап по поиску не выполняет реализатор и называет причину, если искать не в чем`() = runTest(dispatcher) {
        val vm = vm(caps = mapOf(CapabilityId("find") to setOf(Intent.UNDERSTAND)))
        vm.onShared("uri", "image/png"); advanceUntilIdle()

        vm.onBubble(bubble(id = "find", title = "Найти в документе")); advanceUntilIdle()

        assertEquals("__unset__", resolver.lastAmendment) // реализатор не звали вовсе
        assertNull(vm.ui.value.find)
        assertEquals("Страница ещё не прочитана — искать не в чем", vm.ui.value.message)
        assertEquals(com.point.core.ui.Outcome.FAILED, vm.ui.value.messageOutcome)
    }
}

// --- Fakes ---

private class FakeStore : ObjectStore {
    var failIngest = false
    /** Сколько раз стёрли рабочую копию: инвариант «чужие байты не остаются» проверяем счётом. */
    var clearedTimes = 0
    override suspend fun ingest(sourceUri: String, mime: String): PointObject =
        if (failIngest) error("boom") else PointObject("in", mime, ScratchRef("/in"), ObjectState(ObjectKind.IMAGE))
    override suspend fun ingestMultiple(sources: List<String>): PointObject =
        PointObject("coll", "inode/directory", ScratchRef("/coll"), ObjectState(ObjectKind.COLLECTION))
    override suspend fun put(result: ResultObject): PointObject =
        PointObject("out", result.mime, result.uri, ObjectState(result.type), result.metadata)
    /** Что store отдаёт как содержимое набора — вместе со счётом, который может быть больше списка. */
    var content: CollectionContent<PointObject> = CollectionContent.empty()
    override suspend fun children(collection: PointObject, limit: Int) = content
    override suspend fun readText(obj: PointObject, limit: Int): String = ""
    override suspend fun newScratchFile(extension: String): ScratchRef = ScratchRef("/scratch.$extension")
    override suspend fun clear() { clearedTimes++ }
}

/**
 * Отвечающий на вопросы к объекту. По умолчанию отвечает сразу; [inFlight] — ответ, который ещё в
 * пути: незавершённое обещание `advanceUntilIdle` не проматывает (в отличие от `delay`), и это
 * единственный способ посмотреть на экран, пока вопрос действительно идёт.
 */
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
    /** Реализаторы фейка локальные, и он это знает: контракт по умолчанию отвечает
     *  «может уйти наружу» намеренно — не знаем, значит спрашиваем согласие. */
    var leavesDevice = false
    var lastAmendment: String? = "__unset__"
    var previews: Map<CapabilityId, Preview> = emptyMap()
    /** Реализатор взорвался вместо того, чтобы вернуть `Failure`, — неучтённый сбой. */
    var throwsOnPerform: Throwable? = null
    /** Для способности нет ни одного реализатора: пузырёк нарисован, а исполнять нечем. */
    var noRealizer = false
    /** Что реализатор говорит о себе (#288); null — молчит, как молчат короткие действия. */
    var stage: String? = null
    /** Сколько работа идёт после сказанного — чтобы тест успел посмотреть на экран, пока она жива. */
    var holdMs: Long = 0
    /**
     * Работа, которая об отмене не знает (#114).
     *
     * Так ведёт себя настоящая: нативный проход движка и сетевой запрос доходят до конца сами и
     * возвращают результат уже ПОСЛЕ того, как человек нажал «Отменить». `NonCancellable` — модель
     * этой непрерываемости, а не трюк ради теста: именно на ней ломалось обещание отмены.
     */
    var uninterruptible = false
    /**
     * Слово, которое работа договаривает, когда её уже сняли (#288).
     *
     * Так ведёт себя настоящее долгое действие: нативный проход Tesseract и отрисовка страниц
     * об отмене не знают и доходят до конца сами. `NonCancellable` — и есть модель этой
     * непрерываемости, а не трюк ради теста.
     */
    var lateStage: String? = null
    var lateAfterMs: Long = 100
    override fun leavesDevice(capabilityId: CapabilityId): Boolean = leavesDevice

    override fun realizerFor(capabilityId: CapabilityId): Realizer {
        if (noRealizer) error("No realizer for capability=${capabilityId.value}")
        return object : Realizer {
            override val capabilityId = capabilityId
            override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
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
) : CapabilityRegistry {
    override fun bubblesFor(state: ObjectState): List<Bubble> =
        caps.keys.map { Bubble("x", "Action ${it.value}", it, ObjectState(ObjectKind.TEXT)) }
    override fun intentsFor(state: ObjectState): List<Intent> =
        Intent.entries.filter { intent -> caps.values.any { intent in it } }
    override fun latentBubblesFor(state: ObjectState) = emptyList<com.point.core.model.LatentBubble>()

    /** Незнакомый id — ошибка, как и у настоящего `DefaultCapabilityRegistry`. Пока фейк
     *  отдавал заглушку на любое имя, он был добрее реальности, и незащищённый вызов
     *  `byId` не мог упасть ни в одном тесте — дыра держалась именно на доброте фейка. */
    override fun byId(id: CapabilityId): Capability =
        caps[id]?.let { FakeCapability(id, it, id in cloud, id in slow) }
            ?: error("No capability registered for id=${id.value}")
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
    /** Что отдаст «Недавнее» по тапу; null — запись не открылась. */
    var opened: PointObject? = null
    override suspend fun record(obj: PointObject) { recorded += obj }
    override suspend fun update(obj: PointObject) { updated += obj }
    override suspend fun recent(limit: Int): List<HistoryEntry> = emptyList()
    override suspend fun open(entryId: String): PointObject? = opened
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

/** Согласие по обещаниям (#114): «показать модели» помнится, «выложить по ссылке» — никогда. */
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

/** Тестовый пропуск аккаунта (#472). */
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

/** Сервер, которого нет: `circle = null` — не дозвонились. */
internal class FakeCircleClient(
    private var circle: List<com.point.core.flow.CircleDevice>? = emptyList(),
    /** Отключили ли это устройство с другого — тогда сервер отвечает «вас тут нет». */
    private val gone: Boolean = false,
) : com.point.core.flow.AccountClient {
    var revoked: String? = null
    var signedOut = false
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
    override suspend fun revoke(account: com.point.core.flow.PointAccount, deviceId: String): Boolean {
        revoked = deviceId
        circle = circle?.filterNot { it.id == deviceId }
        return true
    }
    override suspend fun signOut(account: com.point.core.flow.PointAccount): Boolean {
        signedOut = true
        return revoke(account, account.deviceId)
    }
}

private class FakePcPairings : com.point.core.flow.PcPairings {
    var pairing: com.point.core.flow.PcPairing? = null
    override fun current() = pairing
    override suspend fun save(pairing: com.point.core.flow.PcPairing) { this.pairing = pairing }
    override suspend fun clear() { pairing = null }
}

private class FakePcTransport : com.point.core.flow.PcTransport {
    var outbox: List<com.point.core.flow.PcOutboxEntry> = emptyList()
    var outboxFetches = 0
    var downloadOk = true
    var pairOk = true
    /** Сколько компьютер думает над «что ты умеешь» — тот самый запрос, пока он в пути (#451). */
    var capsDelayMs = 0L
    val acked = mutableListOf<Int>()
    var pushedPhoneCaps: List<com.point.core.flow.PcRemoteAction> = emptyList()
    override suspend fun pair(host: String, port: Int, deviceName: String): com.point.core.flow.PcPairing? =
        if (pairOk) com.point.core.flow.PcPairing(host, port, "tok") else null
    override suspend fun send(
        pairing: com.point.core.flow.PcPairing,
        obj: com.point.core.model.PointObject,
        fileName: String,
        meta: Map<String, String>,
        action: String?,
    ): com.point.core.flow.PcSendOutcome = com.point.core.flow.PcSendOutcome.Sent()
    override suspend fun fetchCaps(pairing: com.point.core.flow.PcPairing): List<com.point.core.flow.PcRemoteAction>? {
        if (capsDelayMs > 0) kotlinx.coroutines.delay(capsDelayMs)
        return listOf(com.point.core.flow.PcRemoteAction("pc-open", "Открыть на компьютере"))
    }
    override suspend fun fetchOutbox(pairing: com.point.core.flow.PcPairing): List<com.point.core.flow.PcOutboxEntry>? {
        outboxFetches++
        return outbox
    }
    override suspend fun downloadOutboxFile(pairing: com.point.core.flow.PcPairing, id: Int, targetPath: String): Boolean {
        if (!downloadOk) return false
        java.io.File(targetPath).apply { parentFile?.mkdirs(); writeText("pulled-$id") }
        return true
    }
    override suspend fun ackOutbox(pairing: com.point.core.flow.PcPairing, id: Int) { acked += id }
    override suspend fun pushPhoneCaps(pairing: com.point.core.flow.PcPairing, caps: List<com.point.core.flow.PcRemoteAction>): Boolean {
        pushedPhoneCaps = caps
        return true
    }
}

private class FakeChosenApps : com.point.core.flow.ChosenApps {
    val recorded = mutableListOf<com.point.core.flow.ChosenApp>()
    override fun all() = recorded.toList()
    override suspend fun record(app: com.point.core.flow.ChosenApp) { recorded += app }
}

private class FakeUsageJournal(private var enabled: Boolean = true) : UsageJournal {
    val events = mutableListOf<UsageEvent>()
    override suspend fun isEnabled() = enabled
    override suspend fun setEnabled(enabled: Boolean) { this.enabled = enabled }
    override suspend fun record(event: UsageEvent) { if (enabled) events += event }
    override suspend fun graph(): Map<String, Int> =
        events.filter { it.type == UsageEventType.EDGE }.groupingBy { it.detail }.eachCount()
    override suspend fun summary() = UsageSummary(
        events.count { it.type == UsageEventType.SHARED },
        events.count { it.type == UsageEventType.ACTION },
        events.count { it.type == UsageEventType.COMPLETED },
    )
    override suspend fun clear() { events.clear() }
}
