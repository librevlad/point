package com.point

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ensureActive
import com.point.core.flow.AppLauncher
import com.point.core.flow.AppTarget
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.ChosenApp
import com.point.core.flow.ChosenApps
import com.point.core.flow.CollectionContent
import com.point.core.flow.CrashLog
import com.point.core.flow.Enrichment
import com.point.core.flow.EnrichmentUpdate
import com.point.core.flow.FailedInvestigation
import com.point.core.flow.FlowSnapshotStore
import com.point.core.flow.HistoryStore
import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.Resolver
import com.point.core.flow.SensoryFeedback
import com.point.core.flow.SensorySettings
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.FocusPart
import com.point.core.flow.FrameTransform
import com.point.core.flow.InvestigationState
import com.point.core.flow.investigationStateOf
import com.point.core.flow.knownBy
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.flow.META_YIELD_NOUN
import com.point.core.flow.READER_NOT_DECODED
import com.point.core.flow.readerFailure
import com.point.core.flow.readerFailureIsFatal
import com.point.core.flow.reportStage
import com.point.core.flow.SnappedSelection
import com.point.core.flow.AiFacts
import com.point.core.flow.BuiltInAiKeys
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserKeyStore
import com.point.core.flow.aiCall
import com.point.core.flow.aiCheckedLine
import com.point.core.flow.aiOutcomeOfStatus
import com.point.core.flow.aiServiceLines
import com.point.core.flow.carryKnowledge
import com.point.core.flow.continuesObject
import com.point.core.flow.findOnPage
import com.point.core.flow.foundOnPageLabel
import com.point.core.ui.Outcome
import com.point.core.flow.snapSelection
import com.point.core.flow.yieldSurprise
import com.point.core.model.Feature
import com.point.core.model.ObjectState
import java.io.File
import com.point.core.model.ActionResult
import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.FlowSnapshotFrame
import com.point.core.model.HistoryEntry
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.isFileBacked
import com.point.core.model.ValueRef
import com.point.core.model.ScratchRef
import com.point.core.model.ObjectRef
import com.point.core.model.PointObject
import com.point.executors.Bitmaps
import com.point.executors.AiCapability
import com.point.executors.FindCapability
import com.point.executors.OpenInCapability
import com.point.executors.aiSuggestions
import com.point.executors.aiTransformTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class FlowViewModel @Inject constructor(
    private val store: ObjectStore,
    private val registry: CapabilityRegistry,
    private val resolver: Resolver,
    private val talk: ChatTalk,
    private val enrichment: Enrichment,
    private val history: HistoryStore,
    private val usage: CapabilityUsage,
    private val chosenApps: ChosenApps,
    private val userKeys: UserKeyStore,
    private val aiFacts: AiFacts,
    private val builtInKeys: BuiltInAiKeys,
    private val consent: PrivacyConsent,
    private val appLauncher: AppLauncher,
    private val pdfRasterizer: PdfRasterizer,
    private val sensory: SensoryFeedback,
    private val sensorySettings: SensorySettings,
    private val cloudPrivacy: com.point.core.flow.CloudPrivacySettings,
    private val yolo: com.point.core.flow.YoloMode,
    private val flowSnapshot: FlowSnapshotStore,
    private val crashLog: CrashLog,
    private val ioDispatcher: CoroutineDispatcher,
    private val appIcons: AppIconResolver,
    private val pcLinks: com.point.core.flow.PcLinks,
    private val pcTransport: com.point.core.flow.PcTransport,
    private val pcCaps: com.point.core.flow.PcCapsStore,

    private val linkMonitor: com.point.core.flow.LinkMonitor,
    private val pulledFiles: PulledFileFactory,
    private val frames: SelectionFrames,

    // Страна для разбора номеров — вход, а не изменяемая глобаль (#1129).
    private val phoneRegion: com.point.core.flow.PhoneRegion,

    private val aiKeyCheck: com.point.core.flow.AiKeyCheck,

    private val accountStore: com.point.core.flow.AccountStore,

    // Последний успешный круг устройств: без сети экран показывает его, а не «пока вы один» (#1076).
    private val circleStore: com.point.core.flow.CircleStore,

    private val accountClient: com.point.core.flow.AccountClient,

    private val pendingLogins: com.point.core.flow.PendingLoginStore,

    private val deviceKeys: com.point.core.flow.DeviceKeyStore,

    private val browser: com.point.core.flow.BrowserOpener,

    private val sharedTexts: com.point.core.flow.SharedTexts,

    private val memory: com.point.core.flow.PointMemory,
) : ViewModel() {

    private var busyJob: kotlinx.coroutines.Job? = null

    /**
     * Работа, которую человек может отменить.
     *
     * Ссылка на работу ставится до её первого шага. Раньше работа запускалась первой, и шаг,
     * успевший начаться внутри неё, записывал себя раньше внешнего: конец внешнего шага стирал
     * запись целиком- экран занят, а отменять уже нечего (#692).
     */
    private fun trackWork(work: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job {
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY, block = work)
        busyJob = job
        val speaksUp = speakUpWhenSlow()
        job.invokeOnCompletion {
            speaksUp.cancel()
            if (busyJob === job) busyJob = null
        }
        job.start()
        return job
    }

    /**
     * Затянувшаяся работа перестаёт быть тихой (#1128).
     *
     * Тихо работать можно, пока работа быстрая: объект остаётся на экране, и подменять его
     * порталом ради доли секунды нельзя. Но право на тишину даётся по времени, а не по имени
     * способности: «Скан», «Сжать», «В PDF» объявлены быстрыми и на большом файле или на
     * медленном устройстве идут минутами. Список тяжёлых действий здесь не помогает: тяжесть у
     * одного и того же действия зависит от прогона.
     *
     * Тихой работы человеку не хватило — это установлено живым прогоном, а не рассуждением. В
     * коде у неё два знака: список действий гаснет вполовину и перестаёт нажиматься, а
     * назвавшая себя работа пишет строку стадии; оба были заведены и в сборке, на которой
     * написана карточка. В живом прогоне на медленном устройстве до человека не дошёл ни один:
     * запись прогона (AND-068 в `docs/audits/live-review-967-coverage.md`, та же сборка и тот
     * же эмулятор) говорит, что тап не дал ни строки состояния, ни счётчика, ни отмены, и через
     * две, четыре и пять минут экран был тот же. Почему знаки не дошли — не выяснено. Поэтому
     * «объект и так показывает занятость» — не довод, чтобы раздвинуть `QUIET_GRACE_S`: в
     * живом прогоне ни один знак человеку не показался, а счёта секунд и «Отменить» в тишине
     * нет и в коде.
     *
     * Поэтому работа, не уложившаяся в `QUIET_GRACE_S`, показывает себя сама — тем же экраном
     * ожидания с «Идёт N с», стадией и «Отменить», что у облачных: новых механизмов для этого
     * не нужно, снимается только право молчать.
     *
     * Сторож живёт ровно столько, сколько его работа: [trackWork] снимает его, чем бы работа ни
     * кончилась — успехом, ошибкой или отменой. Иначе быстрое действие оставляло бы после себя
     * заведённый будильник и отбирало тишину у следующего, начатого сразу за ним.
     *
     * Поднятый позже начала работы экран говорит, сколько она уже шла (`busySpentS`): иначе
     * счётчик врал бы ровно на подаренную тишину.
     */
    private fun speakUpWhenSlow(): Job = viewModelScope.launch {
        kotlinx.coroutines.delay(com.point.core.flow.QUIET_GRACE_S * 1000L)
        _ui.update {
            if (it.busy != null && it.busyQuiet) {
                it.copy(busyQuiet = false, busySpentS = com.point.core.flow.QUIET_GRACE_S)
            } else {
                it
            }
        }
    }

    private fun raiseBusy(
        title: String,
        network: Boolean = false,
        quiet: Boolean = false,
        cancelable: Boolean = false,
    ) {
        _ui.update {
            it.copy(
                busy = title, busyStage = null, busyNetwork = network, busyQuiet = quiet,

                // Работа начинается сейчас: ждать её человек ещё не начинал (#1128).
                busySpentS = 0,
                busyCancelable = cancelable,
                message = null, messageOutcome = Outcome.NONE, inputPrompt = null,
            )
        }
    }

    private fun owns(voice: Long) = voice == workVoice

    @Volatile private var workVoice = 0L

    private fun claimVoice(): Long = ++workVoice

    private val stack = ArrayDeque<FlowFrame>()

    /**
     * Фоновое обогащение помнит, какой объект оно обогащает (#1060): суд ждёт чтения ровно
     * столько, сколько живёт работа, способная это чтение принести.
     */
    private class EnrichWork(val objectId: String, val job: Job)

    private val enrichJobs = mutableListOf<EnrichWork>()
    private var pendingBubble: Bubble? = null

    private var pendingCloud: (() -> Unit)? = null

    private var pendingCloudScope: com.point.core.flow.CloudScope = com.point.core.flow.CloudScope.MODELS

    private var pendingPreviewBubble: Bubble? = null

    private var selectionLayer: AtomLayer? = null
    private var selectionTransform: FrameTransform? = null
    private var selectionSnap: SnappedSelection? = null

    private var findLayer: AtomLayer? = null
    private var findTransform: FrameTransform? = null

    private val _ui = MutableStateFlow(FlowUiState())
    val ui: StateFlow<FlowUiState> = _ui.asStateFlow()

    private val _recent = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val recent: StateFlow<List<HistoryEntry>> = _recent.asStateFlow()

    private val _crashReport = MutableStateFlow<String?>(null)

    val crashReport: StateFlow<String?> = _crashReport.asStateFlow()

    private val _fromPcCount = MutableStateFlow(0)

    val fromPcCount: StateFlow<Int> = _fromPcCount.asStateFlow()
    private var fromPcEntries: List<com.point.core.flow.PcOutboxEntry> = emptyList()
    private var lastOutboxFetchMs = 0L
    private var lastCircleSyncMs = 0L

    private val _clipboard = MutableStateFlow<String?>(null)

    val clipboard: StateFlow<String?> = _clipboard.asStateFlow()
    private var lastClipboard: String? = null

    private var freshShareArrived = false

    init {
        viewModelScope.launch { _crashReport.value = runCatching { crashLog.pending() }.getOrNull() }
    }

    fun appIcon(packageName: String): androidx.compose.ui.graphics.ImageBitmap? =
        runCatching { appIcons.iconFor(packageName) }.getOrNull()

    fun dismissCrashReport() {
        _crashReport.value = null
        viewModelScope.launch { runCatching { crashLog.clear() } }
    }

    fun restoreJourney() {
        viewModelScope.launch {
            val frames = runCatching { flowSnapshot.load() }.getOrDefault(emptyList())
            if (frames.isEmpty() || freshShareArrived || stack.isNotEmpty()) return@launch

            // Кадр помнит путь в scratch, а scratch не переживает ни завершения flow, ни
            // открытия другого объекта. Копия того же объекта при этом лежит в истории —
            // оттуда файл и поднимается (#812). Прежде кадр либо пропадал целиком, либо
            // оставался с мёртвым путём, и Focus отвечал «не удалось открыть страницу»,
            // хотя объект был на месте.
            val alive = frames.mapNotNull { f ->
                if (!f.kind.isFileBacked) return@mapNotNull f to f.ref
                val own = runCatching { java.io.File(f.ref).isFile }.getOrDefault(false)
                if (own) return@mapNotNull f to f.ref
                val kept = runCatching { history.open(f.id) }.getOrNull()?.uri?.value
                kept?.let { f to it }
            }
            if (alive.isEmpty()) {
                runCatching { flowSnapshot.clear() }
                return@launch
            }

            alive.forEach { (f, path) ->
                pushFrame(
                    PointObject(f.id, f.mime, refFor(f.kind, path),
                        com.point.core.model.ObjectState(f.kind), f.metadata),
                    via = f.viaCapabilityId?.let { CapabilityId(it) },
                    viaTitle = f.viaTitle,
                )
                restoreGraph(f)
            }
        }
    }

    fun onSharedText(text: String) {
        val path = runCatching { sharedTexts.create(text) }.getOrNull()
        if (path == null) {
            _ui.update { it.copy(message = "Не удалось принять текст", messageOutcome = Outcome.FAILED) }
            return
        }
        onShared(
            java.io.File(path).toURI().toString(),
            "text/plain",
            name = com.point.core.flow.textObjectName(text),
        )
    }

    fun openExample(example: ExampleObject) =
        onShared(example.uri, example.mime, name = example.name)

    fun refuseIncoming() {
        _ui.update {
            it.copy(
                busy = null,
                busyStage = null,
                message = "Point не понял, что ему прислали — попробуйте поделиться файлом",
                messageOutcome = Outcome.FAILED,
            )
        }
    }

    fun onShared(
        sourceUri: String,
        mime: String,
        autoAction: String? = null,
        name: String? = null,

        /**
         * Знание, приехавшее вместе с объектом с другого устройства (ADR-0001 §20):
         * потеря знания при переносе — дефект. Вливается единым mergeKnowledge.
         */
        carried: Map<String, String> = emptyMap(),
    ) {
        freshShareArrived = true

        syncCircle()
        val voice = claimVoice()
        val interrupted = makeWayForIncoming()

        // Имя — до копии, и передумать можно (#640): большой файл копируется секундами, и
        // всё это время человек видел голое «Открываю…» без единого способа выйти.
        raiseBusy("Открываю…", cancelable = true)
        trackWork {
            (name ?: runCatching { store.nameOf(sourceUri) }.getOrNull())
                ?.takeIf { it.isNotBlank() }
                ?.let { known -> _ui.update { it.copy(busy = "Открываю $known…") } }
            val obj = runCatching {
                store.clear()
                store.ingest(sourceUri, mime)
            }.getOrNull()?.let { ingested ->
                if (carried.isEmpty()) {
                    ingested
                } else {
                    val (landed, knowledge) = withTravelledText(ingested, carried)

                    // Из чего сделан, чем сделан и каким путём — приезжает вместе с ним
                    // (#1112): результат долгой работы соседа иначе вставал в Graph новой
                    // вещью с происхождением «дано», как будто его прислал человек.
                    com.point.core.flow.withLineage(landed, knowledge).copy(
                        // Тот же объект, а не новая вещь рядом (#811, ADR-0001 §20): если он
                        // уезжал отсюда, он возвращается своим узлом и знание прирастает к
                        // нему. Чужой объект приезжает со своим именем и остаётся собой.
                        id = knowledge[com.point.core.flow.META_ORIGIN_ID]?.takeIf { it.isNotBlank() }
                            ?: landed.id,
                        metadata = com.point.core.flow.mergeKnowledge(
                            landed.metadata,
                            knowledge - com.point.core.flow.PC_EXEC_META,
                            region = phoneRegion.code(),
                        ),
                    )
                }
            }?.let { ingested ->
                if (!name.isNullOrBlank()) {
                    ingested.copy(metadata = ingested.metadata + ("name" to name))
                } else {

                    val fromFile = ingested.metadata["name"]
                    if (!com.point.core.flow.looksMachineName(fromFile)) {
                        ingested
                    } else {
                        val human = com.point.core.flow.stampedObjectName(
                            com.point.core.ui.kindLabel(ingested.state.kind),
                            System.currentTimeMillis(),
                        )
                        ingested.copy(metadata = ingested.metadata + ("name" to human))
                    }
                }
            }
            if (!owns(voice)) return@trackWork
            if (obj == null) {

                _ui.update { it.copy(busy = null, busyStage = null, message = "Не удалось открыть объект", messageOutcome = Outcome.FAILED) }
                return@trackWork
            }
            runCatching { history.record(obj) }
            pushFrame(obj)
            tellInterrupted(interrupted)

            autoAction?.let { id ->
                val cap = CapabilityId(id)
                val title = runCatching { registry.byId(cap).label(obj.state) }.getOrNull()
                if (title == null) {
                    _ui.update {
                        it.copy(message = "Компьютер попросил действие, которого в Point нет", messageOutcome = Outcome.FAILED)
                    }
                    return@let
                }
                onBubble(Bubble("pc", title, cap, obj.state))
            }
        }
    }

    fun onSharedMultiple(sources: List<String>) {
        freshShareArrived = true
        val voice = claimVoice()
        val interrupted = makeWayForIncoming()
        raiseBusy("Открываю…", cancelable = false)
        trackWork {
            val obj = runCatching {
                store.clear()
                store.ingestMultiple(sources)
            }.getOrNull()
            if (!owns(voice)) return@trackWork
            if (obj == null) {

                _ui.update { it.copy(busy = null, busyStage = null, message = "Не удалось открыть объект", messageOutcome = Outcome.FAILED) }
                return@trackWork
            }

            pushFrame(obj)
            tellInterrupted(interrupted)
        }
    }

    fun loadRecent() {

        _ui.update { it.copy(aiKeySet = runCatching { userKeys.keys().mine.isNotEmpty() }.getOrDefault(false)) }
        viewModelScope.launch {
            _recent.value = runCatching { history.recent() }.getOrDefault(emptyList())
        }
        refreshFromPc()
        syncCircle()
    }

    private fun refreshFromPc(force: Boolean = false) {
        val pc = pcLinks.current() ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastOutboxFetchMs < OUTBOX_THROTTLE_MS) return
        lastOutboxFetchMs = now
        viewModelScope.launch {
            runCatching { pcTransport.fetchOutbox(pc) }.getOrNull()?.let { entries ->
                fromPcEntries = entries
                _fromPcCount.value = entries.size
            }
        }
    }

    fun pullFromPc() {
        val pc = pcLinks.current() ?: return
        val voice = claimVoice()

        raiseBusy("Забираю с компьютера…", cancelable = true)
        trackWork {

            val entries = runCatching { pcTransport.fetchOutbox(pc) }.getOrNull().orEmpty()
            if (!owns(voice)) return@trackWork
            if (entries.isEmpty()) {
                fromPcEntries = emptyList()
                _fromPcCount.value = 0
                _ui.update { it.copy(busy = null) }
                return@trackWork
            }
            val pulled = entries.map { entry ->
                val name = entry.meta["name"] ?: "объект"
                val path = pulledFiles.create("${entry.id}-$name")
                val ok = runCatching { pcTransport.downloadOutboxFile(pc, entry.id, path) }.getOrDefault(false)
                Triple(entry, path, ok)
            }
            if (!owns(voice)) return@trackWork
            if (pulled.any { !it.third }) {
                // Причина называется настоящая (#1018): письмо тянется с сервера, и
                // выключенный компьютер тут ни при чём.
                _ui.update { it.copy(busy = null, busyStage = null, message = com.point.core.flow.PC_PULL_FAILED_TEXT, messageOutcome = Outcome.FAILED) }
                return@trackWork
            }
            // Просьба исполнить — не переезд объекта (ADR-0001 §7). Дом такого объекта
            // остался на компьютере: телефон делает работу и отсылает результат домой, а
            // не открывает вещь у себя и не оставляет сделанное на своей стороне.
            val (asked, arrived) = pulled.partition {
                !it.first.meta[com.point.core.flow.PcExecFields.REQUEST].isNullOrBlank()
            }
            asked.forEach { (entry, path, _) -> executeForPc(pc, entry.meta, path) }

            // Просьба и знание объекта не теряются в пачке (#1090). Общий набор передать
            // ими не может: у него один экран на всех. Поэтому объект со своей просьбой или
            // со своим знанием берётся отдельно, а остальные ждут в очереди компьютера —
            // не подтверждёнными, то есть не потерянными.
            val (own, plain) = arrived.partition { hasOwnErrand(it.first.meta) }
            val taken = mutableListOf<Triple<com.point.core.flow.PcOutboxEntry, String, Boolean>>()
            taken += asked

            when {
                own.isNotEmpty() -> {
                    val one = own.first()
                    taken += one
                    if (own.size == 1) taken += plain
                    onShared(
                        "file://${one.second}",
                        one.first.meta["mime"] ?: "application/octet-stream",
                        autoAction = one.first.meta["pc.action"]?.takeIf { it.isNotBlank() },
                        name = one.first.meta["name"],
                        carried = one.first.meta - PC_SERVICE_META,
                    )
                }
                plain.size == 1 -> {
                    taken += plain
                    onShared(
                        "file://${plain[0].second}",
                        plain[0].first.meta["mime"] ?: "application/octet-stream",
                        name = plain[0].first.meta["name"],
                        carried = plain[0].first.meta - PC_SERVICE_META,
                    )
                }
                plain.isNotEmpty() -> {
                    taken += plain
                    onSharedMultiple(plain.map { "file://${it.second}" })
                }
                else -> _ui.update { it.copy(busy = null, busyStage = null) }
            }

            // Подтверждается только то, что и правда разобрано: неподтверждённое остаётся
            // в очереди компьютера и приезжает следующим заходом.
            taken.forEach { (entry, _, _) ->
                runCatching { pcTransport.ackOutbox(pc, entry.id) }
                    .recoverCatching { pcTransport.ackOutbox(pc, entry.id) }
            }
            val waiting = pulled.filterNot { left -> taken.any { it.first.id == left.first.id } }
            fromPcEntries = waiting.map { it.first }
            _fromPcCount.value = waiting.size
        }
    }

    /**
     * Просьба, привязанная к этому письму (#1090).
     *
     * Общий набор просьбу передать не может: у него один экран на всех, и `pc.action`
     * терялся вместе с обещанием компьютера. Поэтому объект с просьбой берётся отдельно, а
     * остальные ждут в очереди — не подтверждёнными, то есть не потерянными.
     */
    private fun hasOwnErrand(meta: Map<String, String>): Boolean = !meta["pc.action"].isNullOrBlank()

    fun hideFromPc() {
        _fromPcCount.value = 0
    }

    /** Убрать одну запись (#543): список после этого перечитывается с диска, а не правится на глаз. */
    fun removeFromHistory(entryId: String) {
        viewModelScope.launch {
            runCatching { history.remove(entryId) }
            _recent.value = runCatching { history.recent() }.getOrDefault(emptyList())
        }
    }

    /**
     * «Забыть всё» забывает всё, что обещано (#1026).
     *
     * Обещание на экране — «уйдут все записи и всё, что Point о них узнал». Убирался же
     * только перечень: сам объект, вычитанное из него знание и переписка с моделью
     * оставались на устройстве. Теперь спрашивается вся память об объектах разом
     * (`PointMemory`), а человеку сказано, чего именно он лишился.
     *
     * Открытый разбор закрывается вместе с ней: его копия только что стёрта, и оставить
     * человека на объекте, которого больше нет, — тот самый призрак, ради которого всё
     * и затевалось.
     */
    fun clearHistory() {
        viewModelScope.launch {
            val gone = runCatching { memory.forgetAll() }.getOrNull()
            _recent.value = emptyList()
            makeWayForIncoming()

            _ui.update {
                it.copy(
                    busy = null,
                    busyStage = null,
                    message = com.point.core.flow.forgottenText(gone),
                    messageOutcome = Outcome.DONE,
                )
            }

            // Обзор «Что Point помнит» показывает то же самое хранилище (#821): забыли —
            // и в настройках сразу видно, что помнить нечего.
            refreshWhatWorks()
        }
    }

    fun offerClipboard(text: String?) {
        val t = text?.trim().orEmpty()
        _clipboard.value = t.takeIf { it.isNotBlank() && it.length <= MAX_CLIP && it != lastClipboard }
    }

    fun refreshClipboard(reader: () -> String?) {
        if (hasFlow()) return
        offerClipboard(reader())
    }

    fun dismissClipboard() {
        lastClipboard = _clipboard.value
        _clipboard.value = null
    }

    fun openFromHistory(entry: HistoryEntry) {
        freshShareArrived = true
        val voice = claimVoice()

        raiseBusy("Открываю…", cancelable = true)
        trackWork {
            val obj = runCatching { history.open(entry.id) }.getOrNull()
            if (!owns(voice)) return@trackWork
            if (obj == null) {
                _ui.update { it.copy(busy = null, busyStage = null, message = "Объект недоступен", messageOutcome = Outcome.FAILED) }
                return@trackWork
            }
            runCatching { store.clear() }
            cancelEnrichment()
            stack.clear()
            pushFrame(obj)
        }
    }

    /**
     * Объект, каким его видит исполнитель- тот же объект, но с указанным человеком Focus
     * (ADR-0001 §10). Focus не подменяет объект и не создаёт новый.
     */
    private fun focused(): PointObject? {
        val frame = stack.lastOrNull() ?: return null
        val focus = frame.focus ?: return frame.obj
        return frame.obj.copy(metadata = com.point.core.flow.withFocus(frame.obj.metadata, focus))
    }

    fun onBubble(bubble: Bubble) {
        val top = focused() ?: return

        // Режим закрыл дорогу наружу (#943): спрашивать согласие незачем — согласие тут не
        // поможет. Дверь была видна и нажата, значит человек услышит причину и то, где она
        // меняется, а не молчаливое «сделали меньше обещанного».
        wayOutClosed(bubble.capabilityId)?.let { reason ->
            _ui.update { it.copy(message = reason, messageOutcome = Outcome.FAILED) }
            return
        }

        // Действие само знает, что сейчас не сработает (#1022): человек слышит причину по
        // тапу, а не после экрана согласия, за которым всё равно отказ (линза #1003).
        wontWorkNow(bubble.capabilityId, top.state)?.let { reason ->
            _ui.update { it.copy(message = reason, messageOutcome = Outcome.FAILED) }
            return
        }

        if (com.point.core.flow.labelNeedsKey(bubble.title)) {
            openKeyScreen(
                KeyErrand(
                    action = com.point.core.flow.labelWithoutKeyNote(bubble.title),
                    objectName = top.metadata["name"] ?: com.point.core.ui.kindLabel(top.state.kind),
                ),
            )
            return
        }
        if (bubble.capabilityId == OpenInCapability.ID) {

            showAppPicker(top)
            return
        }
        if (bubble.capabilityId == AiCapability.ID) {

            requireCloudConsent { openChat(top) }
            return
        }
        if (bubble.capabilityId == FindCapability.ID) {

            openFind()
            return
        }
        if (isCloud(bubble.capabilityId)) {

            requireCloudConsent(bubble.capabilityId) { maybePreview(bubble, top) }
            return
        }
        maybePreview(bubble, top)
    }

    private fun maybePreview(bubble: Bubble, top: PointObject) {
        val voice = claimVoice()
        runningStep = bubble.title
        raiseBusy(
            bubble.title,
            network = isCloud(bubble.capabilityId),
            quiet = isQuietAction(bubble.capabilityId),
            cancelable = true,
        )
        trackWork {

            val realizer = runCatching { resolver.realizerFor(bubble.capabilityId, top.state) }.getOrNull()
            if (!owns(voice)) return@trackWork
            if (realizer == null) {
                _ui.update {
                    it.copy(
                        busy = null,
                        busyStage = null,
                        message = com.point.core.flow.NO_WAY_HERE_REASON,
                        messageOutcome = Outcome.FAILED,
                    )
                }
                return@trackWork
            }
            val preview = runCatching { realizer.preview(top) }.getOrNull()
            if (!owns(voice)) return@trackWork
            if (preview == null) {

                // Само действие идёт этой же работой. Отдельный запуск изнутри уводил отслеживание
                // на себя, а конец подготовки стирал его- и «Отменить» переставало действовать (#692).
                runCatching { sensory.tap() }

                // Кто исполнил — часть добытого знания (#1127): тот же шов, что и у
                // исследований, только исполнитель здесь уже выбран строчкой выше.
                runAction(bubble, voice, top) { obj -> realizer.perform(obj, null).knownBy(obj, realizer.meta.actor) }
            } else {
                pendingPreviewBubble = bubble
                _ui.update { it.copy(busy = null, busyStage = null, preview = preview) }
            }
        }
    }

    // Стражника повторного облака больше нет (#1176, решение владельца дословно):
    // «если повтор добавляет ценность, это не про yolo. а если нет то человек не будет
    // 10 раз тапать одно и то же. убери его вообще». Виток спирали ценен сам: бриф целит
    // в незакрытое, исполнитель ротируется, согласие меняет знание (#668 отменено).

    fun confirmPreview() {
        val bubble = pendingPreviewBubble ?: return
        val top = focused() ?: return
        pendingPreviewBubble = null
        _ui.update { it.copy(preview = null) }
        runOnObject(bubble, top)
    }

    fun cancelPreview() {
        pendingPreviewBubble = null
        _ui.update { it.copy(preview = null) }
    }

    fun openTopObject() {
        val top = stack.lastOrNull()?.obj ?: return
        val cap = com.point.core.model.CapabilityId("open")
        val bubble = runCatching {
            Bubble("open", registry.byId(cap).label(top.state), cap, top.state)
        }.getOrNull() ?: return
        onBubble(bubble)
    }

    fun openSelection() {
        val top = stack.lastOrNull()?.obj ?: return

        val atomsRef = top.metadata[META_OCR_ATOMS_REF]
        viewModelScope.launch {
            val loaded = withContext(ioDispatcher) {
                runCatching {

                    // Слой слов — подспорье, а не условие: его файл мог уйти вместе со
                    // scratch, пока кадр оставался открытым (#812). Прежде исключение отсюда
                    // роняло всю загрузку, и обводка отвечала «не удалось открыть страницу»
                    // даже там, где сама картинка была на месте.
                    val layer = atomsRef
                        ?.let { runCatching { AtomCodec.decode(File(it).readText()) }.getOrNull() }
                        ?: AtomLayer(emptyList())

                    // Файл объекта мог уйти вместе со scratch, пока кадр оставался открытым
                    // (#812). Копия того же объекта живёт в истории — обводке хватит её,
                    // и человеку не приходится узнавать о пропаже тапом.
                    val path = top.uri.value.takeIf { File(it).isFile }
                        ?: runCatching { history.open(top.id) }.getOrNull()?.uri?.value
                        ?: top.uri.value
                    frames.frame(path, SELECTION_MAX_PX)?.let { frame ->
                        Triple(layer, frame.transform, frame.bitmap.asImageBitmap())
                    }
                }.getOrNull()
            }
            if (loaded == null) {
                _ui.update { it.copy(message = "Не удалось открыть страницу для выделения", messageOutcome = Outcome.FAILED) }
                return@launch
            }
            selectionLayer = loaded.first
            selectionTransform = loaded.second
            selectionSnap = null
            _ui.update { it.copy(selection = SelectionUi(image = loaded.third, layer = loaded.first)) }
        }
    }

    /**
     * ✓ на экране Focus: показанное становится Focus объекта и экран закрывается
     * (ТЗ владельца 10.08.2026 — «нажал → Focus исчез → Point получает region»).
     *
     * Каждое место прилипает по правилу своего инструмента и не выше потолка (#1037, #1039):
     * кисть — к задетым строкам, обводка — как нарисована. Одни и те же прилипшие места
     * уходят в Focus и для «Замазать», и для «Взять фрагмент», и для перечитывания области.
     */
    fun onSelectRegion(parts: List<FocusPart>) {
        val layer = selectionLayer ?: return
        val transform = selectionTransform ?: return
        if (parts.isEmpty()) return
        selectionSnap = layer.snapSelection(parts.map { it.copy(box = transform.toRaw(it.box)) })
        focusOnSelection()
    }

    /**
     * «Смотреть сюда»- Focus как сигнал- ADR-0001 §10.
     *
     * Объект остаётся прежним со всем уже накопленным знанием. Самостоятельный объект из
     * области рождает действие «Взять фрагмент» (TakeFragmentCapability, #742) — не экран.
     */
    fun focusOnSelection() {
        val frame = stack.lastOrNull() ?: return
        val snap = selectionSnap ?: return
        focusOn(
            com.point.core.flow.Focus(
                objectId = frame.obj.id,
                region = snap.region,
                atomIds = snap.ids,
                text = snap.text.takeIf { it.isNotBlank() },
                parts = snap.parts,
            ),
        )
        _ui.update { it.copy(focusPreview = focusPreviewOf(snap.region)) }
        closeSelection()
    }

    /**
     * Показанная область картинкой (#757): Focus меняет поведение всех следующих действий,
     * и человек обязан видеть не только слова «Смотрю сюда», но и саму область.
     */
    private fun focusPreviewOf(region: Box?): androidx.compose.ui.graphics.ImageBitmap? {
        val page = _ui.value.selection?.image ?: return null
        val transform = selectionTransform ?: return null
        val at = transform.toUpright(region ?: return null)
        return runCatching {
            val source = page.asAndroidBitmap()
            val left = at.left.toInt().coerceIn(0, source.width - 1)
            val top = at.top.toInt().coerceIn(0, source.height - 1)
            val width = (at.right.toInt() - left).coerceIn(1, source.width - left)
            val height = (at.bottom.toInt() - top).coerceIn(1, source.height - top)
            android.graphics.Bitmap.createBitmap(source, left, top, width, height).asImageBitmap()
        }.getOrNull()
    }

    fun focusOn(focus: com.point.core.flow.Focus) {
        val index = stack.lastIndex
        val frame = stack.getOrNull(index) ?: return
        val refreshed = frame.copy(focus = focus)
        stack[index] = refreshed.copy(bubbles = registry.bubblesFor(graphOf(refreshed)))
        _ui.update { withoutAreaAnswer(it).copy(frame = stack[index]) }
        persistJourney()

        // Focused-проход- контекст области захватывается сейчас, в metadata копии объекта.
        // Поздний результат останется знанием этой области, какой бы Focus ни был текущим.
        enrichInBackground(
            refreshed.obj.copy(metadata = com.point.core.flow.withFocus(refreshed.obj.metadata, focus)),
        )
    }

    /**
     * Человек показал область — и тем самым спросил «что здесь» (#1000).
     *
     * Point смотрел и не находил ничего, а экран после области выглядел ровно как до неё:
     * человек не отличал «посмотрел и не нашёл» от «не посмотрел вовсе», хотя ответ в графе
     * уже был. Прошенное получает слово в момент ответа — в отличие от незапрошенного
     * «не нашлось», которое экран не показывает (#1016, решение владельца).
     *
     * Говорится, только пока стоит та же область: ответ про прежнюю область под новой был бы
     * неправдой, а без области вопроса уже нет.
     *
     * И только в тишину, оставшуюся от вопроса: пока Point смотрел, человек не ждал молча —
     * он нажимал действия и слышал о них слово. Слово о его шаге сказано ПОСЛЕ вопроса и
     * потому свежее ответа: перебивать его фоновым «в области ничего» нельзя. [saidBefore] —
     * то, что стояло на экране в момент вопроса; изменилось — значит Point успел сказать
     * человеку что-то ещё.
     */
    private fun answerAskedArea(asked: PointObject, saidBefore: String?) {
        val askedFocus = com.point.core.flow.focusOf(asked.metadata, asked.id) ?: return
        val frame = stack.lastOrNull()?.takeIf { it.obj.id == asked.id } ?: return
        val standing = frame.focus?.let { com.point.core.flow.focusScope(it) } ?: return
        if (standing != com.point.core.flow.focusScope(askedFocus)) return
        if (!com.point.core.flow.nothingFoundIn(frame.obj.metadata, askedFocus)) return
        _ui.update {
            if (it.frame?.obj?.id != asked.id || it.message != saidBefore) {
                it
            } else {

                // Ответ, а не исход: «не нашлось» — знание, и упрёком его помечать нельзя.
                it.copy(message = com.point.core.ui.FOCUS_NOTHING_FOUND, messageOutcome = Outcome.NONE)
            }
        }
    }

    /** Вопрос областью снят или сменился — прежний ответ про область больше не к месту (#1000). */
    private fun withoutAreaAnswer(state: FlowUiState): FlowUiState =
        if (state.message == com.point.core.ui.FOCUS_NOTHING_FOUND) {
            state.copy(message = null, messageOutcome = Outcome.NONE)
        } else {
            state
        }

    /**
     * Мир снаружи сменился под открытым экраном (#758).
     *
     * Причина «нет интернета» появлялась только при следующем открытии объекта: список
     * действий собирается один раз, а состояние сети спрашивается в момент сборки. Человек
     * упирался в отказ после тапа — ровно то, ради чего правило и делалось. Симметрично и
     * обратное: сеть вернулась, а подпись висела до перезахода.
     *
     * Пересборка — тот же самый ход, каким пространство действий расширяется от нового
     * знания: изменилось не знание об объекте, а то, чем его можно обработать.
     */
    /**
     * Сеть пропала или вернулась — об этом говорит система, со своего потока (#1117).
     *
     * Разбор живёт в одном потоке, как и всё остальное состояние экрана. Прежде этот вызов
     * правил его прямо оттуда, откуда пришёл, — и Point падал на смене сети с
     * `IndexOutOfBounds`, теряя объект человека. Никакой второй машины состояний для этого
     * не нужно: работа возвращается в ту же очередь, что и любое другое изменение разбора.
     */
    fun networkChanged() {
        viewModelScope.launch { rebuildForNetwork() }
    }

    private fun rebuildForNetwork() {
        val index = stack.lastIndex
        val frame = stack.getOrNull(index) ?: return
        val rebuilt = registry.bubblesFor(graphOf(frame))
        if (rebuilt == frame.bubbles) return
        stack[index] = frame.copy(bubbles = rebuilt)
        _ui.update { it.copy(frame = stack[index]) }
    }

    fun clearFocus() {
        val index = stack.lastIndex
        val frame = stack.getOrNull(index) ?: return
        if (frame.focus == null) return
        val refreshed = frame.copy(focus = null)
        stack[index] = refreshed.copy(bubbles = registry.bubblesFor(graphOf(refreshed)))
        _ui.update { withoutAreaAnswer(it).copy(frame = stack[index], focusPreview = null) }
        persistJourney()
    }

    fun closeSelection() {
        selectionLayer = null
        selectionTransform = null
        selectionSnap = null
        _ui.update { it.copy(selection = null) }
    }

    fun openFind() {
        val top = stack.lastOrNull()?.obj ?: return

        val atomsRef = top.metadata[META_OCR_ATOMS_REF]
        if (atomsRef == null) {
            _ui.update { it.copy(message = "Страница ещё не прочитана — искать не в чем", messageOutcome = Outcome.FAILED) }
            return
        }
        viewModelScope.launch {
            val loaded = withContext(ioDispatcher) {
                runCatching {
                    val layer = AtomCodec.decode(File(atomsRef).readText())
                    frames.frame(top.uri.value, SELECTION_MAX_PX)?.let { frame ->
                        Triple(layer, frame.transform, frame.bitmap.asImageBitmap())
                    }
                }.getOrNull()
            }
            if (loaded == null) {
                _ui.update { it.copy(message = "Не удалось открыть страницу для поиска", messageOutcome = Outcome.FAILED) }
                return@launch
            }
            findLayer = loaded.first
            findTransform = loaded.second
            _ui.update { it.copy(find = FindUi(image = loaded.third)) }
        }
    }

    fun onFindQuery(query: String) {
        val layer = findLayer ?: return
        val transform = findTransform ?: return
        val found = layer.findOnPage(query)
        val asked = com.point.core.flow.isSearchable(query)
        _ui.update { state ->
            val find = state.find ?: return@update state
            state.copy(
                find = find.copy(
                    highlights = found.map { transform.toUpright(it.region) },
                    status = if (asked) foundOnPageLabel(found.size) else null,
                ),
            )
        }
    }

    fun closeFind() {
        findLayer = null
        findTransform = null
        _ui.update { it.copy(find = null) }
    }

    private fun runOnObject(bubble: Bubble, top: PointObject) {
        raiseBusy(
            bubble.title,
            network = isCloud(bubble.capabilityId),
            quiet = isQuietAction(bubble.capabilityId),
            cancelable = true,
        )
        dispatch(bubble, top) { obj -> performed(bubble.capabilityId, obj, null) }
    }

    /**
     * Почему это действие сейчас наружу не пойдёт — словами режима (#943).
     *
     * Спрашивается то же правило, по которому отказывает сама цепочка: пускает ли режим
     * наружу вообще.
     */
    private fun wayOutClosed(id: CapabilityId): String? {
        if (!isCloud(id)) return null

        // Своё устройство — не «наружу»: «На компьютер» режим не закрывает.
        if (runCatching { registry.byId(id).meta.localOnly }.getOrDefault(false)) return null
        val level = runCatching { cloudPrivacy.level() }
            .getOrDefault(com.point.core.flow.PrivacyLevel.DEFAULT)
        if (com.point.core.flow.anyoneAllowedAt(level)) return null
        return com.point.core.flow.chainClosedBy(level)
    }

    /**
     * Почему это действие сейчас не сработает — словами самого действия (#1022).
     *
     * Спрашивается у способности: чего не хватает, знает она, а не экран. Молчание —
     * обычный ход, и тогда всё идёт как прежде.
     */
    private fun wontWorkNow(id: CapabilityId, state: ObjectState): String? =
        runCatching { registry.byId(id).wontWorkNow(state) }.getOrNull()

    private fun isCloud(id: CapabilityId) =
        runCatching { registry.byId(id).meta.network }.getOrDefault(false) ||
            runCatching { resolver.leavesDevice(id) }.getOrDefault(false)

    /**
     * Объект уходит отсюда на компьютер (#650): либо «На компьютер», либо действие,
     * которое исполняет компьютер, — в обоих случаях объект физически покидает телефон.
     */
    private fun leavesForPc(id: CapabilityId) =
        id == com.point.executors.PcCapability.ID || id.value.startsWith("pc-do:")

    private fun isQuietAction(id: CapabilityId) =
        runCatching { quietWork(registry.byId(id).meta) }.getOrDefault(false)

    private fun requireCloudConsent(
        capabilityId: com.point.core.model.CapabilityId? = null,
        onGranted: () -> Unit,
    ) {
        val id = capabilityId ?: com.point.core.model.CapabilityId("ai")
        val scope = com.point.core.flow.cloudScopeOf(id)
        viewModelScope.launch {
            if (runCatching { consent.allowed(scope) }.getOrDefault(false)) {
                onGranted()
            } else {
                pendingCloud = onGranted
                pendingCloudScope = scope
                _ui.update {
                    it.copy(
                        cloudConsent = true,
                        cloudDestination = com.point.core.flow.cloudDestination(id, chosenAiService()),
                        cloudTitle = com.point.core.flow.cloudAskTitle(scope),
                        cloudConfirm = com.point.core.flow.cloudAskConfirm(scope),
                    )
                }
            }
        }
    }

    private fun chosenAiService(): String? = runCatching {
        val mine = userKeys.keys().mine.firstOrNull() ?: return@runCatching null
        com.point.core.flow.AI_PROVIDERS.firstOrNull { it.id == mine.providerId }?.name
    }.getOrNull()

    fun onItem(item: PointObject) {
        if (stack.lastOrNull()?.obj?.state?.kind != ObjectKind.COLLECTION) return
        pushFrame(item)
    }

    /**
     * Перестановка страниц набора — знание набора, а не имя файла (#1207).
     *
     * Несколько фото одной накладной приходят в порядке съёмки, и именно этот порядок
     * нужен «Сканировать в PDF» и «В Excel». Человек двигает страницу на шаг — список на
     * экране меняется сразу, а порядок ложится в сам объект-набор тем же merge-путём, что и
     * любое другое знание (`applyEnrichment`): он переживёт возврат к набору и «Недавнее».
     *
     * Только в набор: `landFindings` разносит знание и в исходник кадра, а порядок страниц
     * разложенного PDF — знание набора страниц, не документа. В PDF ему делать нечего.
     */
    fun moveItem(item: PointObject, by: Int) {
        val index = stack.lastIndex
        val top = stack.getOrNull(index) ?: return
        if (top.obj.state.kind != ObjectKind.COLLECTION) return
        val from = top.items.indexOfFirst { it.id == item.id }
        val to = from + by
        if (from < 0 || to !in top.items.indices) return
        val moved = top.items.toMutableList().apply { add(to, removeAt(from)) }
        val refreshed = top.copy(items = moved)
        stack[index] = refreshed
        _ui.update { if (it.frame?.obj?.id == top.obj.id) it.copy(frame = refreshed) else it }
        applyEnrichment(
            top.obj,
            EnrichmentUpdate(
                features = emptySet(),
                metadata = mapOf(
                    com.point.core.flow.META_COLLECTION_ORDER to
                        com.point.core.flow.collectionOrderValue(moved.mapNotNull { it.metadata["name"] }),
                ),
                running = top.enriching,
            ),
        )
        // Карточка «Недавнего» несёт знание набора — порядок обязан дойти и до неё.
        stack.getOrNull(index)?.obj?.let { set -> viewModelScope.launch { runCatching { history.update(set) } } }
    }

    /** Миниатюра страницы набора — тем же чтением, что и превью снимка (#1207). */
    suspend fun itemPreview(item: PointObject): androidx.compose.ui.graphics.ImageBitmap? {
        if (item.state.kind != ObjectKind.IMAGE) return null
        return withContext(ioDispatcher) {
            runCatching { Bitmaps.decodeThumbnail(item.uri.value, ITEM_THUMB_PX)?.asImageBitmap() }.getOrNull()
        }
    }

    fun onFound(found: PointObject) {
        if (stack.lastOrNull()?.found?.none { it.id == found.id } != false) return
        pushFrame(found)
    }

    fun submitAmendment(text: String) {
        val bubble = pendingBubble ?: return
        val top = focused() ?: return
        pendingBubble = null
        raiseBusy(
            bubble.title,
            network = isCloud(bubble.capabilityId),
            quiet = isQuietAction(bubble.capabilityId),
            cancelable = true,
        )
        _ui.update { it.copy(inputSuggestions = emptyList(), needsImage = null) }
        dispatch(bubble, top) { obj -> performed(bubble.capabilityId, obj, text) }
    }

    fun cancelInput() {
        pendingBubble = null
        _ui.update { it.copy(inputPrompt = null, inputSuggestions = emptyList(), needsImage = null, busy = null) }
    }

    /**
     * Разговор живёт своим держателем (#833): здесь остаются только двери, которыми его
     * открывает экран. Что делать с идущим вопросом при уходе и как забрать ответ — знает
     * `ChatFlow`, и правки этих правил больше не трогают этот файл.
     */
    private val chatFlow by lazy {
        ChatFlow(
            talk = talk,
            scope = viewModelScope,
            chat = { _ui.value.chat },
            setChat = { chat, open ->
                _ui.update {
                    if (open) {
                        it.copy(
                            chat = chat,
                            chatOpen = true,
                            busy = null,
                            inputPrompt = null,
                            message = null,
                            messageOutcome = Outcome.NONE,
                        )
                    } else {
                        it.copy(chat = chat, chatOpen = false)
                    }
                }
            },
            labelOf = { id, state -> runCatching { registry.byId(id).label(state) }.getOrNull() },
            iconOf = { id -> runCatching { registry.byId(id).icon }.getOrDefault("ai") },
            runBubble = ::onBubble,
            keepAnswer = { obj -> pushFrame(obj, viaTitle = "Ответ AI") },
            onSuccess = { runCatching { sensory.success() } },
            onFailure = { why ->
                _ui.update { it.copy(message = why, messageOutcome = Outcome.FAILED) }
            },
        )
    }

    private fun openChat(obj: PointObject) = chatFlow.open(obj)

    fun closeChat() = chatFlow.close()

    fun sendChatMessage(text: String) = chatFlow.send(text)

    fun runChatOffer() = chatFlow.runOffer()

    fun cancelChatMessage() = chatFlow.cancelMessage()

    fun takeChatAnswer() = chatFlow.takeAnswer()

    fun openKeySettings() {
        openKeyScreen(errand = null)
        refreshWhatWorks()
    }

    /**
     * Что уже работает и негде было увидеть (#821): точки входа и память.
     * Читается при открытии настроек — на первый экран это не влияет.
     */
    private fun refreshWhatWorks() {
        viewModelScope.launch {
            val footprint = runCatching { memory.footprint() }.getOrNull()
            _ui.update { it.copy(memory = footprint) }
        }
    }


    private fun openKeyScreen(errand: KeyErrand?) {

        _ui.update {

            val refusal = keyOfferLabel(it.message) != null
            it.copy(
                keyScreen = aiKeysScreen(), busy = null,

                keyScreenNote = it.message.takeIf { _ -> refusal },

                keyErrand = errand,
                message = it.message.takeIf { _ -> refusal },
                messageOutcome = if (refusal) it.messageOutcome else Outcome.NONE,
                inputPrompt = null,

                keyChecking = null,
                keyVerdict = null,
                keyVerdictFor = null,
                aiKeySet = runCatching { userKeys.keys().mine.isNotEmpty() }.getOrDefault(false),
                soundEnabled = runCatching { sensorySettings.isSoundEnabled() }.getOrDefault(true),
                privacyLevel = runCatching { cloudPrivacy.level() }
                    .getOrDefault(com.point.core.flow.PrivacyLevel.DEFAULT),
            )
        }
        viewModelScope.launch { refreshCloudConsent() }
    }

    /**
     * Все известные сервисы списком, в том порядке, в каком Point к ним
     * обращается: ключ, что умеет и последний факт (#699).
     */
    private fun aiKeysScreen(): AiKeysScreen {
        val keys = runCatching { userKeys.keys() }.getOrDefault(com.point.core.flow.UserAiKeys.NONE)
        val facts = runCatching { aiFacts.all() }.getOrDefault(emptyMap())
        val ours = runCatching { builtInKeys.have() }.getOrDefault(emptySet())
        val now = System.currentTimeMillis()
        return AiKeysScreen(
            keys = keys,
            services = aiServiceLines(keys, ours, facts, now),
            checkedLine = aiCheckedLine(facts, now),
        )
    }

    private fun refreshKeyScreen() {
        _ui.update { if (it.keyScreen == null) it else it.copy(keyScreen = aiKeysScreen()) }
    }

    /** Одна дешёвая проверка одного сервиса — только по тапу человека. */
    fun checkAiKey(key: UserAiKey) {
        if (key.apiKey.isBlank() || _ui.value.keyChecking != null) return
        _ui.update { it.copy(keyChecking = key.providerId, keyVerdict = null, keyVerdictFor = null) }
        viewModelScope.launch {
            val verdict = probe(key.providerId, aiCall(key))
            if (verdict is com.point.core.flow.KeyVerdict.Works) {
                runCatching { userKeys.save(key.copy(savedAt = System.currentTimeMillis())) }
                syncAccountSettings(justChanged = true)
            }
            _ui.update {
                it.copy(
                    keyChecking = null,
                    keyVerdict = verdict,
                    keyVerdictFor = key.providerId,
                    keyScreen = if (it.keyScreen == null) null else aiKeysScreen(),
                    aiKeySet = it.aiKeySet || verdict is com.point.core.flow.KeyVerdict.Works,
                )
            }
        }
    }

    /**
     * «Проверить все» — по одному дешёвому запросу к каждому сервису, у которого
     * есть ключ. Фоном Point не проверяет ничего.
     */
    fun checkAllAiKeys() {
        if (_ui.value.keyChecking != null) return
        _ui.update { it.copy(keyChecking = CHECK_ALL_SERVICES, keyVerdict = null, keyVerdictFor = null) }
        viewModelScope.launch {
            val keys = runCatching { userKeys.keys() }.getOrDefault(com.point.core.flow.UserAiKeys.NONE)
            for (provider in com.point.core.flow.AI_PROVIDERS) {
                val mine = keys.of(provider.id)
                val key = mine?.apiKey ?: runCatching { builtInKeys.key(provider.id) }.getOrDefault("")
                if (key.isBlank()) continue
                val call = mine?.let(::aiCall) ?: com.point.core.flow.UserAiConfig(
                    apiKey = key,
                    baseUrl = provider.baseUrl,
                    model = provider.models.substringBefore(','),
                )
                probe(provider.id, call)
                refreshKeyScreen()
            }
            keys.of(com.point.core.flow.OWN_SERVICE_ID)?.let {
                probe(com.point.core.flow.OWN_SERVICE_ID, aiCall(it))
            }
            _ui.update { it.copy(keyChecking = null, keyScreen = if (it.keyScreen == null) null else aiKeysScreen()) }
        }
    }

    private suspend fun probe(
        providerId: String,
        call: com.point.core.flow.UserAiConfig,
    ): com.point.core.flow.KeyVerdict {
        val probe = runCatching { aiKeyCheck.check(call) }
            .getOrElse {
                com.point.core.flow.KeyProbe(
                    error = com.point.core.flow.withoutKey(it.message.orEmpty(), call.apiKey),
                )
            }
        runCatching { aiFacts.remember(providerId, aiOutcomeOfStatus(probe.status)) }
        return com.point.core.flow.keyVerdict(probe)
    }

    fun forgetAiKey(providerId: String) {
        viewModelScope.launch {
            runCatching { userKeys.forget(providerId) }
            _ui.update {
                it.copy(
                    keyScreen = if (it.keyScreen == null) null else aiKeysScreen(),
                    keyVerdict = null,
                    keyVerdictFor = null,
                    keyChecking = null,
                    aiKeySet = runCatching { userKeys.keys().mine.isNotEmpty() }.getOrDefault(false),

                    // Экран ключей показывает исход своей строкой — карточка
                    // поверх неё была бы вторым ответом на тот же вопрос.
                    message = if (it.keyScreen == null) "Ключ забыт" else it.message,
                    messageOutcome = if (it.keyScreen == null) Outcome.DONE else it.messageOutcome,
                )
            }
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { sensorySettings.setSoundEnabled(enabled) }
            _ui.update { it.copy(soundEnabled = enabled) }
            syncAccountSettings(justChanged = true)
        }
    }

    fun setPrivacyLevel(level: com.point.core.flow.PrivacyLevel) {
        viewModelScope.launch {
            runCatching { cloudPrivacy.setLevel(level) }

            // Человек выбрал беречь — режим «не спрашивай» снимается сам (#795). Иначе
            // экран показывал бы выбранный уровень, а работал бы по другому: выбор,
            // который ничего не меняет, — обман.
            if (level != com.point.core.flow.PrivacyLevel.FREE_FIRST) setYoloEnabled(false)
            _ui.update { it.copy(privacyLevel = level) }
            syncAccountSettings(justChanged = true)
        }
    }

    /**
     * Режим «делай лучшее и не спрашивай» (#795). Выключение возвращает и вопрос перед
     * облаком, и прежде выбранный уровень отправки: режим их не стирал.
     */
    fun setYoloEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { yolo.setEnabled(enabled) }
            refreshCloudConsent()
            _ui.update {
                it.copy(
                    yoloEnabled = enabled,
                    privacyLevel = runCatching { cloudPrivacy.level() }.getOrDefault(it.privacyLevel),
                )
            }
        }
    }

    fun closeKeySettings() {
        _ui.update {
            it.copy(
                keyScreen = null, keyScreenNote = null, keyErrand = null,
                keyVerdict = null, keyVerdictFor = null, keyChecking = null,
            )
        }
        refreshTopBubbles()
    }

    /**
     * Вход обоих решений — всё состояние, а не одна форма объекта- ADR-0001 §14.
     */
    private fun graphOf(frame: FlowFrame, obj: PointObject = frame.obj): com.point.core.flow.GraphState {
        val graph = com.point.core.flow.GraphState(
            obj = obj,
            found = frame.found,
            relations = frame.relations,
            focus = frame.focus,
        )
        return graph.copy(
            intent = com.point.core.flow.leadingIntent(graph, working = frame.enriching.isNotEmpty()),
        )
    }

    private fun refreshTopBubbles() {
        val index = stack.lastIndex
        val frame = stack.getOrNull(index) ?: return
        val graph = graphOf(frame)
        val refreshed = frame.copy(
            bubbles = registry.bubblesFor(graph),

            // Подсказка спрашивается по тому же графу, что и двери (#1101): иначе экран
            // отказывается предлагать чтение и тут же подсказывает, как к нему подготовиться.
            latent = registry.latentBubblesFor(graph),
        )
        stack[index] = refreshed
        _ui.update { it.copy(frame = refreshed) }
    }

    private fun updateDevices(block: (DevicesScreenState) -> DevicesScreenState) {
        _ui.update { s -> s.devicesScreen?.let { s.copy(devicesScreen = block(it)) } ?: s }
    }

    private val signInDriver by lazy {
        com.point.core.flow.SignInDriver(
            client = accountClient,
            store = accountStore,
            browser = browser,
            pending = pendingLogins,
        )
    }

    private var signInJob: Job? = null

    private fun gateSignIn() {
        if (accountStore.current() == null) {
            _ui.update { it.copy(signIn = com.point.core.flow.SignIn.SignedOut) }

            resumeSignIn()
        }
    }

    fun signIn() {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            signInDriver.signIn(deviceName(), com.point.core.flow.DeviceKind.PHONE) { state ->
                showSignIn(state)
            }
        }
    }

    fun resumeSignIn() {
        if (signInJob?.isActive == true) return
        signInJob = viewModelScope.launch {

            val started = withContext(ioDispatcher) { runCatching { signInDriver.pendingLogin() }.getOrNull() }
            if (started == null) return@launch
            signInDriver.resume(deviceName(), com.point.core.flow.DeviceKind.PHONE) { state ->
                showSignIn(state, quiet = true)
            }
        }
    }

    private fun showSignIn(state: com.point.core.flow.SignIn, quiet: Boolean = false) {
        if (state is com.point.core.flow.SignIn.SignedIn) {
            val gateWasUp = _ui.value.signIn != null
            _ui.update { it.copy(signIn = null) }

            announceKey(state.account)
            syncAccountSettings()
            if (gateWasUp) openDevices()
            return
        }
        if (quiet && _ui.value.signIn == null) return
        _ui.update { it.copy(signIn = state) }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null

        viewModelScope.launch(NonCancellable) { runCatching { signInDriver.forgetPending() } }
        _ui.update { it.copy(signIn = com.point.core.flow.SignIn.SignedOut) }
    }

    fun dismissSignIn() {
        _ui.update { it.copy(signIn = null) }
    }

    fun openSignInPage(url: String) = browser.open(url)

    fun hasSignInGate(): Boolean = _ui.value.signIn != null

    fun openDevices() {
        val account = accountStore.current()
        if (account == null) {
            gateSignIn()
            return
        }
        val self = com.point.core.flow.CircleDevice(
            id = account.deviceId,
            kind = com.point.core.flow.DeviceKind.PHONE,
            name = account.deviceName.ifBlank { deviceName() },
            lastSeenMillis = System.currentTimeMillis(),
            self = true,
        )
        _ui.update {
            it.copy(
                devicesScreen = DevicesScreenState(email = account.email, devices = listOf(self), loading = true),
                busy = null, message = null, messageOutcome = Outcome.NONE,
            )
        }
        viewModelScope.launch { loadCircle(account) }
        syncAccountSettings()

        pcLinks.current()?.let { pc ->
            viewModelScope.launch {
                runCatching { pcTransport.fetchCaps(pc)?.let { caps -> pcCaps.save(caps) } }
            }
        }
    }

    fun closeDevices() {
        refreshFromPc()
        _ui.update { it.copy(devicesScreen = null) }
    }

    private suspend fun loadCircle(account: com.point.core.flow.PointAccount) {
        val answer = runCatching { accountClient.circle(account) }
            .getOrDefault(com.point.core.flow.CircleAnswer.Unreachable)
        when (answer) {
            is com.point.core.flow.CircleAnswer.Circle -> {

                // Экран получает ответ сразу, знание укладывается следом: `learnCircle`
                // договаривает с компьютером и человека этим ждать незачем (#1076).
                updateDevices { it.copy(devices = answer.devices, checkedNow = true, loading = false, error = null) }
                learnCircle(answer.devices)
            }
            com.point.core.flow.CircleAnswer.Unreachable -> {

                // Молчание сервера — беда операции, а не знание «в круге никого нет»:
                // ниже честной строки об ошибке стоит последний известный круг (#1076).
                // «Пока вы один» остаётся только тому, у кого круга не было никогда
                // или последний известный круг и правда из одного этого устройства.
                val remembered = withContext(ioDispatcher) {
                    runCatching { circleStore.current() }.getOrNull()
                }
                updateDevices {
                    it.copy(
                        loading = false,
                        error = "Не удалось спросить сервер о ваших устройствах — проверьте интернет",
                        devices = remembered ?: it.devices,
                        checkedNow = false,
                    )
                }
            }
            com.point.core.flow.CircleAnswer.Revoked -> forgetAccount(com.point.core.flow.ACCOUNT_REVOKED)
        }
    }

    fun revokeDevice(deviceId: String) {
        val account = accountStore.current() ?: return

        // Круг, каким человек видел его, нажимая «Отключить». Память телефона знает круг лучше,
        // но её может не быть вовсе — шифрованное хранилище не создалось, и `current()` всегда
        // молчит. Тогда прежним кругом остаётся этот снимок, а не список на экране в момент
        // ответа сервера: экран человек вправе закрыть, не дождавшись его (#1076).
        val seen = _ui.value.devicesScreen?.devices.orEmpty()
        updateDevices { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val ok = runCatching { accountClient.revoke(account, deviceId) }.getOrDefault(false)
            if (!ok) {
                updateDevices { it.copy(busy = false, error = "Сервер не отключил устройство — попробуйте ещё раз") }
                return@launch
            }
            if (deviceId == account.deviceId) {
                forgetAccount(com.point.core.flow.SignIn.SignedOut)
                return@launch
            }

            // Сервер отключил устройство — это уже знание о круге, а не ожидание ответа:
            // устройство уходит из памяти телефона сейчас, а не после повторного чтения
            // круга, которое может и не дойти (#1076). Иначе отключённое переживало бы своё
            // отключение в памяти и возвращалось на экран, стоило серверу замолчать.
            //
            // Прежний круг берётся из памяти телефона, а на худой конец — из `seen` выше.
            // Из состояния экрана его не строят вовсе: экран — вид на знание, а не знание,
            // и закрытый экран превращал бы «в круге стало на одного меньше» в «круг опустел».
            updateDevices { screen ->
                screen.copy(busy = false, devices = screen.devices.filterNot { it.id == deviceId })
            }
            val remembered = withContext(ioDispatcher) {
                runCatching { circleStore.current() }.getOrNull()
            }
            val previous = remembered ?: seen
            learnCircle(previous.filterNot { it.id == deviceId })
            loadCircle(account)
        }
    }

    fun signOut() {
        val account = accountStore.current() ?: return
        updateDevices { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { accountClient.signOut(account) }
            forgetAccount(com.point.core.flow.SignIn.SignedOut)
        }
    }

    fun deleteAccount() {
        val account = accountStore.current() ?: return
        updateDevices { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val gone = runCatching { accountClient.deleteAccount(account) }.getOrDefault(false)
            if (gone) {
                forgetAccount(com.point.core.flow.SignIn.SignedOut)
            } else {
                updateDevices {
                    it.copy(busy = false, error = com.point.core.flow.accountRefusal(null).what)
                }
            }
        }
    }

    private suspend fun forgetAccount(next: com.point.core.flow.SignIn) {
        runCatching { accountStore.clear() }

        // Круг принадлежит аккаунту: чужие устройства не переживают выход (#1076).
        runCatching { circleStore.clear() }
        runCatching { pcLinks.clear() }
        runCatching { pcCaps.clear() }
        runCatching { linkMonitor.forget() }
        _ui.update { it.copy(devicesScreen = null, signIn = next) }
    }

    private fun syncCircle() {
        val account = accountStore.current() ?: return
        val now = System.currentTimeMillis()
        if (now - lastCircleSyncMs < CIRCLE_SYNC_THROTTLE_MS) return
        lastCircleSyncMs = now
        announceKey(account)
        viewModelScope.launch {
            val answer = runCatching { accountClient.circle(account) }.getOrNull()
            if (answer is com.point.core.flow.CircleAnswer.Circle) learnCircle(answer.devices)
        }
    }

    /**
     * Новое знание о круге — одним шагом, откуда бы оно ни пришло: ответ сервера или
     * успешное отключение устройства. Память круга (#1076) и связанный компьютер идут
     * за знанием вместе; путь, который обновлял бы одно без другого, — и есть дефект,
     * при котором отключённое устройство оставалось в памяти до следующего ответа сервера.
     *
     * Шаг доводится до конца там, где его позвали, и не бросает работу вдогонку: одно
     * отключение приносит два знания подряд — своё и уточнение сервера, — и вторая запись
     * начинается после первой. Разбегавшиеся корутины писали связку с компьютером наперегонки,
     * и какое знание выигрывало, решал порядок ответов сети.
     *
     * Пустой список — незнание круга, а не круг без устройств: учить нечего.
     *
     * И учить некого, если аккаунта больше нет: круг принадлежит ему.
     */
    private suspend fun learnCircle(devices: List<com.point.core.flow.CircleDevice>) {
        if (devices.isEmpty()) return

        // Ответ о круге приходит когда придёт, а человек за это время мог выйти: «Отключить»
        // прошло, кнопки на экране снова живые, повторный вопрос о круге ещё в пути — и тут
        // нажато «Выйти». `forgetAccount` к этому моменту уже стёр и память круга, и связку
        // с компьютером; дописать их задним числом — вернуть вышедшему телефону чужие
        // устройства и снова заговорить с чужим компьютером (#1076).
        if (accountStore.current() == null) return
        runCatching { circleStore.save(devices) }
        rememberPc(devices)
    }

    private suspend fun rememberPc(devices: List<com.point.core.flow.CircleDevice>) {
        val pc = devices
            .filter { !it.self && it.kind == com.point.core.flow.DeviceKind.PC }
            .maxByOrNull { it.lastSeenMillis ?: 0L }
        if (pc == null) {

            runCatching { pcLinks.clear() }
            runCatching { pcCaps.clear() }
            return
        }
        val known = com.point.core.flow.LinkedPc(pc.id, pc.name, pc.key)

        runCatching { syncSecrets(known) }

        // Объявления обеих сторон освежаются и для давно известного ПК: он мог
        // обновиться, пока связь жила, — «На телефон на ПК» держалось у телефона
        // кэшем вечно, до захода в «Устройства» (#627, скрин владельца 2026-08-09).
        runCatching { pcTransport.fetchCaps(known)?.let { caps -> pcCaps.save(caps) } }
        runCatching { pcTransport.pushPhoneCaps(known, phoneAdvertised()) }

        if (pcLinks.current() == known) return
        runCatching { pcLinks.save(known) }
        refreshFromPc(force = true)
    }

    private fun announceKey(account: com.point.core.flow.PointAccount) {
        val key = runCatching { deviceKeys.keys().publicKey }.getOrNull() ?: return
        viewModelScope.launch { runCatching { accountClient.enroll(account, key) } }
    }

    private val settingsSync by lazy { com.point.core.flow.AccountSettingsSync(accountClient) }

    /**
     * Настройки едут за человеком через аккаунт (#610): ключи сервисов, «куда можно
     * отправлять» и звук. Сервер получает их запечатанными и прочитать не может.
     *
     * [justChanged] — человек прямо сейчас что-то поменял, и это новее общего. Без правки
     * своя отметка берётся у ключей: перезапуск приложения не должен выигрывать спор с
     * устройством, где человек действительно менял настройки позже.
     */
    private fun syncAccountSettings(justChanged: Boolean = false) {
        val account = accountStore.current() ?: return
        viewModelScope.launch(ioDispatcher) {
            val keys = runCatching { userKeys.keys() }.getOrDefault(com.point.core.flow.UserAiKeys.NONE)
            val mine = com.point.core.flow.AccountSettings(
                aiKeys = keys,
                privacy = runCatching { cloudPrivacy.level() }.getOrNull(),
                sound = runCatching { sensorySettings.isSoundEnabled() }.getOrNull(),
                at = if (justChanged) {
                    System.currentTimeMillis()
                } else {
                    keys.mine.maxOfOrNull { it.savedAt } ?: 0L
                },
            )
            val merged = runCatching { settingsSync.sync(account, deviceKeys.keys(), mine) }
                .getOrNull() ?: return@launch
            applyAccountSettings(merged, mine)
        }
    }

    /** Приехавшее применяется только там, где оно отличается: лишних записей не делаем. */
    private suspend fun applyAccountSettings(
        merged: com.point.core.flow.AccountSettings,
        mine: com.point.core.flow.AccountSettings,
    ) {
        merged.aiKeys.mine
            .filter { key -> mine.aiKeys.of(key.providerId)?.apiKey != key.apiKey }
            .forEach { key -> runCatching { userKeys.save(key) } }

        merged.privacy?.takeIf { it != mine.privacy }?.let { runCatching { cloudPrivacy.setLevel(it) } }
        merged.sound?.takeIf { it != mine.sound }?.let { runCatching { sensorySettings.setSoundEnabled(it) } }

        _ui.update {
            it.copy(
                aiKeySet = runCatching { userKeys.keys().mine.isNotEmpty() }.getOrDefault(it.aiKeySet),
                privacyLevel = merged.privacy ?: it.privacyLevel,
                soundEnabled = merged.sound ?: it.soundEnabled,
                keyScreen = if (it.keyScreen == null) null else aiKeysScreen(),
            )
        }
    }

    private suspend fun syncSecrets(pc: com.point.core.flow.LinkedPc) {
        val saved = runCatching { userKeys.keys().mine.maxByOrNull { key -> key.savedAt } }.getOrNull()
        val mine = com.point.core.flow.SharedSecrets(
            aiKey = saved?.apiKey.orEmpty(),
            at = saved?.savedAt ?: 0L,
        )
        val merged = pcTransport.exchangeSecrets(pc, mine) ?: return
        if (merged.aiKey.isBlank() || merged.aiKey == mine.aiKey) return

        // Ключ с компьютера ложится к тому же сервису, что и здешний:
        // чужой сервис ему приписывать не за что.
        val key = UserAiKey(
            providerId = saved?.providerId ?: com.point.core.flow.AI_PROVIDERS.first().id,
            apiKey = merged.aiKey,
            model = saved?.model.orEmpty(),
            baseUrl = saved?.baseUrl.orEmpty(),
            savedAt = merged.at,
        )
        runCatching { userKeys.save(key) }
        _ui.update { it.copy(aiKeySet = true) }
    }

    private fun phoneAdvertised(): List<com.point.core.flow.PcRemoteAction> =
        runCatching { com.point.core.flow.advertisedActions(registry.all()) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: com.point.core.flow.PHONE_ADVERTISED_FALLBACK

    private fun deviceName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

    fun confirmCloud() {
        val run = pendingCloud ?: return
        val scope = pendingCloudScope
        pendingCloud = null
        _ui.update { it.copy(cloudConsent = false) }
        viewModelScope.launch {

            runCatching { consent.allow(scope) }
            run()
        }
    }

    fun declineCloud() {
        pendingCloud = null
        _ui.update {
            it.copy(cloudConsent = false, message = CLOUD_DECLINED, messageOutcome = Outcome.NONE)
        }
    }

    fun setCloudAllowed(allowed: Boolean) {
        viewModelScope.launch {
            runCatching {
                if (allowed) consent.allow(com.point.core.flow.CloudScope.MODELS)
                else consent.revoke(com.point.core.flow.CloudScope.MODELS)
            }

            // «Не отправлять» сильнее режима: он и был заранее данным согласием (#795).
            if (!allowed) {
                runCatching { yolo.setEnabled(false) }
                _ui.update { it.copy(yoloEnabled = false) }
            }
            refreshCloudConsent()
        }
    }

    private suspend fun refreshCloudConsent() {
        val allowed = runCatching { consent.allowed(com.point.core.flow.CloudScope.MODELS) }.getOrDefault(false)
        val yoloOn = runCatching { yolo.enabled() }.getOrDefault(false)
        _ui.update { it.copy(cloudEnabled = allowed, yoloEnabled = yoloOn) }
    }

    private fun showAppPicker(obj: PointObject) {
        val voice = claimVoice()
        raiseBusy("Ищу приложения…", cancelable = true)
        trackWork {
            val direct = runCatching { appLauncher.handlers(obj) }.getOrDefault(emptyList())

            val apps = (direct + bridgedHandlers(obj)).distinctBy { it.packageName }
            if (!owns(voice)) return@trackWork
            _ui.update {
                if (apps.isEmpty()) it.copy(busy = null, busyStage = null, message = "Нет приложения для этого объекта", messageOutcome = Outcome.FAILED)
                else it.copy(busy = null, busyStage = null, appPicker = apps)
            }
        }
    }

    private suspend fun bridgedHandlers(obj: PointObject): List<AppTarget> {
        val state = stack.lastOrNull()?.obj?.state ?: return emptyList()
        val transforms = registry.bubblesFor(state).mapNotNull { bubble ->
            val produced = runCatching { registry.byId(bubble.capabilityId).produces(state) }.getOrNull()
            val kind = produced?.kind?.takeIf { it != state.kind } ?: return@mapNotNull null
            openableMime(kind)?.let { Triple(bubble.capabilityId, kind, it) }
        }.distinctBy { it.third }
        return transforms.flatMap { (capId, kind, mime) ->
            runCatching { appLauncher.handlersForMime(mime) }.getOrDefault(emptyList())
                .map { it.copy(label = "${it.label} · ${kindShort(kind)}", via = capId.value) }
        }
    }

    fun onPickApp(target: AppTarget) {
        val obj = stack.lastOrNull()?.obj ?: return
        _ui.update { it.copy(appPicker = null) }
        val via = target.via

        trackWork {

            if (via == null) {
                val pick = ChosenApp(obj.state.kind, target.packageName, target.activity, target.label)
                runCatching { chosenApps.record(pick) }
                runCatching { usage.record(CapabilityId("app:${target.packageName}#${obj.state.kind.name}")) }
            }
            val toOpen = if (via != null) bridge(obj, via) else obj

            ensureActive()
            if (toOpen == null) {
                _ui.update {
                    it.copy(
                        busy = null, busyStage = null, messageOutcome = Outcome.FAILED,
                        message = "Не удалось подготовить объект для этого приложения",
                    )
                }
                return@trackWork
            }
            runCatching { appLauncher.launch(target, toOpen) }

                // Выбранное приложение — тот же уход в чужой экран, что и календарь (#1131):
                // подпись гаснет возвратом человека, а не висит над объектом.
                .onSuccess { sayHandingOff("Открываю в ${target.label}") }
                .onFailure { e -> _ui.update { it.copy(busy = null, busyStage = null, message = e.message ?: "Не удалось открыть", messageOutcome = Outcome.FAILED) } }
        }
    }

    /**
     * Исполнить просьбу компьютера, не забирая объект себе (ADR-0001 §7, §20).
     *
     * Телефон здесь исполнитель, а не новый дом: работа идёт над рабочей копией, результат
     * и добытое знание уезжают обратно к тому объекту, который у компьютера, и разбор
     * человека на телефоне при этом не трогается — он мог смотреть совсем другую вещь.
     *
     * Своего жизненного цикла у этого нет: тот же Resolver, тот же Realizer, тот же
     * `ActionResult` — меняется только то, куда возвращается результат.
     */
    private suspend fun executeForPc(
        pc: com.point.core.flow.LinkedPc,
        ask: Map<String, String>,
        path: String,
    ) {
        val f = com.point.core.flow.PcExecFields
        val capability = ask[f.ACTION]?.takeIf { it.isNotBlank() } ?: return
        val home = ask[f.HOME]?.takeIf { it.isNotBlank() } ?: return
        val label = ask[f.LABEL]?.takeIf { it.isNotBlank() } ?: capability
        val mime = ask["mime"] ?: "application/octet-stream"

        _ui.update { it.copy(message = "«$label» — делаю для компьютера", messageOutcome = Outcome.NONE) }

        val outcome = runCatching {
            val obj = store.ingest("file://$path", mime).let { taken ->
                val carried = ask - PC_SERVICE_META - com.point.core.flow.PC_EXEC_META
                taken.copy(
                    metadata = com.point.core.flow.mergeKnowledge(
                        taken.metadata,
                        carried,
                        region = phoneRegion.code(),
                    ),
                )
            }
            val realizer = resolver.realizerFor(CapabilityId(capability), obj.state)
            realizer.perform(obj, null).knownBy(obj, realizer.meta.actor) to obj
        }.getOrElse { e ->
            ActionResult.Failure(e.message ?: "не вышло", recoverable = true) to null
        }

        val (result, worked) = outcome
        val sent = runCatching { sendExecutionResult(pc, ask, home, capability, label, result, worked) }
        val said = when {
            sent.isFailure -> "«$label» — результат не доехал до компьютера"
            result is ActionResult.Failure -> "«$label» — не вышло, компьютер об этом знает"
            else -> "«$label» — сделано для компьютера"
        }
        _ui.update {
            it.copy(
                message = said,
                messageOutcome = if (sent.isFailure) Outcome.FAILED else Outcome.DONE,
            )
        }
    }

    /** Результат уезжает домой — к объекту компьютера, а не остаётся здесь. */
    private suspend fun sendExecutionResult(
        pc: com.point.core.flow.LinkedPc,
        ask: Map<String, String>,
        home: String,
        capability: String,
        label: String,
        result: ActionResult,
        worked: PointObject?,
    ) {
        val f = com.point.core.flow.PcResultFields
        val e = com.point.core.flow.PcExecFields
        val head = mapOf(
            e.HOME to home,
            e.ACTION to capability,
            e.LABEL to label,
            e.REQUEST to ask[e.REQUEST].orEmpty(),
        )
        val produced = (result as? ActionResult.Success)?.result
        val findings = (result as? ActionResult.Done)?.findings

        // Знание едет компьютеру значением, а не ссылкой на scratch этого телефона (#811,
        // #995): прочитанный здесь текст на той стороне жил мёртвым путём, и компьютер снова
        // считал свой документ непрочитанным. Дорога в обе стороны одна.
        val understood = packedForPc(findings?.metadata.orEmpty()).mapKeys { (k, _) -> f.UNDERSTOOD + k }

        val body = produced?.let { made ->
            PointObject(
                id = "$home:pc:$capability",
                mime = made.mime,
                uri = made.uri,
                state = com.point.core.model.ObjectState(made.type),
                metadata = made.metadata,
                provenance = made.provenance,
            )
        } ?: worked ?: return

        val meta = head + understood + com.point.core.flow.lineageMeta(
            sourceId = home,
            creator = capability,
            provenance = produced?.provenance ?: com.point.core.model.Provenance.RULE,
            executor = PHONE_EXECUTOR,
        ) + when (result) {
            is ActionResult.Failure -> mapOf(
                f.OUTCOME to f.FAILED,
                f.DETAIL to result.reason,
            )
            is ActionResult.Done -> mapOf(f.OUTCOME to f.DONE, f.DETAIL to result.message)
            is ActionResult.Success -> mapOf(
                f.OUTCOME to f.DONE,
                f.NAME to (produced?.metadata?.get("name") ?: "$label.result"),
                f.MIME to (produced?.mime ?: body.mime),
            )
            else -> mapOf(f.OUTCOME to f.FAILED, f.DETAIL to "«$label» ждёт продолжения на телефоне")
        }

        // Пустой шаг без нового объекта отправляет только знание: файла у него нет, и
        // компьютеру он приезжает как знание своего объекта, а не как вещь.
        val nameForFile = (meta[f.NAME] ?: "$label.txt")
        pcTransport.send(pc, if (result is ActionResult.Success) body else emptyBody(body), nameForFile, meta)
    }

    /** Прочитанное здесь едет компьютеру содержимым: ссылка на scratch телефона там мертва (#811). */
    private suspend fun packedForPc(knowledge: Map<String, String>): Map<String, String> {
        val ref = com.point.core.flow.textRefForTravel(knowledge) ?: return knowledge
        val text = runCatching {
            store.readText(
                PointObject(
                    id = "read",
                    mime = "text/plain",
                    uri = com.point.core.model.ScratchRef(ref),
                    state = com.point.core.model.ObjectState(ObjectKind.TEXT),
                ),
                limit = com.point.core.flow.READ_TEXT_TRAVEL_LIMIT,
            )
        }.getOrNull()
        return com.point.core.flow.knowledgePackedForTravel(knowledge, text)
    }

    /** Тело письма, когда объекта у результата нет: знание едет, вещь не рождается. */
    private suspend fun emptyBody(sample: PointObject): PointObject {
        val ref = store.newScratchFile("txt")
        java.io.File(ref.value).writeText("")
        return sample.copy(uri = ref, mime = "text/plain")
    }

    /**
     * Исполнить действие и запомнить, кто именно его исполнил (#1127).
     *
     * Тот же шов, что и у исследований (`DefaultEnrichment.run`): имя исполнителя знает
     * только место, где его выбрал Resolver, и знание уходит в Graph уже с ним.
     */
    private suspend fun performed(id: CapabilityId, obj: PointObject, amendment: String?): ActionResult {
        val realizer = resolver.realizerFor(id, obj.state)
        return realizer.perform(obj, amendment).knownBy(obj, realizer.meta.actor)
    }

    private suspend fun bridge(obj: PointObject, viaCapId: String): PointObject? {
        claimVoice()
        raiseBusy("Преобразую…", cancelable = true)
        val result = runCatching { resolver.realizerFor(CapabilityId(viaCapId), obj.state).perform(obj, null) }.getOrNull()
        return (result as? ActionResult.Success)?.let {
            runCatching { store.put(it.result, from = obj, by = CapabilityId(viaCapId)) }.getOrNull()
        }
    }

    private fun openableMime(kind: ObjectKind): String? = when (kind) {
        ObjectKind.PDF -> "application/pdf"
        ObjectKind.IMAGE -> "image/png"
        ObjectKind.TEXT -> "text/plain"
        else -> null
    }

    private fun kindShort(kind: ObjectKind): String = when (kind) {
        ObjectKind.PDF -> "PDF"
        ObjectKind.IMAGE -> "картинка"
        ObjectKind.TEXT -> "текст"
        else -> kind.name
    }

    fun dismissAppPicker() = _ui.update { it.copy(appPicker = null) }

    fun saveAiKey(key: UserAiKey) {
        viewModelScope.launch {
            runCatching { userKeys.save(key.copy(savedAt = System.currentTimeMillis())) }
            _ui.update {
                it.copy(
                    keyScreen = if (it.keyScreen == null) null else aiKeysScreen(),
                    keyVerdict = null, keyVerdictFor = null, keyChecking = null,
                    aiKeySet = runCatching { userKeys.keys().mine.isNotEmpty() }.getOrDefault(false),

                    message = if (it.keyScreen == null) "Ключ сохранён" else it.message,
                    messageOutcome = if (it.keyScreen == null) Outcome.DONE else it.messageOutcome,
                )
            }

            refreshTopBubbles()
        }
    }

    private fun dispatch(bubble: Bubble, top: PointObject, action: suspend (PointObject) -> ActionResult) {
        runCatching { sensory.tap() }

        busyJob?.cancel()
        val voice = claimVoice()
        runningStep = bubble.title
        trackWork { runAction(bubble, voice, top, action) }
    }

    /**
     * Как называется идущая сейчас работа (#1133).
     *
     * У прерванного шага должно быть имя: «молча потерять начатое нельзя» — человек ждал
     * таблицу, за которую уже заплатил ожиданием и облачным вызовом.
     */
    @Volatile private var runningStep: String? = null

    /**
     * Предел одного действия (#1069) — поле, а не константа: тесты с виртуальными часами
     * гоняют настоящие движки на настоящих потоках, и виртуальные десять минут пролетают
     * раньше, чем настоящая секунда работы.
     */
    internal var actionCeilingMs: Long? = ACTION_CEILING_MS

    /**
     * Действие идёт над объектом, каким он стал к началу работы, а не к моменту тапа (#1060):
     * `top` — объект под пальцем, `action` получает его уже после `afterReading`.
     */
    private suspend fun runAction(
        bubble: Bubble,
        voice: Long,
        top: PointObject,
        action: suspend (PointObject) -> ActionResult,
    ) {
        runCatching { usage.record(bubble.capabilityId) }
        runCatching {

            // У действия есть предел (#1069). «Расшифровать» шло шестнадцать минут и не
            // кончалось ничем — сеть была, обменов не прибавлялось, выход был один: отмена
            // руками. Предел щедрый: таблица на эмуляторе строится минутами, и это законно.
            // Дальше предела — честный отказ операции, знание не трогается (ADR-0001 §9).
            // Ожидание чтения (#1060) — тоже внутри предела и под той же кнопкой «Отменить».
            val work: suspend () -> ActionResult = {
                kotlinx.coroutines.withContext(
                    com.point.core.flow.ActionProgress { stage ->
                        if (voice == workVoice) _ui.update { it.copy(busyStage = stage) }
                    },
                ) { action(afterReading(bubble.capabilityId, top)) }
            }
            actionCeilingMs?.let { ceiling -> kotlinx.coroutines.withTimeout(ceiling) { work() } } ?: work()
        }
            // Потолок — исход операции, а не исключение: TimeoutCancellation дальше по
            // цепочке приняли бы за отмену человеком и промолчали (см. onFailure ниже).
            .let { done ->
                val e = done.exceptionOrNull()
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    Result.success(
                        ActionResult.Failure(
                            "«${bubble.title}» не уложилось в ${(actionCeilingMs ?: ACTION_CEILING_MS) / 60_000} минут — прервано",
                            recoverable = true,
                        ),
                    )
                } else {
                    done
                }
            }

            .onSuccess { result ->
                if (owns(voice)) {
                    runningStep = null
                    handleResult(result, bubble)
                }
            }
            .onFailure { e ->

                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!owns(voice)) return@onFailure
                runningStep = null

                _ui.update { it.copy(busy = null, busyStage = null, message = e.message ?: "Не получилось", messageOutcome = Outcome.FAILED) }
            }
    }

    /**
     * Объект, над которым суд идёт на самом деле (#1060).
     *
     * «Понять» судит по графу, а нажатое, пока снимок ещё читался, судило по пустому: шло
     * слепым путём, объявляло «На снимке ничего не разобрать» — и через минуту тот же экран
     * показывал над этим вердиктом сто двадцать прочитанных адресов. Состояние операции
     * чтения исполнителю не передаётся — и не должно, это не знание (ADR-0001 §9); поэтому
     * ждёт тот, кто его видит: суд откладывается тем же экраном «Идёт» со своей стадией и
     * идёт уже по полному графу.
     *
     * Ждётся сам вопрос чтения, а не «сейчас что-то крутится». Исследования идут волнами по
     * latency: в конце каждой волны идущих нет вовсе, а чтение снимка — последняя, самая
     * медленная волна. Признак «список идущего пуст» просыпался бы на границе волн, ещё до
     * начала чтения, и суд снова шёл бы по графу без текста.
     *
     * Правило одно на любой объект, а не на снимок: суд идёт, когда работа над этим объектом
     * либо ответила на вопрос чтения, либо кончилась вся. Снимок и сканированный PDF отвечают
     * на этот вопрос раньше конца прохода ([unread]) — им дальше ждать нечего; объекту, у
     * которого вопроса чтения нет вовсе, ожидание кончает конец разбора. Ждётся ровно та
     * работа, которая ещё может изменить граф, — дольше не держат нигде.
     *
     * Ждёт только тот, кто судит, и говорит об этом сам: признак `judgesKnowledge` объявляет
     * Capability, а не сравнение id в экранном слое.
     */
    private suspend fun afterReading(id: CapabilityId, tapped: PointObject): PointObject {
        if (!judgesKnowledge(id) || !unread(tapped)) return tapped
        val work = enrichJobs.filter { it.objectId == tapped.id && it.job.isActive }.map { it.job }
        if (work.isEmpty()) return tapped
        reportStage(waitingForReadingStage(tapped.state.kind))
        awaitReading(tapped.id, work)
        return focused()?.takeIf { it.id == tapped.id } ?: tapped
    }

    /** Судит ли действие об объекте по знанию — спрашивается у самого действия (#1060). */
    private fun judgesKnowledge(id: CapabilityId): Boolean =
        runCatching { registry.byId(id).meta.judgesKnowledge }.getOrDefault(false)

    /**
     * Вопрос чтения объекта ещё без ответа: `not investigated` ≠ `not found` (ADR-0001 §9).
     *
     * Вопрос один и тот же у снимка и у сканированного документа — на `image-text` отвечают
     * и `ocr`, и `read-document` (`CapabilityMeta.answers`), поэтому здесь не спрашивается
     * про вид объекта. У объекта, которому этот вопрос не задают, ответа не будет никогда —
     * и ожидание такого объекта кончает не он, а конец разбора ([awaitReading]).
     */
    private fun unread(obj: PointObject): Boolean =
        com.point.core.flow.investigationStateOf(obj.metadata, com.point.core.flow.KnownCapabilities.IMAGE_TEXT) ==
            com.point.core.flow.InvestigationState.NOT_INVESTIGATED

    /**
     * Ждёт ответа на вопрос чтения — но не дольше, чем живёт работа [work], которая одна его
     * и принесёт: сорвавшееся чтение ответа не пишет (знание остаётся «не исследовано»), и
     * держать человека вечно из-за этого нельзя.
     */
    private suspend fun awaitReading(objectId: String, work: List<Job>) {
        kotlinx.coroutines.flow.merge(
            _ui.map { ui -> ui.frame?.obj?.let { it.id != objectId || !unread(it) } ?: true },
            kotlinx.coroutines.flow.flow { work.joinAll(); emit(true) },
        ).first { it }
    }

    /**
     * Отказ человека всегда снимает экран ожидания- даже если работу к этому моменту
     * не за что взять. Оставлять человека запертым из-за того, что Point потерял
     * собственную работу, нельзя (#692).
     */
    fun cancelAction() {
        if (_ui.value.busy == null) return

        busyJob?.cancel()
        busyJob = null

        // Отменённый вход не оставляет недокопированного в scratch (#640): объекта ещё нет,
        // а байты уже легли — и лежали бы до следующего приёма.
        if (_ui.value.frame == null) viewModelScope.launch { runCatching { store.clear() } }

        // Новый голос глушит работу, которую уже не отменить: её результат не приземлится.
        claimVoice()

        val hasObject = _ui.value.frame != null
        _ui.update {
            it.copy(
                busy = null, busyStage = null, busyCancelable = false,
                message = if (hasObject) "Отменено" else null, messageOutcome = Outcome.NONE,
            )
        }
    }

    /**
     * Подпись шага, отдавшего человека чужому экрану (#1131): календарь, карточка контакта,
     * карта, диалог «Поделиться», выбранное приложение — там работу заканчивает человек,
     * а не Point.
     *
     * Возврат в Point без результата — отмена человека, а не ошибка: подпись гаснет
     * молча, без «не вышло» (линза «без оправданий», #1003).
     */
    @Volatile private var handOffMessage: String? = null

    /**
     * Отдаёт ли шаг человека системе — говорит само действие (`CapabilityMeta.handsOff`),
     * а не список id здесь: список знал календарь и визитку и не знал «Сохранить контакт»,
     * который уводит в то же приложение контактов, — и его подпись висела вечно.
     */
    private fun handsOffToSystem(id: CapabilityId) =
        runCatching { registry.byId(id).meta.handsOff }.getOrDefault(false)

    /** Исход шага-передачи: подпись стоит, пока человек на чужом экране, и гаснет его возвратом. */
    private fun sayHandingOff(said: String) {
        handOffMessage = said
        _ui.update { it.copy(busy = null, busyStage = null, message = said, messageOutcome = Outcome.DONE) }
    }

    /** Зов из onResume хостов: человек вернулся в Point с чужого экрана. */
    fun returnedToPoint() {
        val standing = handOffMessage ?: return
        handOffMessage = null
        _ui.update {
            if (it.message == standing) it.copy(message = null, messageOutcome = Outcome.NONE) else it
        }
    }

    private suspend fun handleResult(result: ActionResult, bubble: Bubble) {
        handOffMessage = null
        when (result) {
            is ActionResult.Success -> {
                runCatching { sensory.success() }

                // Происхождение результата — часть Graph, а не только кадра: объект
                // помнит, из чего и каким действием он сделан (ADR-0001 §2, #1127).
                val source = stack.lastOrNull()?.obj
                val produced = store.put(result.result, from = source, by = bubble.capabilityId)

                // Результат — такой же объект человека, как присланный (#1057): скан, взятый
                // фрагмент или собранный документ должны находиться в «Недавнем» и после
                // выхода. Прежде туда попадал только вход, и сделанное пропадало бесследно
                // вместе со scratch. Шаг, продолживший тот же объект («Понять»), записи не
                // множит — его довозит до истории update; мерка той же, что у pushFrame:
                // continuesObject.
                if (source == null || !com.point.core.flow.continuesObject(source, produced)) {
                    runCatching { history.record(produced) }
                }
                pushFrame(produced, bubble.capabilityId, bubble.title)

                yieldSurprise(bubble.yields, produced.state.kind, produced.metadata[META_YIELD_NOUN])?.let { note ->
                    _ui.update { it.copy(message = note, messageOutcome = Outcome.NONE) }
                }
            }
            is ActionResult.Done -> {

                // Уход объекта на соседнее устройство звучит своим звуком, а не общим
                // успехом (#650): на той стороне его подхватит парный, того же тембра.
                runCatching { if (leavesForPc(bubble.capabilityId)) sensory.sent() else sensory.success() }

                // ADR-0001 §18: «выполнено» может нести новое знание — оно идёт тем же
                // merge-путём, что и находки исследований, а не выбрасывается.
                result.findings?.takeIf { !it.isEmpty }?.let(::landFindings)
                if (handsOffToSystem(bubble.capabilityId)) {
                    sayHandingOff(result.message)
                } else {
                    _ui.update { it.copy(busy = null, busyStage = null, message = result.message, messageOutcome = Outcome.DONE) }
                }
            }
            is ActionResult.Failure -> {

                // Ожидание — не отказ (#992): пока движок готовится, работа ещё не начиналась,
                // и через минуту она выйдет. Крест и звук провала над просьбой подождать врут
                // человеку о том, что сломалось. Слово остаётся, исход — просто сообщение.
                val waiting = com.point.core.flow.failureIsWaiting(result.reason)
                if (!waiting) runCatching { sensory.failure() }

                _ui.update {
                    it.copy(
                        busy = null, busyStage = null, message = result.reason,
                        messageOutcome = if (waiting) Outcome.NONE else Outcome.FAILED,
                    )
                }
            }
            is ActionResult.NeedsInput -> {
                pendingBubble = bubble
                _ui.update { it.copy(busy = null, busyStage = null, inputPrompt = result.prompt, inputSuggestions = result.suggestions) }
            }
            is ActionResult.NeedsImage -> {

                pendingBubble = bubble
                _ui.update { it.copy(busy = null, busyStage = null, needsImage = result.prompt) }
            }
        }
    }

    fun dismissMessage(): Boolean {
        val state = _ui.value
        if (state.frame != null || state.message == null) return false
        _ui.update { it.copy(message = null, messageOutcome = Outcome.NONE) }
        return true
    }

    fun onBack(): Boolean {

        // Уход с экрана ожидания — отказ от работы, а не согласие ждать её в пустоте (#668):
        // облачный вызов иначе долетал и оплачивался уже после того, как человек ушёл.
        // Ровно то же, что делает видимая кнопка «Отменить», — и ровно там, где она видна.
        if (showsCancel(_ui.value)) {
            cancelAction()
            return true
        }
        if (_ui.value.selection != null) {
            closeSelection()
            return true
        }
        if (_ui.value.find != null) {
            closeFind()
            return true
        }
        if (_ui.value.preview != null) {
            cancelPreview()
            return true
        }
        if (_ui.value.appPicker != null) {
            dismissAppPicker()
            return true
        }
        if (_ui.value.cloudConsent) {
            declineCloud()
            return true
        }
        if (openChatOf(_ui.value) != null) {
            closeChat()
            return true
        }

        if (_ui.value.signIn != null) {
            cancelSignIn()
            dismissSignIn()
            return true
        }
        if (_ui.value.devicesScreen != null) {
            closeDevices()
            return true
        }
        if (_ui.value.keyScreen != null) {
            closeKeySettings()
            return true
        }
        if (_ui.value.inputPrompt != null || _ui.value.needsImage != null) {
            cancelInput()
            return true
        }
        if (stack.size <= 1) return false
        stack.removeLast()
        val top = stack.last()
        _ui.update { it.copy(frame = top, message = null, messageOutcome = Outcome.NONE, path = currentPath()) }
        persistJourney()
        return true
    }


    fun jumpTo(index: Int) {
        if (index < 0 || index >= stack.size - 1) return
        while (stack.size - 1 > index) stack.removeLast()
        val top = stack.last()
        _ui.update { it.copy(frame = top, message = null, messageOutcome = Outcome.NONE, path = currentPath()) }
        persistJourney()
    }

    private fun currentPath(): List<PathStep> =
        stack.map { PathStep(it.obj.state.kind, it.viaTitle) }

    fun hasFlow(): Boolean = stack.isNotEmpty()

    fun endFlow() {
        cancelEnrichment()

        busyJob?.cancel()
        busyJob = null

        signInJob?.cancel()
        signInJob = null

        // Разговор сворачивает свой держатель — здесь про его внутренности не знают (#833).
        chatFlow.close()
        stack.clear()
        pendingBubble = null
        pendingPreviewBubble = null
        _ui.update { FlowUiState() }

        viewModelScope.launch(NonCancellable) {
            runCatching { store.clear() }

            runCatching { sharedTexts.clear() }
            runCatching { flowSnapshot.clear() }
        }
    }

    /**
     * Спросить компьютер, что он умеет, — фоном, при появлении объекта (#633).
     *
     * Первый экран рисуется как прежде, без сети: вопрос уходит после отрисовки, а пришедший
     * ответ обновляет пространство действий — так же, как это делают обогатители признаков.
     * Прежде объявление освежалось только при заходе в «Мои устройства», и телефон показывал
     * состояние недельной давности как нынешнее.
     */
    private fun refreshPcCapsInBackground() {
        val pc = runCatching { pcLinks.current() }.getOrNull() ?: return
        if (com.point.core.flow.capsFresh(pcCaps.savedAt(), System.currentTimeMillis())) return
        viewModelScope.launch(ioDispatcher) {
            val fresh = runCatching { pcTransport.fetchCaps(pc) }.getOrNull() ?: return@launch
            runCatching { pcCaps.save(fresh) }
        }
    }

    /**
     * Прошлый объект уходит с экрана сразу, как только пришёл новый (#939).
     *
     * Раньше кадр менялся только в конце: скопировать файл, разобрать, положить в историю.
     * Всё это время человек видел **прежний** объект со всеми его живыми кнопками — и мог
     * нажать действие, не зная, к чему оно относится. Живая охота 13.08.2026 ловила окно до
     * полуминуты, а на фотографиях экран так и оставался чужим.
     *
     * Показывать нечего — значит и не показываем: остаётся «Открываю…», а объект встаёт на
     * место, как только его есть чем показать.
     */
    /**
     * Новый объект приходит поверх начатого — прежняя работа обрывается, и у обрыва есть
     * исход (#1133, ADR-0001 §18).
     *
     * Прежде шаг исчезал молча: экран переключался на новое, облачная работа доживала в
     * пустоту, результат не приходил никуда, и человеку об этом не говорили ни строки.
     * Отмена руками так себя не ведёт — она честно говорит «Отменено».
     */
    private fun makeWayForIncoming(): String? {
        val interrupted = runningStep?.takeIf { busyJob?.isActive == true }
        runningStep = null
        busyJob?.cancel()
        cancelEnrichment()
        stack.clear()
        _ui.update { it.copy(frame = null, focusPreview = null, path = emptyList()) }
        return interrupted
    }

    /** Сказать про оборванный шаг, когда новый объект уже открыт и экран освободился. */
    private fun tellInterrupted(step: String?) {
        val what = step ?: return
        _ui.update { it.copy(message = "«$what» прервано — принят новый объект", messageOutcome = Outcome.NONE) }
    }

    private fun pushFrame(obj: PointObject, via: CapabilityId? = null, viaTitle: String? = null) {
        refreshPcCapsInBackground()

        // Объект в разборе один (ADR-0001 §2, #1110). Вход в найденное, которое уже открыто
        // выше по пути, — возврат к тому же узлу, а не второй его экземпляр: иначе знание
        // об одном объекте расходилось по двум кадрам, а связь с уже созданным из него
        // результатом терялась вместе с ним.
        if (via == null && returnedToOpenNode(obj)) return

        val parent = stack.lastOrNull()
        val carried = parent?.takeIf { continuesObject(it.obj, obj) }
        val known = carried?.let { carryKnowledge(it.obj, obj, phoneRegion.code()) } ?: obj

        // Вход в найденное забирает его окрестность графа (#1176): связи узла, тех, с кем он
        // связан, и их связи — иначе внутри номера оставалась связь «чей он», а сказать, чей,
        // было некому. Чужие находки при этом не тащатся.
        val around = parent?.takeIf { carried == null }
            ?.let { com.point.core.flow.GraphState(it.obj, it.found, it.relations).around(obj.id) }
        val carriedFound = carried?.found ?: around?.found.orEmpty()
        val carriedRelations = carried?.relations ?: around?.relations.orEmpty()

        // Порождённый объект знает, откуда он взялся (#946). Связь именованная: архив
        // содержит файлы, а запись получена из текста — это не одно и то же, и в графе они
        // не сливаются. Раньше вложенность жила стопкой экранов: пока человек внутри — путь
        // помнит, вышел — забыл; отношения между объектами не создавались вовсе.
        val born = via?.let { bornOf(parent, known, it) }
        val relations = carriedRelations + listOfNotNull(born)

        // Исходник, из которого объект получен, стоит рядом (#925): это и путь к нему, и
        // опора для порядка действий — обратное преобразование того, что Point только что
        // сделал, первым стоять не должно.
        val cameFrom = parent?.obj?.takeIf {
            born?.type == com.point.core.model.RelationType.DERIVED_FROM &&
                carriedFound.none { seen -> seen.id == it.id }
        }
        val found = carriedFound + listOfNotNull(cameFrom)
        val graph = com.point.core.flow.GraphState(known, found, relations)
        val bubbles = registry.bubblesFor(graph)
        val frame = FlowFrame(
            known, bubbles, via, viaTitle,

            found = found,
            relations = relations,

            // Двери и подсказки считаются по одному графу (#1101).
            latent = registry.latentBubblesFor(graph),
        )

        // Исходник помнит, что из него вышло: вернулся человек назад — узел на месте, и
        // пространство действий исходника считается уже с ним.
        parent?.let { rememberBorn(it, known, born) }
        stack.addLast(frame)
        _ui.update {
            it.copy(
                busy = null, busyStage = null, frame = frame, message = null, messageOutcome = Outcome.NONE, inputPrompt = null, inputSuggestions = emptyList(),
                needsImage = null, preview = null, path = currentPath(),
            )
        }
        persistJourney()
        enrichInBackground(known)
        loadChildrenIfCollection(known)
        loadTextPreviewIfText(known)
        loadObjectPreview(known)
    }

    /**
     * Возврат к уже открытому узлу пути (#1110).
     *
     * Знание, пришедшее вместе с находкой, не выбрасывается: оно сливается в узел тем же
     * merge-путём, что и любое другое (`carryKnowledge`), — расхождения остаются видимыми,
     * а не создают второй экземпляр объекта со своей половиной правды.
     */
    private fun returnedToOpenNode(obj: PointObject): Boolean {
        val at = stack.indexOfLast { it.obj.id == obj.id }
        if (at < 0) return false

        while (stack.size > at + 1) stack.removeLast()

        val open = stack.last()
        val known = carryKnowledge(open.obj, obj, phoneRegion.code())
        val frame = open.copy(
            obj = known,
            bubbles = registry.bubblesFor(
                com.point.core.flow.GraphState(known, open.found, open.relations),
            ),
        )
        stack[at] = frame
        _ui.update {
            it.copy(
                busy = null, busyStage = null, frame = frame, message = null,
                messageOutcome = Outcome.NONE, inputPrompt = null, inputSuggestions = emptyList(),
                needsImage = null, preview = null, path = currentPath(),
            )
        }
        persistJourney()
        return true
    }

    /**
     * Какой связью новый объект держится за исходник (#946).
     *
     * Решение владельца 13.08.2026: связи разные — «содержит» и «получено из». Что действие
     * достаёт из исходника, а что делает заново, объявляет само действие.
     */
    private fun bornOf(
        parent: FlowFrame?,
        born: PointObject,
        via: CapabilityId,
    ): com.point.core.model.Relation? {
        val source = parent?.obj ?: return null
        if (source.id == born.id) return null
        val inside = runCatching { registry.byId(via).meta.revealsInside }.getOrDefault(false)
        val type = if (inside) {
            com.point.core.model.RelationType.CONTAINS
        } else {
            com.point.core.model.RelationType.DERIVED_FROM
        }
        return if (inside) {
            com.point.core.model.Relation(source.id, type, born.id)
        } else {
            com.point.core.model.Relation(born.id, type, source.id)
        }
    }

    /** Исходник запоминает вышедший из него объект — иначе шаг назад стирает его. */
    private fun rememberBorn(parent: FlowFrame, born: PointObject, relation: com.point.core.model.Relation?) {
        if (relation == null || parent.found.any { it.id == born.id }) return
        val index = stack.indexOfFirst { it === parent }
        if (index < 0) return
        stack[index] = parent.copy(
            found = parent.found + born,
            relations = parent.relations + relation,
        )
    }

    /**
     * Возвращает кадру найденные объекты, связи и Focus из снапшота и пересчитывает список
     * действий по полному состоянию. Исследования повторно не запускаются: только знание
     * и порядок действий.
     */
    private fun restoreGraph(f: FlowSnapshotFrame) {
        if (f.found.isEmpty() && f.relations.isEmpty() && f.focusRegion == null && f.focusIds == null) return
        val index = stack.lastIndex
        val frame = stack.getOrNull(index)?.takeIf { it.obj.id == f.id } ?: return
        val focus = com.point.core.flow.focusOf(
            buildMap {
                f.focusRegion?.let { put(com.point.core.flow.META_FOCUS_REGION, it) }
                f.focusIds?.let { put(com.point.core.flow.META_FOCUS_IDS, it) }
            },
            frame.obj.id,
        )
        val restored = frame.copy(
            found = (frame.found + f.found).distinctBy { it.id },
            relations = (frame.relations + f.relations).distinct(),
            focus = frame.focus ?: focus,
        )
        stack[index] = restored.copy(bubbles = registry.bubblesFor(graphOf(restored)))
        _ui.update { if (it.frame?.obj?.id == f.id) it.copy(frame = stack[index]) else it }
    }

    private fun persistJourney() {
        val frames = stack.map { f ->

            val focusWire = com.point.core.flow.withFocus(emptyMap(), f.focus)
            FlowSnapshotFrame(
                id = f.obj.id, kind = f.obj.state.kind, mime = f.obj.mime, ref = f.obj.uri.value,
                metadata = f.obj.metadata,
                viaCapabilityId = f.viaCapability?.value, viaTitle = f.viaTitle,
                found = f.found,
                relations = f.relations,
                focusRegion = focusWire[com.point.core.flow.META_FOCUS_REGION],
                focusIds = focusWire[com.point.core.flow.META_FOCUS_IDS],
            )
        }
        viewModelScope.launch { runCatching { flowSnapshot.save(frames) } }
    }

    private fun refFor(kind: ObjectKind, ref: String): ObjectRef =
        if (kind.isFileBacked) ScratchRef(ref) else ValueRef(ref)

    private fun loadObjectPreview(obj: PointObject) {
        if (obj.state.kind != ObjectKind.IMAGE && obj.state.kind != ObjectKind.PDF) return
        viewModelScope.launch {
            var failure: Throwable? = null
            val bitmap = withContext(ioDispatcher) {

                // Сорвавшееся чтение страницы — не «нечего показать» (#570): по его причине
                // человеку скажут, что документ пуст, а не общее «файл не открылся».
                val source = runCatching { previewSource(obj, pdfRasterizer) }
                    .onFailure { failure = it }
                    .getOrNull() ?: return@withContext null
                runCatching { Bitmaps.decodeThumbnail(source, PREVIEW_MAX_PX)?.asImageBitmap() }
                    .onFailure { failure = it }
                    .getOrNull()
            }
            if (bitmap == null) {
                notePreviewFailure(obj, previewTrouble(failure))
                return@launch
            }

            val index = stack.indexOfLast { it.obj.id == obj.id }
            val top = stack.getOrNull(index) ?: return@launch
            val refreshed = top.copy(preview = bitmap)
            stack[index] = refreshed
            _ui.update { if (it.frame?.obj?.id == obj.id) it.copy(frame = refreshed) else it }
        }
    }

    /**
     * Чем именно сорвался предпросмотр — словами того, кто это видел (#1271).
     *
     * Дальше решает одно правило [readerFailureIsFatal], общее с чтением снимка. Своих слов
     * у этого разбора нет и быть не может: «байты не разобрались» произносит только тот, кто
     * их видел (#1258). На пути предпросмотра кроме ридера есть и запись готовой страницы в
     * scratch, и пропавший файл — их отказы про попытку, и выдавать их за приговор объекту
     * нельзя: снять метку негодности в сеансе нечем.
     *
     * Ни одна ступень не бросила, файл был на месте, а снимка нет — вот это и значит, что
     * байты не разобрались: сигнал назван, а не передан молчанием.
     */
    private fun previewTrouble(failure: Throwable?): String =
        if (failure == null) READER_NOT_DECODED else failure.message.orEmpty()

    /**
     * Сорвавшийся предпросмотр говорит либо о самом объекте, либо о попытке (#685, #1271).
     *
     * Испорченный файл, документ без страниц — знание: оно остаётся с объектом и после этого
     * кадра, а не гаснет вместе с надписью. Пароль, исчезнувший файл, нехватка памяти —
     * состояние операции (Конституция §13, §18.13): человеку сказано, что не вышло, но
     * объект негодным не объявляется, иначе до конца сеанса ему нечем попробовать ещё раз.
     * Правило то же, каким пользуется чтение снимка, — второго разбора негодности нет.
     *
     * Уже отмеченный объект не переотмечается: сюда можно попасть дважды при повторном
     * открытии одного узла.
     */
    private fun notePreviewFailure(obj: PointObject, trouble: String) {
        val known = stack.lastOrNull { it.obj.id == obj.id }?.obj ?: return
        if (known.state.has(Feature.UNUSABLE)) return
        val said = readerFailure(trouble, obj.state.kind)
        applyEnrichment(
            obj,
            if (readerFailureIsFatal(trouble)) {
                EnrichmentUpdate(
                    features = setOf(Feature.UNUSABLE),
                    metadata = mapOf(META_UNUSABLE_REASON to said),
                    running = emptyList(),
                )
            } else {
                EnrichmentUpdate(
                    features = emptySet(),
                    metadata = emptyMap(),
                    running = emptyList(),
                    failed = listOf(FailedInvestigation(PREVIEW, null, said)),
                )
            },
        )
    }

    /**
     * Прочитанное показывается человеку, чем бы объект ни был (#792, решение владельца
     * 11.08.2026: «текст виден»).
     *
     * Раньше превью грузилось только у текстового объекта, поэтому снимок, прочитанный
     * фоновым исследованием, молчал: список действий уже знал про текст, а человек — нет и
     * проверить прочитанное не мог.
     */
    /**
     * Прочитанное на той стороне остаётся прочитанным здесь (#811, ADR-0001 §20).
     *
     * Текст живёт файлом устройства, и ссылка на него в пути не значит ничего: объект
     * приезжал снова непрочитанным, и вторая сторона предлагала работу, которая уже сделана.
     * Приехавшее значение здесь снова становится знанием — файлом рядом с объектом и
     * признаком «текст есть», как это давно делает компьютер на приёме.
     */
    private suspend fun withTravelledText(
        obj: PointObject,
        carried: Map<String, String>,
    ): Pair<PointObject, Map<String, String>> {
        val text = com.point.core.flow.textArrivedFromTravel(carried) ?: return obj to carried
        val kept = runCatching {
            store.newScratchFile("txt").also { java.io.File(it.value).writeText(text) }.value
        }.getOrNull()

        val landed = com.point.core.flow.knowledgeArrivedFromTravel(carried, kept)
        return obj.copy(state = landed.features.fold(obj.state) { acc, f -> acc.with(f) }) to landed.metadata
    }

    private fun loadTextPreviewIfText(obj: PointObject) {
        // Откуда брать текст объекта, решает общее с компьютером правило (#995): прочитанное,
        // а если его нет — собственное содержимое там, где оно и есть текст.
        val ref = com.point.core.flow.shownTextRef(obj) ?: return
        viewModelScope.launch {
            val limit = com.point.core.ui.TEXT_PREVIEW_LOAD_LIMIT
            val source = if (ref == obj.uri.value) obj else obj.copy(uri = com.point.core.model.ScratchRef(ref))
            val raw = runCatching { store.readText(source, limit = limit) }.getOrDefault("")
            if (raw.isBlank()) return@launch
            val text = sanitizeTextPreview(raw)

            val topIndex = stack.lastIndex
            val top = stack.getOrNull(topIndex) ?: return@launch
            if (top.obj.id != obj.id) return@launch

            // Честность «Показать целиком» (#682/#683): считается по сырому чтению, а не
            // по санитайзеру — тот может укоротить текст и спрятать, что предел был достигнут.
            val refreshed = top.copy(textPreview = text, textPreviewTruncated = raw.length >= limit)
            stack[topIndex] = refreshed
            _ui.update { if (it.frame?.obj?.id == obj.id) it.copy(frame = refreshed) else it }
        }
    }

    private fun loadChildrenIfCollection(obj: PointObject) {
        if (obj.state.kind != ObjectKind.COLLECTION) return
        viewModelScope.launch {
            val content = runCatching { store.children(obj) }
                .getOrDefault(CollectionContent.empty())
            if (content.shown.isEmpty()) return@launch

            val topIndex = stack.lastIndex
            val top = stack.getOrNull(topIndex) ?: return@launch
            if (top.obj.id != obj.id) return@launch

            val refreshed = top.copy(
                items = content.shown,
                itemsTotal = content.total,
                itemsTotalAtLeast = content.atLeast,
            )
            stack[topIndex] = refreshed
            _ui.update { if (it.frame?.obj?.id == obj.id) it.copy(frame = refreshed) else it }
        }
    }

    private fun enrichInBackground(obj: PointObject) {

        // Слово, стоявшее на экране в момент вопроса (#1000): всё сказанное человеку после
        // него — свежее ответа про область, и ответ его не перебивает.
        val saidBefore = _ui.value.message
        val job = viewModelScope.launch {

            // Состояние операции, а не знания (ADR-0001 §9): сорвался проход целиком или
            // отдельный вопрос под областью — разбор не закончен, и «в области ничего не
            // нашлось» было бы сказано про недоделанное.
            var whole = true
            enrichment.enrich(obj)
                .catch { whole = false }
                .collect { update ->
                    if (update.failed.isNotEmpty()) whole = false
                    applyEnrichment(obj, update)
                }

            stack.lastOrNull { it.obj.id == obj.id }?.let { frame ->
                if (frame.obj.state.features.isNotEmpty()) runCatching { history.update(frame.obj) }
            }

            // Проход дошёл до конца — у вопроса, заданного областью, есть ответ (#1000).
            if (whole) answerAskedArea(obj, saidBefore)
        }
        enrichJobs += EnrichWork(obj.id, job)
    }

    /**
     * Знание из пользовательского действия. Носитель смыслового факта — кадр-источник:
     * правка на кадре извлечённого значения дойдёт и до родителя, иначе следующий пересбор
     * узлов из родительской metadata затёр бы её.
     *
     * Прочтение же — своё у каждого объекта (#1023). Вырезка из снимка читается сама по
     * себе, и её текст прочтением страницы не является: наверх идёт смысловой факт, а
     * собственное знание объекта — ссылки на его слои, курсор чтения, порядок страниц
     * ([REFRESHABLE_META]) — остаётся у него. Иначе исправленный текст выделения вставал
     * прочтением родителя, и страница теряла своё: знание об объекте подменялось знанием
     * о его части.
     */
    private fun landFindings(findings: com.point.core.model.Findings) {
        val update = EnrichmentUpdate(
            features = findings.features,
            metadata = findings.metadata,
            running = emptyList(),
            objects = findings.objects,
            relations = findings.relations,
        )
        val top = stack.lastOrNull() ?: return
        applyEnrichment(top.obj, update)
        top.obj.sourceObjects.firstOrNull()
            ?.let { parentId -> stack.lastOrNull { it.obj.id == parentId }?.obj }
            ?.let { parent -> applyEnrichment(parent, update.copy(metadata = update.metadata - REFRESHABLE_META)) }

        // Карточка «Недавнего» несёт факты объекта: правка человека обязана дойти и до неё
        // сейчас — фоновое обогащение могло уже завершиться и историю не перепишет.
        setOfNotNull(top.obj.id, top.obj.sourceObjects.firstOrNull()).forEach { id ->
            stack.lastOrNull { it.obj.id == id }?.let { frame ->
                viewModelScope.launch { runCatching { history.update(frame.obj) } }
            }
        }

        // Существенное обогащение меняет пространство возможностей (ADR-0001 §10):
        // исследования пересматриваются над обновлённым объектом. Отвеченные вопросы
        // не переспрашиваются — их держат состояния знания.
        stack.lastOrNull { it.obj.id == top.obj.id }?.let { enrichInBackground(it.obj) }

        // Прибывший объект (результат компьютера) — не просто доставленные байты:
        // он сразу продолжает цикл понимания, и его знание видно у находки без входа
        // (Product Constitution PC2). Вход в находку получит объект уже понятым.
        findings.objects.forEach { born -> enrichFoundInBackground(born, top.obj.id) }
    }

    /** Понимание найденного узла: знание ложится в сам узел кадра-хозяина. */
    private fun enrichFoundInBackground(node: PointObject, hostId: String) {
        val job = viewModelScope.launch {
            enrichment.enrich(node)
                .catch { }
                .collect { update ->
                    if (update.features.isEmpty() && update.metadata.isEmpty()) return@collect
                    val index = stack.indexOfLast { it.obj.id == hostId }
                    val frame = stack.getOrNull(index) ?: return@collect
                    val updated = frame.found.map { n ->
                        if (n.id != node.id) {
                            n
                        } else {
                            n.copy(
                                state = update.features.fold(n.state) { s, f -> s.with(f) },
                                metadata = com.point.core.flow.mergeKnowledge(
                                    n.metadata,
                                    update.metadata,
                                    REFRESHABLE_META,
                                    phoneRegion.code(),
                                ),
                            )
                        }
                    }
                    if (updated == frame.found) return@collect
                    val refreshed = frame.copy(found = updated)
                    stack[index] = refreshed
                    _ui.update { if (it.frame?.obj?.id == hostId) it.copy(frame = refreshed) else it }
                    persistJourney()
                }
        }
        enrichJobs += EnrichWork(node.id, job)
    }

    private fun applyEnrichment(source: PointObject, update: EnrichmentUpdate) {
        val index = stack.indexOfLast { it.obj.id == source.id }
        val frame = stack.getOrNull(index) ?: return
        val newState = update.features.fold(frame.obj.state) { state, feature -> state.with(feature) }

        val newMetadata = com.point.core.flow.mergeKnowledge(
            frame.obj.metadata,
            update.metadata,
            REFRESHABLE_META,
            phoneRegion.code(),
        )

        // Тот же id — тот же объект: свежий результат (повтор действия на PC, пересбор узла)
        // занимает место прежнего, а не отбрасывается. Порядок появления сохраняется.
        //
        // Занимает место — но не стирает знание (#769). Одного человека объявляют двое:
        // роль на документе даёт имя, пара «имя + номер» — телефон. Прежде второй молча
        // заменял первого целиком, и внутри найденного человека не оставалось ни телефона,
        // ни признака, по которому предлагают «Сохранить контакт».
        val newFound = (frame.found + update.objects)
            .groupBy { it.id }
            .map { (_, nodes) -> nodes.reduce(::sameNodeKnown) }
            .map { node -> syncNodeFact(node, newMetadata) }
        val enriched = frame.obj.copy(state = newState, metadata = newMetadata)

        // Связи копятся, а принадлежность — заменяется и снимается (#1176): у номера один
        // хозяин, второй виток поправляет прежнего, а не добавляет второго; узел, чей факт
        // сменился, прежнего хозяина не наследует — как `.of` в знании объекта.
        val newRelations = com.point.core.flow.mergedRelations(
            frame.relations,
            update.relations,
            renamed = com.point.core.flow.renamedNodes(frame.found + frame.obj, newFound + enriched),
        )

        // Неудача — состояние операции: показывается человеку, но не становится знанием
        // и не переживает журнал. Удавшийся повтор снимает упрёк по своему вопросу.
        val newFailed = (frame.failed + update.failed)
            .distinctBy { it.id to it.reason }
            .filter {
                com.point.core.flow.investigationStateOf(newMetadata, it.id) !=
                    com.point.core.flow.InvestigationState.FOUND
            }
        val objChanged = newState != frame.obj.state || newMetadata != frame.obj.metadata
        val graphChanged = newFound != frame.found || newRelations != frame.relations
        if (!objChanged && !graphChanged && update.running == frame.enriching && newFailed == frame.failed) return

        val newGraph = if (objChanged) {
            graphOf(
                frame.copy(found = newFound, relations = newRelations, enriching = update.running),
                enriched,
            )
        } else {
            null
        }
        val newBubbles = if (newGraph != null) {
            com.point.core.model.keepShownOrder(frame.bubbles, registry.bubblesFor(newGraph))
        } else {
            frame.bubbles
        }
        val refreshed = frame.copy(
            obj = enriched,
            bubbles = newBubbles,

            // Подсказки видят то же знание, что и двери (#1101).
            latent = if (newGraph != null) registry.latentBubblesFor(newGraph) else frame.latent,
            enriching = update.running,
            found = newFound,
            relations = newRelations,
            failed = newFailed,
        )
        stack[index] = refreshed
        _ui.update { if (it.frame?.obj?.id == source.id) it.copy(frame = refreshed) else it }

        // Текст приходит обогащением, а не при открытии кадра: снимок читается фоново уже
        // после того, как экран показан (#792). Сильное чтение заменяет прочтение целиком
        // (#1097) — прежнее превью при сменившемся чтении не оставляется.
        val readingChanged =
            frame.obj.metadata[com.point.core.flow.META_OCR_TEXT_REF] != newMetadata[com.point.core.flow.META_OCR_TEXT_REF]
        if (refreshed.textPreview == null || readingChanged) loadTextPreviewIfText(enriched)
        if (objChanged || graphChanged) {
            persistJourney()
        }
    }

    /**
     * Узел и факт родителя — одно значение в двух ролях (ADR-0001 §4): после ЛЮБОГО merge,
     * сменившего primary (человек, машинный repair), узел зеркалит факт кадра — значение,
     * историю `.alt` и происхождение. Идентичность узла не меняется.
     */
    /**
     * Один и тот же узел, объявленный дважды: знание складывается, а не заменяется (#769).
     *
     * На почтовой наклейке человека объявляют двое — роль на документе даёт имя, пара
     * «имя + номер» даёт телефон. Свежее значение по спорному ключу побеждает, но ключи и
     * признаки прежнего остаются: иначе телефон исчезал вместе с «Сохранить контакт».
     */
    private fun sameNodeKnown(known: PointObject, fresh: PointObject): PointObject =
        fresh.copy(
            metadata = com.point.core.flow.mergeFacts(known.metadata, fresh.metadata),
            state = fresh.state.copy(features = known.state.features + fresh.state.features),
        )

    private fun syncNodeFact(node: PointObject, merged: Map<String, String>): PointObject {

        // Узел «ещё одного» значения (id вида host:вид:значение) — другой объект того же
        // вида: зеркалить его под primary кадра значит затирать второй телефон первым
        // (живой прогон S6). Его правда — его собственное значение.
        val hostId = node.sourceObjects.firstOrNull()
        if (hostId != null && node.id.removePrefix("$hostId:").contains(':')) return node
        val key = node.metadata.keys.firstOrNull {
            (
                it.startsWith(com.point.core.flow.META_ENTITY_PREFIX) ||
                    it.startsWith(com.point.core.flow.META_GRAPH_ROLE_PREFIX)
                ) &&
                !com.point.core.flow.isAnnotationKey(it) && !com.point.core.flow.isStateKey(it)
        } ?: return node
        val primary = merged[key] ?: return node
        if (com.point.core.flow.normConsensus(primary) ==
            com.point.core.flow.normConsensus(node.metadata[key].orEmpty())
        ) {

            // Значение не изменилось, но слово могло стать весомее: подтверждение
            // человеком того же значения обязано быть видно и на узле.
            val stronger = com.point.core.flow.provenanceOf(merged, key)
            if (stronger <= node.provenance) return node
            val srcKey = key + com.point.core.flow.META_SOURCE_SUFFIX
            return node.copy(
                metadata = node.metadata + listOfNotNull(merged[srcKey]?.let { srcKey to it }),
                provenance = stronger,
            )
        }
        val alt = key + com.point.core.flow.META_ALT_SUFFIX
        val src = key + com.point.core.flow.META_SOURCE_SUFFIX
        val slice = buildMap {
            put(key, primary)
            merged[alt]?.let { put(alt, it) }
            merged[src]?.let { put(src, it) }
        }
        return node.copy(

            metadata = node.metadata - alt + slice,
            provenance = maxOf(node.provenance, com.point.core.flow.provenanceOf(merged, key)),
        )
    }

    internal companion object {

        /** Служебные ключи письма с компьютера — знанием объекта не являются. */
        val PC_SERVICE_META = setOf("name", "mime", "pc.action", "pc.action.label", "id")

        /** Как телефон называет себя в происхождении знания (#1127). */
        const val PHONE_EXECUTOR = "phone"

        /**
         * Предел одного действия (#1069): дольше — честный отказ, а не вечное «Идёт».
         *
         * Щедрый нарочно: таблица на эмуляторе строится минутами, облачный скан — тоже,
         * и предел должен резать только зависание, а не работу.
         */
        const val ACTION_CEILING_MS = 10L * 60 * 1000

        /**
         * Стадия суда, который ждёт чтения (#1060): человеку говорится, чего именно ждут.
         * Снимок — читается; у остальных объектов фоном идёт разбор, не чтение.
         */
        internal fun waitingForReadingStage(kind: ObjectKind): String =
            if (kind == ObjectKind.IMAGE) "Жду чтения снимка" else "Жду разбора объекта"


        val REFRESHABLE_META = com.point.core.flow.REFRESHABLE_KNOWLEDGE

        const val CLOUD_DECLINED =
            "Ничего не отправлено — объект остался на телефоне, действие не выполнено. " +
                "Без отправки оно не работает: тапните ещё раз, если передумаете"
    }

    private fun cancelEnrichment() {
        enrichJobs.forEach { it.job.cancel() }
        enrichJobs.clear()
    }
}

private const val MAX_CLIP = 2000

private const val OUTBOX_THROTTLE_MS = 30_000L

private const val CIRCLE_SYNC_THROTTLE_MS = 5 * 60_000L

private const val PREVIEW_MAX_PX = 640

/**
 * Чьей неудачей назван сорвавшийся предпросмотр (#1271).
 *
 * Показ объекта способностью не объявляется — человек его не выбирает. Имя здесь нужно
 * ровно затем, зачем оно нужно всякой неудаче операции: чтобы упрёк не слился с чужим и
 * повторный срыв не удвоил строку на экране.
 */
private val PREVIEW = CapabilityId("preview")

/** Миниатюра страницы в списке набора — того же размера, что и в «Недавнем» (#1207). */
private const val ITEM_THUMB_PX = 96

private const val SELECTION_MAX_PX = 2048
