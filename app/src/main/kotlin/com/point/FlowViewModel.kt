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
import com.point.core.flow.edgeDetail
import com.point.core.flow.EnrichmentUpdate
import com.point.core.flow.FlowSnapshotStore
import com.point.core.flow.HistoryStore
import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.PinnedActions
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.Resolver
import com.point.core.flow.SensoryFeedback
import com.point.core.flow.SensorySettings
import com.point.core.flow.UsageEvent
import com.point.core.flow.UsageEventType
import com.point.core.flow.UsageJournal
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.FrameTransform
import com.point.core.flow.META_CLOUD_ATOMS_REF
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_SELECTION_IDS
import com.point.core.flow.META_SELECTION_PAGE
import com.point.core.flow.META_SELECTION_REGION
import com.point.core.flow.META_SELECTION_SOURCE
import com.point.core.flow.META_YIELD_NOUN
import com.point.core.flow.SnappedSelection
import com.point.core.flow.UserAiConfig
import com.point.core.flow.UserKeyStore
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
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Owns the flow as a stack of [FlowFrame]s (the whole navigation model). The top
 * frame is rendered. Back pops; the scratch store is cleared when the flow ends.
 * Records each object into History and each capability into the frame provenance,
 * from which the journey path (the timeline above the object) is built.
 */
@HiltViewModel
class FlowViewModel @Inject constructor(
    private val store: ObjectStore,
    private val registry: CapabilityRegistry,
    private val resolver: Resolver,
    private val aiChatResponder: com.point.core.flow.AiChatResponder,
    private val enrichment: Enrichment,
    private val history: HistoryStore,
    private val usage: CapabilityUsage,
    private val chosenApps: ChosenApps,
    private val userKeys: UserKeyStore,
    private val journal: UsageJournal,
    private val consent: PrivacyConsent,
    private val appLauncher: AppLauncher,
    private val pdfRasterizer: PdfRasterizer,
    private val sensory: SensoryFeedback,
    private val sensorySettings: SensorySettings,
    private val cloudPrivacy: com.point.core.flow.CloudPrivacySettings,
    private val flowSnapshot: FlowSnapshotStore,
    private val crashLog: CrashLog,
    private val ioDispatcher: CoroutineDispatcher,
    private val pins: PinnedActions,
    private val appIcons: AppIconResolver,
    private val pcLinks: com.point.core.flow.PcLinks,
    private val pcTransport: com.point.core.flow.PcTransport,
    private val pcCaps: com.point.core.flow.PcCapsStore,
    /** Кто помнит, когда компьютер отвечал в последний раз (#412). */
    private val linkMonitor: com.point.core.flow.LinkMonitor,
    private val pulledFiles: PulledFileFactory,
    private val frames: SelectionFrames,
    /** Кто стучится в сервис ключом человека, когда тот просит проверить (#465). */
    private val aiKeyCheck: com.point.core.flow.AiKeyCheck,
    /** Пропуск аккаунта на этом устройстве (#472). */
    private val accountStore: com.point.core.flow.AccountStore,
    /** Разговор с сервером Point: вход, круг устройств, отзыв (#472). */
    private val accountClient: com.point.core.flow.AccountClient,
    /** Начатый, но не законченный вход (#561): он переживает экран, потому что человек уходит в браузер. */
    private val pendingLogins: com.point.core.flow.PendingLoginStore,
    /** Ключи этого телефона (#475): открытая половина едет в круг, закрытая остаётся здесь. */
    private val deviceKeys: com.point.core.flow.DeviceKeyStore,
    /** Открыть системный браузер — единственное, что вход просит у платформы (#472). */
    private val browser: com.point.core.flow.BrowserOpener,
    /** Временные копии расшаренного текста: их заводит `:app`, их же и убирает в конце флоу. */
    private val sharedTexts: com.point.core.flow.SharedTexts,
) : ViewModel() {

    /**
     * Задача той работы, что подняла экран ожидания, — чтобы её можно было отменить (#288, #114).
     *
     * Держат её ВСЕ занятости, а не одно действие по пузырю: «Ищу приложения…», «Открываю…»,
     * «Забираю с компьютера…» тоже рисуют «Отменить», и кнопка обязана снимать ту работу, над
     * которой стоит. Задача обнуляется по завершении: снимать законченное значило бы объявлять
     * отменённым уже сделанное.
     */
    private var busyJob: kotlinx.coroutines.Job? = null

    /** Эта задача теперь и есть идущая занятость. По завершении поле гаснет само. */
    private fun trackWork(job: kotlinx.coroutines.Job) {
        busyJob = job
        job.invokeOnCompletion { if (busyJob === job) busyJob = null }
    }

    /**
     * Поднять экран ожидания одним движением — и сразу сказать, можно ли эту работу отменить.
     *
     * [cancelable] стоит `true` только там, где [cancelAction] действительно снимает задачу И
     * человеку есть куда вернуться (объект под экраном или «Недавнее»). Приём расшаренного
     * файла — не такой случай: за экраном ожидания нет ничего, и «Отменено» осталось бы
     * единственным, что человек видит.
     */
    private fun raiseBusy(
        title: String,
        network: Boolean = false,
        quiet: Boolean = false,
        cancelable: Boolean = false,
    ) {
        _ui.update {
            it.copy(
                busy = title, busyStage = null, busyNetwork = network, busyQuiet = quiet,
                busyCancelable = cancelable,
                message = null, messageOutcome = Outcome.NONE, inputPrompt = null,
            )
        }
    }

    /**
     * Эта работа всё ещё та, что на экране?
     *
     * Отмена и любая новая занятость забирают голос ([claimVoice]). Снятая работа обязана не
     * только замолчать, но и **не применить свой результат**: нативный проход движка о прерывании
     * не знает и доходит до конца сам — без этой проверки объект открывался секундой позже поверх
     * слова «Отменено».
     */
    private fun owns(voice: Long) = voice == workVoice

    /**
     * Чей голос сейчас на экране (#288): номер занятости, которой принадлежит `busyStage`.
     *
     * Отмена и смена действия снимают задачу, но не саму работу: нативный проход Tesseract и
     * отрисовка страниц о прерывании не знают и договаривают начатое. Их `reportStage` попадал
     * в живое состояние уже над ДРУГОЙ занятостью — и объект подписывался словами работы,
     * которой больше нет. Это та же подмена статуса, ради которой затевался срез, только
     * чужими словами вместо выдуманных, и обнулением стадии в начале новой работы она не
     * лечится: снятая работа заговаривает уже ПОСЛЕ обнуления.
     */
    @Volatile private var workVoice = 0L

    /** Новая занятость забирает голос у прошлой: та замолкает, даже если ещё дышит. Вызывается
     *  везде, где ставится `busy`, — «Открываю…» чужих слов носить тоже не должно. */
    private fun claimVoice(): Long = ++workVoice

    private val stack = ArrayDeque<FlowFrame>()
    private val enrichJobs = mutableListOf<Job>()
    private var pendingBubble: Bubble? = null
    /** A cloud action deferred until the user grants consent (#10); run on confirm. */
    private var pendingCloud: (() -> Unit)? = null
    /** На что именно спрашиваем сейчас (#114) — от этого зависит, запоминать ли ответ. */
    private var pendingCloudScope: com.point.core.flow.CloudScope = com.point.core.flow.CloudScope.MODELS
    /** A bubble whose preview is shown, deferred until the user confirms it (#97). */
    private var pendingPreviewBubble: Bubble? = null
    /** Экран выделения (#259): слой, преобразование координат и последний захват — живут,
     *  пока экран открыт; текст ячейки собирают атомы, модель здесь не участвует вовсе. */
    private var selectionLayer: AtomLayer? = null
    private var selectionTransform: FrameTransform? = null
    private var selectionSnap: SnappedSelection? = null
    /** Экран поиска (#279): та же пара «слой + преобразование координат», что у выделения, —
     *  живёт, пока экран открыт. Своя пара, а не общая с выделением: два экрана открываются
     *  независимо, и закрытие одного не имеет права обнулять страницу другого. */
    private var findLayer: AtomLayer? = null
    private var findTransform: FrameTransform? = null

    private val _ui = MutableStateFlow(FlowUiState())
    val ui: StateFlow<FlowUiState> = _ui.asStateFlow()

    private val _recent = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val recent: StateFlow<List<HistoryEntry>> = _recent.asStateFlow()

    private val _crashReport = MutableStateFlow<String?>(null)
    /** A previous crash report, offered once for an explicit share (#11). */
    val crashReport: StateFlow<String?> = _crashReport.asStateFlow()

    private val _fromPcCount = MutableStateFlow(0)
    /** Objects waiting in the paired PC's outbox (#161) — Home offers to pull them here. */
    val fromPcCount: StateFlow<Int> = _fromPcCount.asStateFlow()
    private var fromPcEntries: List<com.point.core.flow.PcOutboxEntry> = emptyList()
    private var lastOutboxFetchMs = 0L
    private var lastCircleSyncMs = 0L

    private val _clipboard = MutableStateFlow<String?>(null)
    /** Actionable text sitting in the clipboard when Point opened — a dismissible Home suggestion (#72). */
    val clipboard: StateFlow<String?> = _clipboard.asStateFlow()
    private var lastClipboard: String? = null

    /** Set synchronously by a fresh share BEFORE its coroutine runs — a stale snapshot
     *  must never race over the user's new intent (#7). */
    private var freshShareArrived = false

    init {
        viewModelScope.launch { _crashReport.value = runCatching { crashLog.pending() }.getOrNull() }
    }

    /** Real launcher icon for an app-capability bubble; null → stock glyph (#66). */
    fun appIcon(packageName: String): androidx.compose.ui.graphics.ImageBitmap? =
        runCatching { appIcons.iconFor(packageName) }.getOrNull()

    /** The user saw (and maybe shared) the crash report - forget it either way. */
    fun dismissCrashReport() {
        _crashReport.value = null
        viewModelScope.launch { runCatching { crashLog.clear() } }
    }

    /** #7: re-materialise the flow after process death. Scratch files survive (clear()
     *  runs only at flow end), so the journey resumes on the same object and step —
     *  features re-derive instantly from the kept metadata via enrichment. Opt-in: only
     *  ShareActivity calls this (a killed mid-share resumes), so the launcher icon
     *  (HomeActivity) always lands on Home — the last object stays in «Недавнее» to re-open. */
    fun restoreJourney() {
        viewModelScope.launch {
            val frames = runCatching { flowSnapshot.load() }.getOrDefault(emptyList())
            if (frames.isEmpty() || freshShareArrived || stack.isNotEmpty()) return@launch
            // An extracted object (#222) has no file to check for — its ref IS its value, so it
            // survives on its own. Only file-backed frames die with their bytes.
            val alive = frames.filter {
                !it.kind.isFileBacked || runCatching { java.io.File(it.ref).isFile }.getOrDefault(false)
            }
            if (alive.isEmpty()) {
                runCatching { flowSnapshot.clear() }
                return@launch
            }
            // The found objects themselves are not journaled: their facts live in the source's
            // `entity.*` metadata, which IS journaled, and MetadataEntityEnricher rebuilds them
            // with the same ids on the way back (#222). Facts survive, bytes need not.
            alive.forEach { f ->
                pushFrame(
                    PointObject(f.id, f.mime, refFor(f.kind, f.ref),
                        com.point.core.model.ObjectState(f.kind), f.metadata),
                    via = f.viaCapabilityId?.let { CapabilityId(it) },
                    viaTitle = f.viaTitle,
                )
            }
        }
    }

    /**
     * Расшаренный текст входит во флоу файлом — тем же путём, что и всё остальное.
     *
     * Файл заводит [SharedTexts], а не сама Activity: тогда его есть кому убрать в конце флоу.
     * Имя объекту даёт содержимое, а не временный файл: `shared-5631909340713910696.txt` — самое
     * частое, что человек шлёт в Point, и первое, что он потом читает в «Недавнем».
     */
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

    /**
     * Пример из ресурсов Point (#210) — и дальше это обычный объект.
     *
     * Всё, чем он отличается от принесённого человеком, кончается на этой строке: дальше та же
     * дверь [onShared], та же копия в рабочую папку, те же действия, та же запись в «Недавнее» и
     * та же обязательная уборка в конце флоу. Своего режима, своих экранов и своих действий у
     * примера нет намеренно — «песочница» тем и является, что песка в ней нет: показывать продукт
     * его подделкой значит показать не тот продукт.
     *
     * Заводить ради этого источник (`ObjectSource`) было бы неправдой в другую сторону: источники
     * перечислены подписью двери «Новый объект», а пример — не то место, откуда человек берёт
     * СВОИ объекты.
     */
    fun openExample(example: ExampleObject) =
        onShared(example.uri, example.mime, name = example.name)

    /**
     * Пришло то, чего Point разобрать не смог.
     *
     * Раньше в этом месте не происходило ничего: экран оставался пустым и чёрным, без слова и без
     * выхода. Отказ обязан быть виден — иначе человек не знает даже, дошёл ли его файл.
     */
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

    /**
     * [name] — имя объекта, если дверь знает его лучше файловой системы (#533).
     *
     * Приёмник (`ObjectStore`) называет объект по имени файла — единственному, что у него есть.
     * Для того, что Point родил сам, это имя машинное (`shared-563190….txt`, `record-1754….m4a`),
     * и человеческое знает только источник. Поэтому имя ставится здесь, ОДНИМ местом на все двери:
     * иначе «Недавнее» и экран объекта разъехались бы в том, как объект называется.
     */
    fun onShared(sourceUri: String, mime: String, autoAction: String? = null, name: String? = null) {
        freshShareArrived = true
        // Круг спрашивается фоном на каждом входе в Point (#475): иначе пузырёк
        // «На компьютер» появлялся бы только после того, как человек сам откроет «Мои
        // устройства», — то есть ровно та молчаливая задержка, из-за которой связь и
        // казалась случайной. Сети на первом экране это не добавляет: запрос уходит в фон.
        syncCircle()
        val voice = claimVoice()
        // Отменить нечем: за приёмом расшаренного файла экрана Point ещё нет, и кнопка увела бы
        // человека в пустоту с одним словом «Отменено» (#114).
        raiseBusy("Открываю…", cancelable = false)
        trackWork(viewModelScope.launch {
            val obj = runCatching {
                store.clear()
                store.ingest(sourceUri, mime)
            }.getOrNull()?.let { ingested ->
                if (name.isNullOrBlank()) ingested
                else ingested.copy(metadata = ingested.metadata + ("name" to name))
            }
            if (!owns(voice)) return@launch
            if (obj == null) {
                // Хвост исключения человеку ничего не говорит («…FileNotFoundException: /storage/…»),
                // а теперь этот текст стоит под объектом первой строкой. Отказ называется словами;
                // техническая причина при этом не пропадает — её пишет сам приёмник (ObjectStore),
                // иначе разбитый шаринг остался бы без единого следа где бы то ни было.
                _ui.update { it.copy(busy = null, busyStage = null, message = "Не удалось открыть объект", messageOutcome = Outcome.FAILED) }
                return@launch
            }
            runCatching { history.record(obj) }
            runCatching { journal.record(UsageEvent(UsageEventType.SHARED, obj.state.kind.name)) }
            cancelEnrichment()
            stack.clear()
            pushFrame(obj)
            // #161 v2: the PC named an intent for this object — run it as if tapped. Имена
            // действий у двух половинок свои, и версии расходятся: незнакомое имя — это фраза
            // на уже открытом объекте, а не смерть процесса. Единственный вызов реестра, что
            // оставался без страховки; объект к этому моменту скачан, и терять его нельзя.
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
        })
    }

    /** Several shared files → one COLLECTION (the inbound half of collections;
     *  e.g. several photos to merge into a PDF). */
    fun onSharedMultiple(sources: List<String>) {
        freshShareArrived = true
        val voice = claimVoice()
        raiseBusy("Открываю…", cancelable = false) // как и в onShared: возвращаться некуда
        trackWork(viewModelScope.launch {
            val obj = runCatching {
                store.clear()
                store.ingestMultiple(sources)
            }.getOrNull()
            if (!owns(voice)) return@launch
            if (obj == null) {
                // То же, что в onShared: человеку — словами, причина — в логе приёмника.
                _ui.update { it.copy(busy = null, busyStage = null, message = "Не удалось открыть объект", messageOutcome = Outcome.FAILED) }
                return@launch
            }
            // A collection is a transient scratch directory — History copies a single file, so skip it.
            runCatching { journal.record(UsageEvent(UsageEventType.SHARED, obj.state.kind.name)) }
            cancelEnrichment()
            stack.clear()
            pushFrame(obj)
        })
    }

    fun loadRecent() {
        // Есть ли ключ — «Недавнее» спрашивает об этом здесь, а не на экране объекта: приглашение
        // подключить AI живёт на домашнем экране, и бюджет первого экрана (≤300 мс) не трогается.
        _ui.update { it.copy(aiKeySet = runCatching { userKeys.read() != null }.getOrDefault(false)) }
        viewModelScope.launch {
            _recent.value = runCatching { history.recent() }.getOrDefault(emptyList())
        }
        refreshFromPc()
        syncCircle()
    }

    /** Quietly ask the paired PC for its outbox (#161) — throttled so app switches with the
     *  PC away don't burn a connect timeout every time; failures just mean no banner. */
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

    /** Pull everything the PC queued (#161): download → ingest → ack, in that order —
     *  a failed ack re-offers (at-least-once); a failed download acks nothing. */
    fun pullFromPc() {
        val pc = pcLinks.current() ?: return
        val voice = claimVoice()
        // Отмена настоящая: качать по сети можно долго, а вернуться есть куда — в «Недавнее».
        raiseBusy("Забираю с компьютера…", cancelable = true)
        trackWork(viewModelScope.launch {
            // Pull what is on the PC RIGHT NOW — a fresh fetch, not the throttled banner snapshot. The
            // cached list can be up to OUTBOX_THROTTLE_MS stale, so an object queued after the last
            // fetch would be missed and a stale one pulled instead — the phone got «не то» (#161).
            val entries = runCatching { pcTransport.fetchOutbox(pc) }.getOrNull().orEmpty()
            if (!owns(voice)) return@launch
            if (entries.isEmpty()) {
                fromPcEntries = emptyList()
                _fromPcCount.value = 0
                _ui.update { it.copy(busy = null) }
                return@launch
            }
            val pulled = entries.map { entry ->
                val name = entry.meta["name"] ?: "объект"
                val path = pulledFiles.create("${entry.id}-$name")
                val ok = runCatching { pcTransport.downloadOutboxFile(pc, entry.id, path) }.getOrDefault(false)
                Triple(entry, path, ok)
            }
            if (!owns(voice)) return@launch // передумали на полпути — скачанное не открываем
            if (pulled.any { !it.third }) {
                _ui.update { it.copy(busy = null, busyStage = null, message = com.point.core.flow.pcUnreachableText(com.point.core.flow.PcUnreachable.PC_ASLEEP), messageOutcome = Outcome.FAILED) }
                return@launch
            }
            when (pulled.size) {
                1 -> onShared(
                    "file://${pulled[0].second}",
                    pulled[0].first.meta["mime"] ?: "application/octet-stream",
                    autoAction = pulled[0].first.meta["pc.action"]?.takeIf { it.isNotBlank() },
                )
                else -> onSharedMultiple(pulled.map { "file://${it.second}" })
            }
            pulled.forEach { (entry, _, _) ->
                runCatching { pcTransport.ackOutbox(pc, entry.id) }
                    .recoverCatching { pcTransport.ackOutbox(pc, entry.id) }
            }
            fromPcEntries = emptyList()
            _fromPcCount.value = 0
        })
    }

    /** Hide the banner until the next fetch — the objects stay on the PC (no ack). */
    fun hideFromPc() {
        _fromPcCount.value = 0
    }

    /** Wipe the recent list and its files — the user's "очистить недавнее" (#8). */
    fun clearHistory() {
        viewModelScope.launch {
            runCatching { history.clearAll() }
            _recent.value = emptyList()
        }
    }

    /**
     * Offer to act on clipboard text when Point opens — any non-blank text that wasn't already
     * dismissed. The Activity reads the clipboard **foreground-only** (Android 10+ rule); Point
     * never watches the clipboard in the background (#72). Reaches messengers: copy → open Point → act.
     */
    fun offerClipboard(text: String?) {
        val t = text?.trim().orEmpty()
        _clipboard.value = t.takeIf { it.isNotBlank() && it.length <= MAX_CLIP && it != lastClipboard }
    }

    /**
     * Re-read the clipboard when the Home list (re)appears mid-session. After Back out of a
     * restored flow the window-focus edge is long gone — without this, copied text is silently
     * ignored exactly when the user came to act on it (#111).
     */
    fun refreshClipboard(reader: () -> String?) {
        if (hasFlow()) return
        offerClipboard(reader())
    }

    /** Dismiss the clipboard suggestion and remember it, so the same text is not re-offered. */
    fun dismissClipboard() {
        lastClipboard = _clipboard.value
        _clipboard.value = null
    }

    fun openFromHistory(entry: HistoryEntry) {
        freshShareArrived = true
        val voice = claimVoice()
        // Пришли с «Недавнего» — туда же и возвращаемся, если человек передумал.
        raiseBusy("Открываю…", cancelable = true)
        trackWork(viewModelScope.launch {
            val obj = runCatching { history.open(entry.id) }.getOrNull()
            if (!owns(voice)) return@launch
            if (obj == null) {
                _ui.update { it.copy(busy = null, busyStage = null, message = "Объект недоступен", messageOutcome = Outcome.FAILED) }
                return@launch
            }
            runCatching { store.clear() }
            cancelEnrichment()
            stack.clear()
            pushFrame(obj)
        })
    }

    fun onBubble(bubble: Bubble) {
        val top = stack.lastOrNull()?.obj ?: return
        // Действие само сказало в своём имени, что без ключа не сможет (#465). Тап по нему — это
        // не запуск, а согласие сходить за ключом: гнать в реализатор то, чему нечем работать,
        // значит отнять у человека минуту ожидания ради отказа, известного ДО тапа.
        //
        // Прерванное действие при этом НИКУДА не запоминается — ни здесь, ни на экране ключей.
        // Человек вернётся к объекту, где оно стоит доступным и ждёт его тапа: «Point никогда не
        // строит автоматические цепочки», и «он же сам его только что нажал» — не исключение.
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
            // "Открыть в…" opens an inline picker of the device's real handlers (#66).
            showAppPicker(top)
            return
        }
        if (bubble.capabilityId == AiCapability.ID) {
            // #4: «Спросить AI» opens the multi-turn chat, not a one-shot field. Cloud consent (#10)
            // gates the conversation, since talking to the object leaves the device.
            requireCloudConsent { openChat(top) }
            return
        }
        if (bubble.capabilityId == FindCapability.ID) {
            // #279: «Найти в документе» показывает места НА СТРАНИЦЕ, а не отвечает числом в
            // баннере, — поэтому тап открывает экран поиска (тот же перехват, что у чата и
            // «Открыть в…»). Реализатор отвечает на тот же вопрос там, где экрана нет.
            openFind()
            return
        }
        if (isCloud(bubble.capabilityId)) {
            // Nothing leaves the device before the user agrees, even once (#10).
            requireCloudConsent(bubble.capabilityId) { maybePreview(bubble, top) }
            return
        }
        maybePreview(bubble, top)
    }

    /** If the chosen realizer offers a preview (#97), show it and wait for confirm; otherwise run
     *  straight away. Busy is shown immediately (so feedback is instant and the preview computation —
     *  e.g. ML Kit for an address — is covered); the coroutine then reveals the preview or runs. */
    private fun maybePreview(bubble: Bubble, top: PointObject) {
        val voice = claimVoice()
        raiseBusy(
            bubble.title,
            network = isCloud(bubble.capabilityId),
            quiet = isQuietAction(bubble.capabilityId),
            cancelable = true,
        )
        trackWork(viewModelScope.launch {
            // Пузырёк нарисован, а исполнять его нечем (потерян `@IntoSet`): человеку — фраза
            // на его языке. Без этой развилки на экран уезжал текст исключения, написанный для
            // разработчика.
            val realizer = runCatching { resolver.realizerFor(bubble.capabilityId) }.getOrNull()
            if (!owns(voice)) return@launch
            if (realizer == null) {
                _ui.update { it.copy(busy = null, busyStage = null, message = "Действие недоступно", messageOutcome = Outcome.FAILED) }
                return@launch
            }
            val preview = runCatching { realizer.preview(top) }.getOrNull()
            if (!owns(voice)) return@launch
            if (preview == null) {
                // Задача поиска превью своё отработала — экраном дальше владеет действие. Снимаем
                // её с учёта до [dispatch]: иначе он снял бы предшественника, то есть нас самих.
                busyJob = null
                dispatch(bubble) { realizer.perform(top, null) }
            } else {
                pendingPreviewBubble = bubble
                _ui.update { it.copy(busy = null, busyStage = null, preview = preview) }
            }
        })
    }

    fun confirmPreview() {
        val bubble = pendingPreviewBubble ?: return
        val top = stack.lastOrNull()?.obj ?: return
        pendingPreviewBubble = null
        _ui.update { it.copy(preview = null) }
        runOnObject(bubble, top)
    }

    fun cancelPreview() {
        pendingPreviewBubble = null
        _ui.update { it.copy(preview = null) }
    }

    /**
     * Тап по герою-превью (#259): страница целиком, палец рисует рамку. Открывается только по
     * явному тапу и только когда слой слов уже прочитан — выделение не смеет стать обязательным
     * шагом, а без атомов прилипать не к чему (кроп «непрочитанного» — следующий срез).
     */
    /**
     * Тап по объекту (#290): смотришь на превью — тапнул — открылось.
     *
     * До этого тап по герою жил только ради выделения (#259) и на объекте без слоя слов не
     * делал НИЧЕГО: человек тапал по единственному крупному элементу экрана и получал тишину.
     * Тишина в ответ на прямое действие — та же ложь, что и заглушка вместо статуса.
     *
     * Открывает тем же путём, что кнопка «Открыть», — без дублирования поведения: одно
     * действие, один реализатор, одна запись в журнале.
     */
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
        // Слой слов необязателен (#259). Есть — рамка липнет к словам, и выделение даёт текст;
        // нет — берётся пустой слой, рамка остаётся свободной, а «Взять» уходит в фрагмент
        // (`fragmentCapture`). Требовать чтение до обводки значило заставлять человека
        // распознавать то, что он всего лишь хочет обвести.
        val atomsRef = top.metadata[META_OCR_ATOMS_REF]
        viewModelScope.launch {
            val loaded = withContext(ioDispatcher) {
                runCatching {
                    val layer = atomsRef
                        ?.let { AtomCodec.decode(File(it).readText()) }
                        ?: AtomLayer(emptyList())
                    frames.frame(top.uri.value, SELECTION_MAX_PX)?.let { frame ->
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
            _ui.update { it.copy(selection = SelectionUi(image = loaded.third)) }
        }
    }

    /** Рамка жеста в координатах показанной копии → притягивание к атомам в сыром кадре →
     *  построчная подсветка обратно в координатах копии. Чистая математика, всё уже оттестировано
     *  в ядре ([snapSelection], [FrameTransform]) — здесь только перевод туда-обратно. */
    fun onSelectRegion(display: Box) {
        val layer = selectionLayer ?: return
        val transform = selectionTransform ?: return
        val snap = layer.snapSelection(transform.toRaw(display))
        selectionSnap = snap
        _ui.update { state ->
            val sel = state.selection ?: return@update state
            state.copy(
                selection = sel.copy(
                    // Пустой захват показывает саму рамку: это то, что уйдёт фрагментом, и оно
                    // обязано быть видно до «Взять» — как и построчная подсветка для слов.
                    highlights = if (snap.atoms.isEmpty()) {
                        listOf(transform.toUpright(snap.region))
                    } else {
                        snap.lineRegions.map(transform::toUpright)
                    },
                    text = snap.text,
                ),
            )
        }
    }

    /** «Взять»: захват становится объектом графа с происхождением до сырого кадра (источник,
     *  метки атомов, рамка, страница). Слова → TEXT; пустой захват → фрагмент-изображение
     *  исходными пикселями (#259, путь «непрочитанного» — рукопись обводят, чтобы проверить
     *  глазами или отдать зрячей модели, и кроп не смеет потерять, откуда он взят). */
    fun takeSelection() {
        val top = stack.lastOrNull()?.obj ?: return
        val snap = selectionSnap ?: return
        viewModelScope.launch {
            val derived = withContext(ioDispatcher) {
                runCatching {
                    if (snap.text.isNotBlank()) textCapture(top, snap) else fragmentCapture(top, snap)
                }.getOrNull()
            }
            if (derived == null) {
                _ui.update { it.copy(message = "Не удалось сохранить выделение", messageOutcome = Outcome.FAILED) }
                return@launch
            }
            closeSelection()
            pushFrame(derived, viaTitle = "Выделение")
        }
    }

    /** Откуда взялось ВЫДЕЛЕНИЕ: объект-источник, метки атомов, область, страница.
     *  Не путать с `PointObject.provenance` (#264) — то про происхождение значения. */
    private fun selectionOrigin(top: PointObject, snap: SnappedSelection) = buildMap {
        put(META_SELECTION_SOURCE, top.id)
        if (snap.ids.isNotEmpty()) put(META_SELECTION_IDS, snap.ids.joinToString(" "))
        put(META_SELECTION_REGION, snap.region.let { "${it.left} ${it.top} ${it.right} ${it.bottom}" })
        put(META_SELECTION_PAGE, "0")
    }

    private suspend fun textCapture(top: PointObject, snap: SnappedSelection): PointObject {
        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(snap.text)
        return PointObject(
            id = "sel-${top.id}-${snap.ids.hashCode()}",
            mime = "text/plain",
            uri = ref,
            state = ObjectState(ObjectKind.TEXT, features = setOf(Feature.HAS_TEXT)),
            metadata = selectionOrigin(top, snap),
            sourceObjects = listOf(top.id),
        )
    }

    private suspend fun fragmentCapture(top: PointObject, snap: SnappedSelection): PointObject? {
        val r = snap.region
        val bmp = frames.crop(
            top.uri.value, r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt(),
        ) ?: return null
        val ref = store.newScratchFile("jpg")
        File(ref.value).outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, FRAGMENT_JPEG_QUALITY, it)
        }
        return PointObject(
            id = "sel-${top.id}-${r.hashCode()}",
            mime = "image/jpeg",
            uri = ref,
            state = ObjectState(ObjectKind.IMAGE),
            metadata = selectionOrigin(top, snap),
            sourceObjects = listOf(top.id),
        )
    }

    fun closeSelection() {
        selectionLayer = null
        selectionTransform = null
        selectionSnap = null
        _ui.update { it.copy(selection = null) }
    }

    /**
     * «Найти в документе» (#279): та же страница, что у выделения, — только рамку рисует запрос.
     *
     * Открывается по явному тапу и только на объекте со слоем слов ([FindCapability.accepts]
     * держит пузырь вне остальных): искать без прочитанных слов не в чем, и действие, которое
     * «ищет и не находит», обещало бы поиск и врало бы про результат.
     */
    fun openFind() {
        val top = stack.lastOrNull()?.obj ?: return
        // Молча не отвечаем никогда: пузырь показан — значит, человек нажал, и тишина в ответ
        // неотличима от сбоя (#290). Слой мог уехать вместе с очищенным scratch.
        val atomsRef = top.metadata[META_OCR_ATOMS_REF] ?: top.metadata[META_CLOUD_ATOMS_REF]
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

    /**
     * Запрос человека → места на странице. Правила сравнения живут в ядре ([findOnPage]) — те же,
     * что у свода чтений; здесь только перевод рамок в координаты показанной копии.
     *
     * Пустой запрос гасит подсветку и **молчит**: сказать «ничего не нашлось» человеку, который
     * стёр строку, значило бы ответить на не заданный вопрос.
     */
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
        dispatch(bubble) { resolver.realizerFor(bubble.capabilityId).perform(top, null) }
    }

    /** Сеть по факту, а не по объявлению (#325): согласие спрашивает тот, кто МОЖЕТ
     *  отправить объект наружу, — хоть один не-локальный реализатор в цепочке. */
    private fun isCloud(id: CapabilityId) =
        runCatching { registry.byId(id).meta.network }.getOrDefault(false) ||
            runCatching { resolver.leavesDevice(id) }.getOrDefault(false)

    /** M3: fast local work runs quietly on the object itself — no full busy screen. */
    private fun isQuietAction(id: CapabilityId) =
        runCatching { quietWork(registry.byId(id).meta) }.getOrDefault(false)

    /**
     * Runs [onGranted] at once if cloud consent is already given; otherwise shows the consent
     * gate and defers it (#10). Reads the on-device flag directly (no cached copy) — so there
     * is no init race, and a saved-chain replay or a single action is held the same way.
     *
     * #114: спрашивают не «про облако вообще», а про то обещание, которое даёт это действие
     * ([cloudScopeOf]). «Показать модели» помнится; «выложить по ссылке, которую откроет любой»
     * не помнится никогда — цена называется перед каждым файлом, до отправки, а не после.
     */
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

    /**
     * Имя сервиса, которому уедет объект по AI-ветке (#538) — или `null`, если назвать его нечем.
     *
     * Согласие говорило «на сервер AI-провайдера» человеку, который сам выбрал сервис и сам вписал
     * его ключ: Point знал адресата и не называл. Имя выводится из сохранённого адреса по каталогу
     * — второго источника правды не заводится. Чтение дешёвое (prefs get, тот же, что перед каждым
     * вызовом модели), и делается оно ровно в момент вопроса: ключ мог смениться минуту назад.
     *
     * `null` остаётся честным исходом: у своего прокси имени в каталоге нет, и выдумывать его
     * нельзя — тогда на экране стоит прежнее умолчание.
     */
    private fun chosenAiService(): String? = runCatching {
        com.point.core.flow.providerForBaseUrl(userKeys.read()?.baseUrl.orEmpty())?.name
    }.getOrNull()

    /** Drill into a collection item — continue the normal flow on that object.
     *  The item is already materialised in scratch, so there is no re-ingest. */
    fun onItem(item: PointObject) {
        if (stack.lastOrNull()?.obj?.state?.kind != ObjectKind.COLLECTION) return
        pushFrame(item)
    }

    /** Tap a thing extraction found inside the object (#222) — the branch address, the waybill
     *  number — and continue the flow on *it*. Its actions come from the same registry: an
     *  Address carries HAS_ADDRESS, so «Маршрут» is there without a line of new action code.
     *
     *  Only objects the current frame actually found are accepted — the graph is what the
     *  screen shows, not an open door into arbitrary objects. */
    fun onFound(found: PointObject) {
        if (stack.lastOrNull()?.found?.none { it.id == found.id } != false) return
        pushFrame(found)
    }

    fun submitAmendment(text: String) {
        val bubble = pendingBubble ?: return
        val top = stack.lastOrNull()?.obj ?: return
        pendingBubble = null
        raiseBusy(
            bubble.title,
            network = isCloud(bubble.capabilityId),
            quiet = isQuietAction(bubble.capabilityId),
            cancelable = true,
        )
        _ui.update { it.copy(inputSuggestions = emptyList(), needsImage = null) }
        dispatch(bubble) { resolver.realizerFor(bubble.capabilityId).perform(top, text) }
    }

    fun cancelInput() {
        pendingBubble = null
        _ui.update { it.copy(inputPrompt = null, inputSuggestions = emptyList(), needsImage = null, busy = null) }
    }

    // --- AI chat (#4): a multi-turn conversation grounded in the object ---

    /** Идущий вопрос к модели (#453): его можно остановить, и его ответ имеет право прийти
     *  в закрытый экран — разговор ждёт человека там, где он его оставил. */
    private var chatJob: Job? = null

    /**
     * Открыть разговор об [obj] (тап «Спросить AI»). Согласие на облако уже взято в `onBubble`.
     *
     * Разговор об ЭТОМ объекте не начинается заново (#453): если он уже был, экран возвращается к
     * нему целиком — вместе с идущим вопросом, если тот ещё в пути. Новый пустой заводится только
     * под новый объект: разговор принадлежит своему объекту, и переносить сказанное на чужой было
     * бы хуже, чем начать с чистого листа.
     */
    private fun openChat(obj: PointObject) {
        _ui.update {
            val kept = it.chat?.takeIf { c -> c.obj.id == obj.id }
            it.copy(
                chat = kept ?: ChatState(obj = obj, suggestions = aiSuggestions(obj.state.kind)),
                chatOpen = true,
                busy = null, inputPrompt = null, message = null, messageOutcome = Outcome.NONE,
            )
        }
    }

    /** «Назад» из разговора: закрывается экран, а не разговор (#453). */
    fun closeChat() = _ui.update { it.copy(chatOpen = false) }

    /**
     * Send a chat message. A «сделай word/excel/pdf» request produces a real object and lands on it
     * (#190 inside the chat); anything else is answered as text with the whole thread as context.
     */
    fun sendChatMessage(text: String) {
        val chat = _ui.value.chat ?: return
        val message = text.trim()
        if (message.isEmpty() || chat.pending) return
        val history = chat.messages
        val obj = chat.obj
        _ui.update {
            it.copy(
                chat = chat.copy(
                    messages = history + ChatMessage(ChatRole.USER, message),
                    pending = true,
                    notice = null, // новый вопрос отменяет прошлую остановку — она про прошлый
                ),
            )
        }
        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            val target = aiTransformTarget(message)
            if (target != null) {
                val result = runCatching { resolver.realizerFor(target).perform(obj, null) }
                    // Остановленное человеком не договаривает: без этого отмена доезжала бы до
                    // реплики «Не удалось создать документ» — отказ, которого не было.
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
                if (result is ActionResult.Success) {
                    runCatching { sensory.success() }
                    // Экран уходит на новый объект, разговор остаётся при своём (#453).
                    _ui.update { s -> s.copy(chat = s.chat?.copy(pending = false), chatOpen = false) }
                    pushFrame(store.put(result.result), target, null)
                } else {
                    appendChatAssistant((result as? ActionResult.Failure)?.reason ?: "Не удалось создать документ")
                }
            } else {
                val reply = runCatching { aiChatResponder.reply(obj, history, message) }
                    .getOrElse {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        "Не получилось ответить: ${it.message ?: "ошибка"}"
                    }
                appendChatAssistant(reply)
            }
        }
    }

    /**
     * Остановить идущий вопрос (#453). Кнопки отмены у разговора не было вовсе: пока модель
     * думает, поле ввода погашено, и человеку оставалось только ждать или уйти — а ушедшему
     * ответ выбрасывался молча вместе с потраченной квотой.
     *
     * Остановка говорит словами и не притворяется репликой собеседника: заданный вопрос остаётся
     * в разговоре, ответа под ним нет, и сказано, почему.
     */
    fun cancelChatMessage() {
        val job = chatJob ?: return
        chatJob = null
        job.cancel()
        _ui.update { s -> s.chat?.let { c -> s.copy(chat = c.copy(pending = false, notice = "Ответ остановлен")) } ?: s }
    }

    /**
     * Забрать сказанное — разговор кончается объектом (#491).
     *
     * Формула продукта: `Object → Intent → Capability → Realizer → Object`. Разговор её нарушал —
     * он кончался текстом в переписке, и всё, что модель сказала, оставалось внутри чата: ни
     * сохранить, ни перевести, ни сделать документом. Выход был ровно один — «назад», то есть
     * выбросить ответ, за который уже заплачена квота.
     *
     * Способностей чату это не добавляет ни одной (он остаётся известным исключением из
     * продуктового фильтра, CLAUDE.md) — оно даёт ему **выход**. Ответ становится обычным текстовым
     * объектом, и дальше живёт по общим правилам графа: «В Word», «В Excel», «В PDF», «Перевести»,
     * «Сохранить» приходят к нему сами, потому что принимают TEXT. Поэтому «превратить в документ»
     * больше не требует угаданной формулировки внутри разговора: оно стоит строкой на экране
     * объекта — с подписью, что вернёт.
     *
     * Цепочек Point не строит: забирает человек, тапом.
     */
    fun takeChatAnswer() {
        val chat = _ui.value.chat ?: return
        if (chat.pending) return
        val answer = chat.messages.lastOrNull { it.role == ChatRole.ASSISTANT }
            ?.text?.takeIf { it.isNotBlank() } ?: return
        val source = chat.obj
        viewModelScope.launch {
            val obj = runCatching { chatAnswerObject(source, answer, chat.messages.size) }.getOrNull()
            if (obj == null) {
                _ui.update { it.copy(message = "Не удалось забрать ответ", messageOutcome = Outcome.FAILED) }
                return@launch
            }
            runCatching { sensory.success() }
            // Экран разговора закрывается, сам разговор остаётся при своём объекте (#453):
            // человек вернётся к нему тем же «Спросить AI», если захочет спросить ещё.
            _ui.update { it.copy(chatOpen = false) }
            pushFrame(obj, viaTitle = "Ответ AI")
        }
    }

    /** Ответ модели как текстовый объект в scratch. Markdown — потому что markdown и приходит:
     *  тем же расширением материализует ответ одноразовое «AI» (`LlmClient.run`). */
    private suspend fun chatAnswerObject(source: PointObject, answer: String, turn: Int): PointObject {
        val ref = store.newScratchFile("md")
        File(ref.value).writeText(answer)
        return PointObject(
            id = "chat-${source.id}-$turn",
            mime = "text/markdown",
            uri = ref,
            state = ObjectState(ObjectKind.TEXT, features = setOf(Feature.HAS_TEXT)),
            metadata = mapOf("name" to "Ответ AI"),
            // Сказанное моделью помечено как сказанное моделью (#264) — забранное из разговора
            // не становится «принесённым человеком» оттого, что стало файлом.
            provenance = com.point.core.model.Provenance.MODEL,
            sourceObjects = listOf(source.id),
            creatorAction = AiCapability.ID.value,
        )
    }

    /** Ответ ложится в разговор, а не на экран: закрытый экран больше не выбрасывает пришедшее
     *  молча (#453) — человек вернётся и увидит ответ, за который уже заплачена квота. */
    private fun appendChatAssistant(text: String) {
        chatJob = null
        _ui.update { s ->
            val c = s.chat ?: return@update s
            s.copy(
                chat = c.copy(
                    messages = c.messages + ChatMessage(ChatRole.ASSISTANT, text),
                    pending = false,
                    notice = null,
                ),
            )
        }
    }

    // --- Bring-your-own AI key (#19). Summoned on demand or from the Home gear. ---

    /** Дверь «AI-ключ» и предложение под отказом: пришли сами, возвращать некуда (#465). */
    fun openKeySettings() = openKeyScreen(errand = null)

    private fun openKeyScreen(errand: KeyErrand?) {
        // A tiny prefs read; the store is warmed when it's created (Activity start), so it
        // is in-memory by the time the gear or an AI-no-key failure summons the screen.
        val saved = userKeys.read()
        _ui.update {
            // Отказ, ради которого сюда и пришли, экран ключей не стирает (#452): человек,
            // нажавший «Отмена», возвращается к объекту, где причина по-прежнему сказана словами.
            // Всё остальное сказанное — стирается, как раньше: «Ключ AI сохранён» из прошлого
            // захода не имеет отношения к этому.
            val refusal = keyOfferLabel(it.message) != null
            it.copy(
                keyScreen = saved ?: UserAiConfig.DEFAULT, busy = null,
                // …и он же стоит НА экране ключей (#467). Сюда пришли по предложению под отказом —
                // то есть с вопросом «какой из семи ключей задать»; ответ на него живёт в тексте
                // отказа, а не в памяти человека, и терять его по дороге незачем. Заполняется
                // только от отказа: пришедшему дверью «Настройки» объяснять нечего.
                keyScreenNote = it.message.takeIf { _ -> refusal },
                // Поручение (#465) — второе «зачем», и оно приходит не от отказа, а от тапа:
                // человек ещё ничего не ждал и ничего не потерял, ему просто назвали цену.
                keyErrand = errand,
                message = it.message.takeIf { _ -> refusal },
                messageOutcome = if (refusal) it.messageOutcome else Outcome.NONE,
                inputPrompt = null,
                // Приговор прошлой проверки не имеет права пережить закрытие экрана: «работает»,
                // висящее над другим ключом, — ровно та ложь, против которой вся проверка (#465).
                keyChecking = false,
                keyVerdict = null,
                aiKeySet = saved != null,
                soundEnabled = runCatching { sensorySettings.isSoundEnabled() }.getOrDefault(true),
                privacyLevel = runCatching { cloudPrivacy.level() }
                    .getOrDefault(com.point.core.flow.PrivacyLevel.DEFAULT),
            )
        }
        refreshUsage()
        viewModelScope.launch { refreshCloudConsent() } // тумблер показывает, что разрешено сейчас
    }

    /**
     * Проверить ключ живым запросом и, если он работает, тут же его сохранить (#465).
     *
     * Порядок именно такой: сохраняет то, что доказано. Записать сначала и проверить потом значило
     * бы оставить в приложении ключ, про который уже известно, что он не подошёл, — и следующее
     * действие человека провалилось бы снова, теперь уже «необъяснимо».
     *
     * Отказ ничего не сохраняет и ничего не закрывает: человек остаётся на экране, где стоит и
     * набранный ключ, и совет, что с ним сделать.
     */
    fun checkAiKey(config: UserAiConfig) {
        if (config.apiKey.isBlank() || _ui.value.keyChecking) return
        _ui.update { it.copy(keyChecking = true, keyVerdict = null) }
        viewModelScope.launch {
            val probe = runCatching { aiKeyCheck.check(config) }
                .getOrElse { com.point.core.flow.KeyProbe(error = com.point.core.flow.withoutKey(it.message.orEmpty(), config.apiKey)) }
            val verdict = com.point.core.flow.keyVerdict(probe)
            if (verdict is com.point.core.flow.KeyVerdict.Works) runCatching { userKeys.save(config) }
            _ui.update {
                it.copy(
                    keyChecking = false,
                    keyVerdict = verdict,
                    aiKeySet = it.aiKeySet || verdict is com.point.core.flow.KeyVerdict.Works,
                )
            }
        }
    }

    /**
     * «Забыть ключ» (#536) — путь обратно, которого не было вовсе.
     *
     * `UserKeyStore.clear()` был написан и не звался ни с одного экрана: человек, задавший ключ, мог
     * только переписать его другим. Отдать телефон, уйти с рабочего ключа, перестать платить своей
     * квотой — всё это чинилось переустановкой приложения.
     *
     * Экран НЕ закрывается: стёртое надо увидеть, а не вывести из его исчезновения. Адрес и модель
     * остаются — человек забыл ключ, а не выбор сервиса, и подсовывать ему вместо этого умолчание
     * значило бы молча отменить ещё одно его решение. Приговор прошлой проверки снимается: «Работает
     * — ключ сохранён», висящее над стёртым ключом, — ровно та ложь, против которой вся проверка.
     */
    fun forgetAiKey() {
        viewModelScope.launch {
            runCatching { userKeys.clear() }
            _ui.update {
                it.copy(
                    keyScreen = it.keyScreen?.copy(apiKey = ""),
                    keyVerdict = null,
                    keyChecking = false,
                    aiKeySet = false,
                    message = "Ключ AI забыт", messageOutcome = Outcome.DONE,
                )
            }
        }
    }

    /** Load the usage journal's on/off state and tally for the key screen. */
    private fun refreshUsage() {
        viewModelScope.launch {
            val enabled = journal.isEnabled()
            val summary = if (enabled) runCatching { journal.summary() }.getOrNull() else null
            _ui.update { it.copy(usageEnabled = enabled, usageSummary = summary) }
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { sensorySettings.setSoundEnabled(enabled) }
            _ui.update { it.copy(soundEnabled = enabled) }
        }
    }

    /**
     * «Куда можно отправлять» (#280). Настройка управляет тем, кому МОЖНО предлагать объект, а не
     * тем, чтобы отправлять его молча: согласие и текст «куда именно» остаются на месте.
     */
    fun setPrivacyLevel(level: com.point.core.flow.PrivacyLevel) {
        viewModelScope.launch {
            runCatching { cloudPrivacy.setLevel(level) }
            _ui.update { it.copy(privacyLevel = level) }
        }
    }

    fun setUsageEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { journal.setEnabled(enabled) }
            refreshUsage()
        }
    }

    /**
     * Уйти с экрана ключей — и увидеть объект таким, каким он стал (#465).
     *
     * Дверь отсюда одна на всех: «Отмена», «Готово», системное «назад» и названная строка
     * «Вернуться к объекту» делают ровно это. Второй правды о выходе поэтому нет — и пересборка
     * действий достаётся каждому из четырёх, а не тому, кто пошёл красивой дорогой.
     */
    fun closeKeySettings() {
        _ui.update {
            it.copy(
                keyScreen = null, keyScreenNote = null, keyErrand = null,
                keyVerdict = null, keyChecking = false,
            )
        }
        refreshTopBubbles()
    }

    /**
     * Пересобрать действия верхнего объекта (#465).
     *
     * Имена действий спрашивают готовность своей цепочки в момент сборки кадра: «Понять · нужен
     * ключ» — правда о той секунде, когда кадр строился. Ключ появляется на другом экране, и без
     * пересборки человек возвращался бы к прежней надписи — то есть к неправде ровно о том, что
     * он только что починил.
     *
     * Пусто на стеке — делать нечего: на экран ключей ходят и с «Недавнего», где объекта нет.
     */
    private fun refreshTopBubbles() {
        val index = stack.lastIndex
        val frame = stack.getOrNull(index) ?: return
        val refreshed = frame.copy(
            bubbles = registry.bubblesFor(frame.obj.state),
            latent = registry.latentBubblesFor(frame.obj.state),
        )
        stack[index] = refreshed
        _ui.update { it.copy(frame = refreshed) }
    }

    // --- Аккаунт и круг устройств (#472). Пейринга нет ни как действия, ни как механики (#475). ---

    /** Правка только живого экрана устройств: закрыли — правке некуда ложиться. */
    private fun updateDevices(block: (DevicesScreenState) -> DevicesScreenState) {
        _ui.update { s -> s.devicesScreen?.let { s.copy(devicesScreen = block(it)) } ?: s }
    }

    /** Ход входа — один и тот же на телефоне и на ПК, поэтому живёт в `:core:flow`. */
    private val signInDriver by lazy {
        com.point.core.flow.SignInDriver(
            client = accountClient,
            store = accountStore,
            browser = browser,
            pending = pendingLogins,
        )
    }

    private var signInJob: Job? = null

    /**
     * Поднять дверь входа, если её ещё не проходили (#472).
     *
     * **Дверь стоит только там, где без сервера нельзя** — на круге устройств. Всё остальное
     * работает без входа: объект открывается, читается, превращается и сохраняется на самом
     * телефоне, и требовать за это аккаунт не за что.
     *
     * Раньше дверь стояла на каждом открытии объекта — на шаринге, «Недавнем» и наборе. Владелец
     * упёрся в неё сразу: сервер аккаунтов ещё не выкачен, войти физически некуда, и приложение
     * перестало работать целиком (04.08.2026). Довод «объекту место за дверью, потому что круга
     * ещё нет» верен для сетевых путей и неверен для локальных: у Tesseract, распаковки, QR и
     * сохранения в файл нет никакого круга, им нечего изолировать между людьми.
     *
     * Сетевые действия своего отказа не потеряли: путь, которому нужен сервер, честно скажет об
     * этом сам — как говорит о недостающем ключе AI. Дверь, поднятая заранее и на всё сразу,
     * подменяла этот честный отказ стеной.
     */
    private fun gateSignIn() {
        if (accountStore.current() == null) {
            _ui.update { it.copy(signIn = com.point.core.flow.SignIn.SignedOut) }
            // Вход мог быть начат до того, как этот экран родился: человек ушёл в браузер, а
            // вернулся уже в другой Point. Дверь тогда обязана продолжить начатое, а не предлагать
            // войти девятый раз (#561).
            resumeSignIn()
        }
    }

    /** Начать вход: сервер заводит вход, браузер спрашивает человека, экран показывает код. */
    fun signIn() {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            signInDriver.signIn(deviceName(), com.point.core.flow.DeviceKind.PHONE) { state ->
                showSignIn(state)
            }
        }
    }

    /**
     * Дожать вход, начатый раньше, — то, чего не делал никто (#561).
     *
     * Зовётся дверями на возврате человека в Point: он только что был в браузере, подтвердил вход
     * и вернулся. Раньше в этот момент не происходило ничего: опрос жил в области экрана, а экрана
     * к возвращению могло уже не быть — система забирает фон, Point закрывают, флоу кончается.
     * Сервер это и показал: восемь начатых входов и ноль вопросов о том, чем они кончились.
     *
     * Дёшево и молча: без записи о начатом входе не делается ни одного запроса, а сам вопрос
     * уходит в фоне и **не поднимает экрана**, которого человек не просил, — только закрывает тот,
     * на который он смотрит.
     */
    fun resumeSignIn() {
        if (signInJob?.isActive == true) return
        signInJob = viewModelScope.launch {
            // Начатый вход лежит на диске (шифрованные prefs), а зовут нас на КАЖДОМ возврате двери —
            // в том числе на пути первого экрана с его бюджетом в 300 мс. Поэтому даже вопрос «есть
            // ли что дожимать» задаётся не на главном потоке.
            val started = withContext(ioDispatcher) { runCatching { signInDriver.pendingLogin() }.getOrNull() }
            if (started == null) return@launch
            signInDriver.resume(deviceName(), com.point.core.flow.DeviceKind.PHONE) { state ->
                showSignIn(state, quiet = true)
            }
        }
    }

    /**
     * Что показать про вход — и показывать ли вообще.
     *
     * Удача закрывает дверь САМА (#561): человек нажал «Войти», подтвердил в браузере и вернулся —
     * просить у него ещё один тап «Продолжить» значит держать процедуру там, где её уже нет. Если
     * дверь стояла, под ней оказывается то, ради чего её открывали, — круг устройств.
     *
     * [quiet] — про дожатый в фоне вход: ожидание и отказ он показывает только при уже поднятой
     * двери и не имеет права выпрыгнуть поверх объекта, которым человек занят. Вход, начатый
     * тапом, говорит всегда: его экран человек и открыл.
     */
    private fun showSignIn(state: com.point.core.flow.SignIn, quiet: Boolean = false) {
        if (state is com.point.core.flow.SignIn.SignedIn) {
            val gateWasUp = _ui.value.signIn != null
            _ui.update { it.copy(signIn = null) }
            // Ключ объявляется сразу после входа, а не перед первой отправкой: компьютер, который
            // уже в круге, должен уметь написать этому телефону, ничего от него не дожидаясь.
            announceKey(state.account)
            if (gateWasUp) openDevices()
            return
        }
        if (quiet && _ui.value.signIn == null) return
        _ui.update { it.copy(signIn = state) }
    }

    /** Передумал: опрос гаснет, экран возвращается к одной кнопке. Тупика на входе нет (#114). */
    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        // Начатый вход снимается вместе с ожиданием: иначе он дожимался бы за спиной у человека,
        // который только что сказал «не надо».
        viewModelScope.launch(NonCancellable) { runCatching { signInDriver.forgetPending() } }
        _ui.update { it.copy(signIn = com.point.core.flow.SignIn.SignedOut) }
    }

    /** Вошли — дверь уходит, и под ней оказывается то, ради чего Point открывали. */
    fun dismissSignIn() {
        _ui.update { it.copy(signIn = null) }
    }

    /** Открыть страницу входа ещё раз: браузер закрывают, не дойдя до конца. */
    fun openSignInPage(url: String) = browser.open(url)

    /** Есть ли на экране дверь входа — Activity решает по этому, что рисовать. */
    fun hasSignInGate(): Boolean = _ui.value.signIn != null

    /**
     * «Мои устройства» — тот же экран, что был экраном компьютера.
     *
     * С #544 сюда приходят разделом настроек, а не отдельной дверью «Недавнего»: `keyScreen` при
     * этом не гасится, и круг встаёт ПОВЕРХ настроек — закрыть его значит вернуться в них.
     *
     * Круг приезжает с сервера при открытии экрана и после входа — тем же правилом «не в каждом
     * шаринге», что действует для `/caps` (#80). Пока он едет, на экране уже стоит то устройство,
     * которое Point знает про себя: пустой список был бы враньём о своём же круге.
     */
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
        // #80 v2: тот же естественный момент синхронизации, что и раньше. Компьютер мог обзавестись
        // умениями с прошлого раза, а экран устройств — как раз тот, ради которого о них спрашивают.
        pcLinks.current()?.let { pc ->
            viewModelScope.launch {
                runCatching { pcTransport.fetchCaps(pc)?.let { caps -> pcCaps.save(caps) } }
            }
        }
    }

    /**
     * Закрыть круг устройств.
     *
     * Под ним открывается то, откуда в него вошли: с #544 это настройки (`keyScreen` при закрытии
     * не трогается), а если их нет — «Недавнее». Отдельного «куда вернуться» экран не помнит: он и
     * не должен, состояние само устроено слоями.
     */
    fun closeDevices() {
        refreshFromPc() // #161: под кругом может показаться «Недавнее» — его плашка должна быть свежей
        _ui.update { it.copy(devicesScreen = null) }
    }

    /**
     * Спросить сервер, какие устройства у человека есть.
     *
     * Три ответа разводятся, потому что чинятся разным: круг приехал, до сервера не дозвонились
     * (прошлое знание в силе) и «это устройство отключили» — последнее поднимает дверь входа тут же,
     * а не оставляет человека с молчаливо сломанным Point.
     */
    private suspend fun loadCircle(account: com.point.core.flow.PointAccount) {
        val answer = runCatching { accountClient.circle(account) }
            .getOrDefault(com.point.core.flow.CircleAnswer.Unreachable)
        when (answer) {
            is com.point.core.flow.CircleAnswer.Circle -> {
                rememberPc(answer.devices)
                updateDevices { it.copy(devices = answer.devices, loading = false, error = null) }
            }
            com.point.core.flow.CircleAnswer.Unreachable -> updateDevices {
                it.copy(
                    loading = false,
                    error = "Не удалось спросить сервер о ваших устройствах — проверьте интернет",
                )
            }
            com.point.core.flow.CircleAnswer.Revoked -> forgetAccount(com.point.core.flow.ACCOUNT_REVOKED)
        }
    }

    /**
     * Отключить устройство круга — своё или чужое.
     *
     * Отключённое получает `401` на следующем же запросе, стирает своё состояние и показывает вход.
     * Отключили это устройство — дверь входа поднимается прямо здесь: молчаливый выход человек
     * прочитал бы как поломку.
     */
    fun revokeDevice(deviceId: String) {
        val account = accountStore.current() ?: return
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
            updateDevices { it.copy(busy = false) }
            loadCircle(account)
        }
    }

    /** «Выйти»: устройство и его ящики уходят с сервера, экран возвращается ко входу. */
    fun signOut() {
        val account = accountStore.current() ?: return
        updateDevices { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { accountClient.signOut(account) }
            forgetAccount(com.point.core.flow.SignIn.SignedOut)
        }
    }

    /**
     * «Удалить аккаунт»: учётная запись, круг и все байты сервера — необратимо.
     *
     * Не то же, что [signOut]: тот снимает **это** устройство и оставляет аккаунт жить. Пока такой
     * двери не было, человек, решивший уйти совсем, мог только выйти — и его почта, круг и
     * невыбранные письма продолжали лежать на сервере.
     *
     * Сервер не ответил — не удалено, и говорить «готово» нельзя: местная память остаётся на месте,
     * человек читает отказ и пробует ещё раз. Стирать своё в ответ на молчание значило бы
     * потерять доступ к аккаунту, который при этом никуда не делся.
     */
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

    /** Стереть всё, что это устройство знало про аккаунт и про свой компьютер. */
    private suspend fun forgetAccount(next: com.point.core.flow.SignIn) {
        runCatching { accountStore.clear() }
        runCatching { pcLinks.clear() }
        runCatching { pcCaps.clear() }
        runCatching { linkMonitor.forget() }
        _ui.update { it.copy(devicesScreen = null, signIn = next) }
    }

    /**
     * Компьютер из круга — и больше ничего между устройствами не происходит (#475).
     *
     * Связывание кончилось вместе с локальной сетью: раньше связь возникала при совпадении трёх
     * условий разом (одна сеть · открытый экран «Устройства» · нажатие на компьютере за минуту), и
     * ни об одном не было сказано. Теперь единственное условие названо вслух и выполняется само —
     * вход в один аккаунт. Круг приехал, компьютер в нём есть — телефон его запомнил.
     *
     * Круг может рассказать про несколько компьютеров; берём тот, что отзывался последним. Выбор
     * между ними — работа, которой у человека сегодня нет, и выдумывать ему экран ради неё рано.
     */
    /**
     * Спросить сервер о круге фоном — тихо, без экрана и без слов (#475).
     *
     * Человек этого не заказывал, поэтому отказ здесь молчит: экран устройств спросит сам и
     * скажет своё. Не чаще раза в пять минут — круг меняется редко, а платить за него
     * запросом на каждый шаг незачем.
     */
    private fun syncCircle() {
        val account = accountStore.current() ?: return
        val now = System.currentTimeMillis()
        if (now - lastCircleSyncMs < CIRCLE_SYNC_THROTTLE_MS) return
        lastCircleSyncMs = now
        announceKey(account)
        viewModelScope.launch {
            val answer = runCatching { accountClient.circle(account) }.getOrNull()
            if (answer is com.point.core.flow.CircleAnswer.Circle) rememberPc(answer.devices)
        }
    }

    private fun rememberPc(devices: List<com.point.core.flow.CircleDevice>) {
        // Круг без единого устройства — это не ответ про круг: в нём обязано быть хотя бы то, что
        // спрашивало. Стирать по такому ответу память значило бы гасить пузырёк «На компьютер» от
        // одной странности сервера.
        if (devices.isEmpty()) return
        val pc = devices
            .filter { !it.self && it.kind == com.point.core.flow.DeviceKind.PC }
            .maxByOrNull { it.lastSeenMillis ?: 0L }
        viewModelScope.launch {
            if (pc == null) {
                // Компьютера в круге не стало — и пузырёк «На компьютер» обязан исчезнуть вместе с
                // ним. Оставить память значило бы предлагать дорогу, которой больше нет.
                runCatching { pcLinks.clear() }
                runCatching { pcCaps.clear() }
                return@launch
            }
            val known = com.point.core.flow.LinkedPc(pc.id, pc.name, pc.key)
            if (pcLinks.current() == known) return@launch
            runCatching { pcLinks.save(known) }
            // Что компьютер умеет и что он про нас знает — сразу же: иначе первые действия из
            // «Почти доступно» появились бы только со второго открытия экрана.
            runCatching { pcTransport.fetchCaps(known)?.let { caps -> pcCaps.save(caps) } }
            runCatching { pcTransport.pushPhoneCaps(known, PHONE_ADVERTISED) }
            refreshFromPc(force = true)
        }
    }

    /**
     * Объявить кругу открытый ключ этого телефона (#475).
     *
     * Молчаливая работа сразу после входа: пока ключа нет в круге, компьютеру нечем запечатать
     * письмо телефону, и он честно скажет «не могу», а не отправит открытым текстом. Не вышло —
     * попробуем при следующем входе; человеку тут сказать нечего, он ничего не заказывал.
     */
    private fun announceKey(account: com.point.core.flow.PointAccount) {
        val key = runCatching { deviceKeys.keys().publicKey }.getOrNull() ?: return
        viewModelScope.launch { runCatching { accountClient.enroll(account, key) } }
    }

    /** Как это устройство представляется в круге. */
    private fun deviceName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

    // --- Cloud consent (#10): nothing leaves the device before the user agrees once. ---

    fun confirmCloud() {
        val run = pendingCloud ?: return
        val scope = pendingCloudScope
        pendingCloud = null
        _ui.update { it.copy(cloudConsent = false) }
        viewModelScope.launch {
            // Помнится только то, что имеет право помниться: «выложить по ссылке» — разрешение
            // на ЭТОТ файл и на этот раз (#114).
            runCatching { consent.allow(scope) }
            run()
        }
    }

    /**
     * Отказ от отправки — это исход, и он говорится словами (#541).
     *
     * Прогон по телефону владельца: человек тапал «Распознать текст», Point спрашивал про
     * отправку снимка в сервис, человек отказывался — и экран молча возвращался к объекту. Три
     * разных события выглядели одинаково: «не вышло», «отменено» и «ничего не делали». Молчание
     * тут дороже ошибки: ошибку человек прочитает, а из пустого экрана он делает вывод сам, и
     * вывод этот — «оно сломано».
     *
     * Знак исхода — [Outcome.NONE], как у отмены работы: человек не потерпел неудачу, он принял
     * решение. Ставить «✕» значило бы назвать его выбор сбоем.
     *
     * Слова живут здесь, а не рядом с текстами самого вопроса о согласии: там своя работа (#560),
     * и это не текст согласия, а исход после него.
     */
    fun declineCloud() {
        pendingCloud = null
        _ui.update {
            it.copy(cloudConsent = false, message = CLOUD_DECLINED, messageOutcome = Outcome.NONE)
        }
    }

    /**
     * Разрешение на облако — тумблер в настройках, а не решение, принятое однажды навсегда (#114).
     *
     * Отозвать было негде: человек, разрешивший облако одним тапом полгода назад, не мог передумать
     * ничем, кроме переустановки. Публичной ссылки это не касается — она спрашивает каждый раз.
     */
    fun setCloudAllowed(allowed: Boolean) {
        viewModelScope.launch {
            runCatching {
                if (allowed) consent.allow(com.point.core.flow.CloudScope.MODELS)
                else consent.revoke(com.point.core.flow.CloudScope.MODELS)
            }
            refreshCloudConsent()
        }
    }

    private suspend fun refreshCloudConsent() {
        val allowed = runCatching { consent.allowed(com.point.core.flow.CloudScope.MODELS) }.getOrDefault(false)
        _ui.update { it.copy(cloudEnabled = allowed) }
    }

    // --- Device actions (#66): the installed apps that can open the object, shown inline. ---

    private fun showAppPicker(obj: PointObject) {
        val voice = claimVoice()
        raiseBusy("Ищу приложения…", cancelable = true)
        trackWork(viewModelScope.launch {
            val direct = runCatching { appLauncher.handlers(obj) }.getOrDefault(emptyList())
            // Dedup by package: an app that also appears as a bridged target must not double —
            // the picker keys rows by package, and duplicates crash the list. Direct wins.
            val apps = (direct + bridgedHandlers(obj)).distinctBy { it.packageName }
            if (!owns(voice)) return@launch // отменённый поиск не открывает список приложений
            _ui.update {
                if (apps.isEmpty()) it.copy(busy = null, busyStage = null, message = "Нет приложения для этого объекта", messageOutcome = Outcome.FAILED)
                else it.copy(busy = null, busyStage = null, appPicker = apps)
            }
        })
    }

    /**
     * Apps reachable via ONE transform (#79.1 synthesized compatibility): for every capability that
     * turns this object into a different openable type, the apps for that type — tagged with the
     * transform so a pick converts first, then launches. E.g. an image → PDF apps ("Acrobat · PDF").
     */
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
        // Задача держится здесь, а не в [bridge]: «Преобразую…» поднимает она, и снимать надо
        // именно её — иначе кнопка отменяла бы работу, которой не владеет.
        trackWork(viewModelScope.launch {
            // #66 slice 4: a direct pick is remembered — on the next launch this app is a
            // first-class bubble in the graph, learning through the same usage signal.
            // Bridged picks are skipped: their capability would need the transform re-run.
            if (via == null) {
                val pick = ChosenApp(obj.state.kind, target.packageName, target.activity, target.label)
                runCatching { chosenApps.record(pick) }
                runCatching { usage.record(CapabilityId("app:${target.packageName}#${obj.state.kind.name}")) }
            }
            val toOpen = if (via != null) bridge(obj, via) else obj
            // Отменённое преобразование — не «не удалось»: человек передумал сам, и отчитываться
            // ему отказом было бы враньём. `runCatching` внутри [bridge] проглатывает и отмену.
            ensureActive()
            if (toOpen == null) {
                _ui.update {
                    it.copy(
                        busy = null, busyStage = null, messageOutcome = Outcome.FAILED,
                        message = "Не удалось подготовить объект для этого приложения",
                    )
                }
                return@launch
            }
            runCatching { appLauncher.launch(target, toOpen) }
                .onSuccess { _ui.update { it.copy(busy = null, busyStage = null, message = "Открываю в ${target.label}", messageOutcome = Outcome.DONE) } }
                .onFailure { e -> _ui.update { it.copy(busy = null, busyStage = null, message = e.message ?: "Не удалось открыть", messageOutcome = Outcome.FAILED) } }
        })
    }

    /** Run one transform to produce the object the bridged app can open (#79.1); null on failure. */
    private suspend fun bridge(obj: PointObject, viaCapId: String): PointObject? {
        claimVoice()
        raiseBusy("Преобразую…", cancelable = true)
        val result = runCatching { resolver.realizerFor(CapabilityId(viaCapId)).perform(obj, null) }.getOrNull()
        return (result as? ActionResult.Success)?.let { runCatching { store.put(it.result) }.getOrNull() }
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

    fun saveAiConfig(config: UserAiConfig) {
        viewModelScope.launch {
            runCatching { userKeys.save(config) }
            _ui.update {
                it.copy(
                    keyScreen = null, keyScreenNote = null, keyErrand = null,
                    keyVerdict = null,
                    aiKeySet = config.apiKey.isNotBlank(),
                    message = "Ключ AI сохранён", messageOutcome = Outcome.DONE,
                )
            }
            // «Сохранить без проверки» — тоже уход с экрана ключей (#465): объект под ним обязан
            // перестать говорить «нужен ключ», иначе имя действия врало бы о только что сделанном.
            refreshTopBubbles()
        }
    }

    private fun dispatch(bubble: Bubble, action: suspend () -> ActionResult) {
        runCatching { sensory.tap() } // M4: the choice answers in the hand at once
        // Задача действия хранится, потому что человек имеет право передумать (#288): «В Excel»
        // — это две последовательные модели по фото, минута и больше, и до сих пор прервать её
        // было нечем; экран обещал «несколько секунд» и упирался в последний шаг.
        busyJob?.cancel()
        val voice = claimVoice()
        trackWork(viewModelScope.launch {
            runCatching { usage.record(bubble.capabilityId) } // learning signal for BubblePolicy
            runCatching { journal.record(UsageEvent(UsageEventType.ACTION, bubble.capabilityId.value)) }
            runCatching {
                // Стадии действия текут на экран его собственными словами (#288): выдуманный
                // чек-лист «по часам» застывал на последнем шаге и читался как «зависло».
                // Говорит только та работа, чей голос на экране: снятая договаривает своё в
                // пустоту, а не поверх следующей (см. [workVoice]).
                kotlinx.coroutines.withContext(
                    com.point.core.flow.ActionProgress { stage ->
                        if (voice == workVoice) _ui.update { it.copy(busyStage = stage) }
                    },
                ) { action() }
            }
                // Снятая работа не приземляется: нативный проход движка об отмене не знает и
                // доходит до конца сам — без этой проверки объект открывался поверх «Отменено».
                .onSuccess { result -> if (owns(voice)) handleResult(result, bubble) }
                .onFailure { e ->
                    // Отмена — не ошибка: человек передумал, и сказать ему «Ошибка» было бы враньём.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (!owns(voice)) return@onFailure
                    // «Ошибка» — слово системы; человеку под объектом нужен исход, а не термин.
                    _ui.update { it.copy(busy = null, busyStage = null, message = e.message ?: "Не получилось", messageOutcome = Outcome.FAILED) }
                }
        })
    }

    /**
     * Отменить идущую работу (#288, #114) — ту самую, над которой человек видит кнопку.
     *
     * Отмена настоящая: задача снимается, а её результат на экран не попадает (см. [owns]).
     * Кнопка рисуется только там, где обе половины верны, — иначе её нет вовсе ([showsCancel]).
     * Возвращаемся туда, откуда пришли: над объектом — со словом «Отменено» (молчаливое
     * исчезновение экрана неотличимо от сбоя), а с «Недавнего» — на само «Недавнее»: там
     * ответом человеку служит вернувшийся экран, а карточка «Отменено» без объекта стала бы
     * тупиком.
     */
    fun cancelAction() {
        val job = busyJob ?: return
        busyJob = null
        job.cancel()
        claimVoice() // остановленная работа замолкает сразу — её хвост ещё идёт
        // Отмена — не отказ и не удача: человек сам передумал. Знак исхода не имеет права ставить
        // ему ни «✕», ни «✓ Готово» — работа не дошла до конца, и заявлять о ней нечего.
        val hasObject = _ui.value.frame != null
        _ui.update {
            it.copy(
                busy = null, busyStage = null, busyCancelable = false,
                message = if (hasObject) "Отменено" else null, messageOutcome = Outcome.NONE,
            )
        }
    }

    private suspend fun handleResult(result: ActionResult, bubble: Bubble) {
        when (result) {
            is ActionResult.Success -> {
                runCatching { sensory.success() } // M4: the transformation lands in the hand
                // #117 graph metrics: the edge actually traversed — kinds and id only.
                val fromKind = stack.lastOrNull()?.obj?.state?.kind?.name ?: "?"
                val produced = store.put(result.result)
                pushFrame(produced, bubble.capabilityId, bubble.title)
                // #491: строка под действием обещала ожидание, а не гарантию — `produces` объявлен
                // подсказкой, и настоящий вид переклассифицируется из выхода. Когда вышло другое,
                // Point говорит об этом сам: молчать тут хуже, чем ошибиться — ошибка объясняет
                // экран, а молчание оставляет человека с догадкой «оно сделало что-то не то».
                // Знака исхода нет (`NONE`): это не отказ и не победа, это уточнение.
                // #558: сверяется не только вид, но и существо — слово, которым реализатор
                // назвал сделанное ([META_YIELD_NOUN]). Вид совпадал и у «Word в PDF», где
                // внутри лежал пересказ документа, а не документ.
                yieldSurprise(bubble.yields, produced.state.kind, produced.metadata[META_YIELD_NOUN])?.let { note ->
                    _ui.update { it.copy(message = note, messageOutcome = Outcome.NONE) }
                }
                runCatching {
                    journal.record(
                        UsageEvent(
                            UsageEventType.EDGE,
                            edgeDetail(fromKind, bubble.capabilityId.value, result.result.type.name),
                        ),
                    )
                }
            }
            is ActionResult.Done -> {
                runCatching { sensory.success() }
                // A flow carried to a terminal (Share/Save/Open) — a task handled in Point.
                runCatching { journal.record(UsageEvent(UsageEventType.COMPLETED, bubble.capabilityId.value)) }
                _ui.update { it.copy(busy = null, busyStage = null, message = result.message, messageOutcome = Outcome.DONE) }
            }
            is ActionResult.Failure -> {
                runCatching { sensory.failure() } // M4: a failure bumps, never buzzes long
                runCatching { journal.record(UsageEvent(UsageEventType.FAILED, bubble.capabilityId.value)) }
                // Отказ говорит сам за себя — любой, включая «нет ключа» (#452). Раньше этот один
                // подменялся экраном настроек, а причина при этом стиралась: человек тапал
                // «Понять», ждал и получал экран про ключи без единого слова о том, почему тот
                // открылся. «Отмена» возвращала его к объекту, где не осталось ничего, — снаружи
                // неотличимо от «действие ничего не сделало». Экран ключей теперь стоит рядом с
                // причиной предложением ([keyOfferLabel]), по которому человек идёт сам, — и,
                // открывшись, повторяет эту причину карточкой (#465).
                _ui.update { it.copy(busy = null, busyStage = null, message = result.reason, messageOutcome = Outcome.FAILED) }
            }
            is ActionResult.NeedsInput -> {
                pendingBubble = bubble
                _ui.update { it.copy(busy = null, busyStage = null, inputPrompt = result.prompt, inputSuggestions = result.suggestions) }
            }
            is ActionResult.NeedsImage -> {
                // Same pending-bubble mechanism as NeedsInput; the picked image URI is fed back
                // through submitAmendment (the host opens the photo picker on this flag).
                pendingBubble = bubble
                _ui.update { it.copy(busy = null, busyStage = null, needsImage = result.prompt) }
            }
        }
    }

    /**
     * Убрать сообщение, за которым нет объекта (#114): «Ключ AI сохранён», «Объект недоступен»,
     * «Это не код подключения Point для ПК». Возвращает `true`, если было что убирать.
     *
     * Такое состояние — экран из одной карточки, и раньше выйти из него было нечем: «назад»
     * доходил до системы и закрывал Point. Дверь решает, куда именно это ведёт: домашняя — обратно
     * на «Недавнее», «Поделиться» — наружу, в приложение, из которого пришли. Общее одно: выход
     * есть всегда.
     */
    fun dismissMessage(): Boolean {
        val state = _ui.value
        if (state.frame != null || state.message == null) return false
        _ui.update { it.copy(message = null, messageOutcome = Outcome.NONE) }
        return true
    }

    fun onBack(): Boolean {
        if (_ui.value.selection != null) {
            closeSelection() // #259: назад закрывает выделение, объект остаётся
            return true
        }
        if (_ui.value.find != null) {
            closeFind() // #279: назад закрывает поиск, объект остаётся
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
            closeChat() // #4: back leaves the chat, returning to the object (#453: разговор остаётся)
            return true
        }
        // Порядок этих трёх веток — порядок, в котором их рисует [PointHost], и с #544 это уже не
        // формальность: настройки, круг устройств и вход теперь МОГУТ стоять друг на друге. Круг
        // открывается разделом настроек, а вход поднимается поверх круга, если аккаунта ещё нет.
        // «Назад» обязан закрывать верхний экран — тот, который человек видит; закрой он нижний,
        // и человек остался бы смотреть на экран, которого в состоянии уже нет.
        //
        // Экран входа был единственным местом без выхода: «назад» проваливался мимо всех веток,
        // Activity закрывалась, и Point исчезал целиком — вместе с объектом, ради которого его
        // открыли. Кнопка «Отменить» на экране рисуется только в состоянии ожидания, так что
        // из остальных состояний выхода не было вовсе.
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

    /** User rule (#66): a long-press pins this action for objects of this kind — it will
     *  always rank first; a second long-press unpins. The frame re-ranks at once. */
    fun togglePin(bubble: Bubble) {
        val top = stack.lastOrNull() ?: return
        val kind = top.obj.state.kind
        viewModelScope.launch {
            val already = runCatching { pins.pinnedFor(kind) }.getOrNull() == bubble.capabilityId
            runCatching { if (already) pins.unpin(kind) else pins.pin(kind, bubble.capabilityId) }
            val index = stack.lastIndex
            val frame = stack.getOrNull(index) ?: return@launch
            val refreshed = frame.copy(
                bubbles = registry.bubblesFor(frame.obj.state),
                pinned = if (already) null else bubble.capabilityId,
            )
            stack[index] = refreshed
            _ui.update {
                it.copy(
                    frame = refreshed,
                    message = if (already) "Откреплено" else "Закреплено: ${bubble.title}",
                    messageOutcome = Outcome.DONE,
                )
            }
        }
    }

    /** Timeline tap (#114): pop back to the [index]-th step of the journey in one move. */
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
        // Флоу кончился — кончилась и работа над его объектом. Раньше отменялись только обогащение
        // и разговор, а начатое действие продолжало идти: человек уходил на «Недавнее», и через
        // минуту поверх него приезжал результат того, от чего он ушёл. Уход — это тоже отмена.
        busyJob?.cancel()
        busyJob = null
        // Опрос входа гаснет вместе с экраном — и это больше не значит «вход пропал»: сам вход
        // записан на устройстве и дожимается на возврате человека ([resumeSignIn], #561). Пока
        // записи не было, эта строка означала «вход не кончится никогда».
        signInJob?.cancel()
        signInJob = null
        // Флоу кончился — кончился и разговор о его объекте (#453): держать вопрос в пути некому,
        // и ответ, пришедший в пустоту, ляжет в разговор, которого больше нет.
        chatJob?.cancel()
        chatJob = null
        stack.clear()
        pendingBubble = null
        pendingPreviewBubble = null
        _ui.update { FlowUiState() }
        // [NonCancellable] здесь — не осторожность, а условие того, что уборка вообще случается.
        //
        // Самый частый конец флоу — человек закрыл Point, и `FlowHostActivity.onDestroy` зовёт
        // `endFlow`. К этой секунде система уже погасила viewModelScope: `ON_DESTROY` приходит
        // наблюдателям ДО `Activity.onDestroy` (`performDestroy` шлёт событие первым), и на нём
        // `ComponentActivity` чистит `ViewModelStore`. Работа, запущенная в мёртвой области, не
        // начинается вовсе — и байты объекта оставались лежать на диске после ухода человека.
        //
        // Найдено тестом, который смотрит в папку, а не считает вызовы (#239): «модель позвала
        // clear()» было правдой всё это время, а «на диске ничего не осталось» — нет.
        viewModelScope.launch(NonCancellable) {
            runCatching { store.clear() }
            // Расшаренный текст лежал в кэше и переживал всё: инвариант «по окончании флоу —
            // обязательный clear()» держался только для рабочей копии, а через эту дверь идут
            // пароли, переписка и реквизиты.
            runCatching { sharedTexts.clear() }
            runCatching { flowSnapshot.clear() } // the journey ended on purpose — forget it (#7)
        }
    }

    /**
     * Новый кадр — и, если шаг вернул тот же объект, всё, что о нём уже знали (#526).
     *
     * Кадр — это место на экране. Знание о том, что на визитке есть QR, местом на экране не
     * является: оно принадлежит объекту. Пока `found`/`relations` рождались пустыми, а состояние
     * бралось только у результата, каждый шаг начинал понимать заново — и «Понять» стирало
     * найденное локально ровно в тот момент, когда человек попросил понять ЛУЧШЕ.
     *
     * Наследование не выборочное и не по списку действий: правило одно на все шаги — «тот же
     * объект продолжает знать то, что знал» ([continuesObject]). Иначе гарантия «ни один шаг не
     * уменьшает известного» держалась бы на памяти автора следующего реализатора.
     */
    private fun pushFrame(obj: PointObject, via: CapabilityId? = null, viaTitle: String? = null) {
        val carried = stack.lastOrNull()?.takeIf { continuesObject(it.obj, obj) }
        val known = carried?.let { carryKnowledge(it.obj, obj) } ?: obj
        val bubbles = registry.bubblesFor(known.state)
        val frame = FlowFrame(
            known, bubbles, via, viaTitle,
            // Найденное внутри объекта (#222) — тоже понятое о нём, а не свойство кадра: трек,
            // адрес отделения и срок никуда не делись оттого, что страницу прочитали ещё раз.
            found = carried?.found.orEmpty(),
            relations = carried?.relations.orEmpty(),
            latent = registry.latentBubblesFor(known.state),
            pinned = runCatching { pins.pinnedFor(known.state.kind) }.getOrNull(),
        )
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

    /** #7: journal the journey after every step — a crash loses nothing. */
    private fun persistJourney() {
        val frames = stack.map { f ->
            FlowSnapshotFrame(
                id = f.obj.id, kind = f.obj.state.kind, mime = f.obj.mime, ref = f.obj.uri.value,
                metadata = f.obj.metadata,
                viaCapabilityId = f.viaCapability?.value, viaTitle = f.viaTitle,
            )
        }
        viewModelScope.launch { runCatching { flowSnapshot.save(frames) } }
    }

    /** A journaled ref back into an object ref. The journal stores one string; what it means
     *  depends on the kind — scratch bytes for a file, the value itself for an extracted
     *  object (#222). `File(ref)` is no longer universally valid, so the kind decides. */
    private fun refFor(kind: ObjectKind, ref: String): ObjectRef =
        if (kind.isFileBacked) ScratchRef(ref) else ValueRef(ref)

    /** For a visual frame (IMAGE / PDF), decode a real thumbnail off-main and attach it (only
     *  while that object is still on the stack). The hero is the object, not an icon (#114);
     *  a PDF shows its rendered first page via [previewSource]. */
    private fun loadObjectPreview(obj: PointObject) {
        if (obj.state.kind != ObjectKind.IMAGE && obj.state.kind != ObjectKind.PDF) return
        viewModelScope.launch {
            val bitmap = withContext(ioDispatcher) {
                val source = previewSource(obj, pdfRasterizer) ?: return@withContext null
                runCatching { Bitmaps.decodeThumbnail(source, PREVIEW_MAX_PX)?.asImageBitmap() }.getOrNull()
            } ?: return@launch

            val index = stack.indexOfLast { it.obj.id == obj.id }
            val top = stack.getOrNull(index) ?: return@launch
            val refreshed = top.copy(preview = bitmap)
            stack[index] = refreshed
            _ui.update { if (it.frame?.obj?.id == obj.id) it.copy(frame = refreshed) else it }
        }
    }

    /** For a TEXT frame, read a bounded preview of its content and attach it to the
     *  frame (only while that object is still on top). Mirrors [enrichInBackground]. */
    private fun loadTextPreviewIfText(obj: PointObject) {
        if (obj.state.kind != ObjectKind.TEXT) return
        viewModelScope.launch {
            val raw = runCatching { store.readText(obj, limit = 100_000) }.getOrDefault("")
            if (raw.isBlank()) return@launch
            val text = sanitizeTextPreview(raw) // strip base64 blobs (e.g. a vCard's inline photo)

            val topIndex = stack.lastIndex
            val top = stack.getOrNull(topIndex) ?: return@launch
            if (top.obj.id != obj.id) return@launch

            val refreshed = top.copy(textPreview = text)
            stack[topIndex] = refreshed
            _ui.update { if (it.frame?.obj?.id == obj.id) it.copy(frame = refreshed) else it }
        }
    }

    /** For a COLLECTION frame, list its items async and attach them to the frame
     *  (only while that object is still on top). Mirrors [enrichInBackground]. */
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

    /** Collect the progressive enrichment stream: every finding lands on screen as it
     *  arrives (bubbles grow one by one), and [FlowFrame.enriching] mirrors the labels of
     *  still-running work — the visible "Point думает" feedback (#64). */
    private fun enrichInBackground(obj: PointObject) {
        enrichJobs += viewModelScope.launch {
            enrichment.enrich(obj)
                .catch { /* enrichment must never break the flow — it only ever adds */ }
                .collect { update -> applyEnrichment(obj, update) }
            // Understanding is final — fold it into History, so Home remembers the object
            // by what it IS («телефон, дата»), not just when it arrived (#114).
            stack.lastOrNull { it.obj.id == obj.id }?.let { frame ->
                if (frame.obj.state.features.isNotEmpty()) runCatching { history.update(frame.obj) }
            }
        }
    }

    /**
     * What a fresh enrichment run is allowed to write onto an object that already knows things
     * (#243).
     *
     * Enrichment **adds**; it does not overwrite. The bug this replaces was a flat `+`, so a
     * re-run of OCR over the same bytes silently undid what an explicit — and paid — action had
     * established: «Понять глубже» repaired `Олексйвка` to `Олексіївка`, the frame was pushed with
     * the repair, background enrichment recognised the same picture again and put the damage back.
     *
     * Re-deriving a fact from bytes that have not changed cannot produce anything new, so the
     * older value is not stale — it is simply the one somebody decided on.
     *
     * [REFRESHABLE_META] is the exception: those keys name a work product, not a fact, and each
     * run writes a new file for them.
     */
    private fun enrichmentAdditions(
        known: Map<String, String>,
        fresh: Map<String, String>,
    ): Map<String, String> =
        fresh.filterKeys { it !in known || it in REFRESHABLE_META }

    /** Apply one enrichment snapshot to its object's frame — found by id, not by top:
     *  a slow OCR finishing after the user moved on still lands on the frame below,
     *  so its findings are there when they come back. */
    private fun applyEnrichment(source: PointObject, update: EnrichmentUpdate) {
        val index = stack.indexOfLast { it.obj.id == source.id }
        val frame = stack.getOrNull(index) ?: return
        val newState = update.features.fold(frame.obj.state) { state, feature -> state.with(feature) }
        val newMetadata = frame.obj.metadata + enrichmentAdditions(frame.obj.metadata, update.metadata)
        // #222: the same fact can arrive from the live extractor and from stored metadata —
        // the ids are built to match, so keeping the first wins and the graph stays one node.
        val newFound = (frame.found + update.objects).distinctBy { it.id }
        val newRelations = (frame.relations + update.relations).distinct()
        val objChanged = newState != frame.obj.state || newMetadata != frame.obj.metadata
        val graphChanged = newFound.size != frame.found.size || newRelations.size != frame.relations.size
        if (!objChanged && !graphChanged && update.running == frame.enriching) return

        // Порядок уже показанного не трогаем — иначе строка уезжает из-под пальца (см. keepShownOrder).
        val newBubbles = if (objChanged) {
            com.point.core.model.keepShownOrder(frame.bubbles, registry.bubblesFor(newState))
        } else {
            frame.bubbles
        }
        val refreshed = frame.copy(
            obj = frame.obj.copy(state = newState, metadata = newMetadata),
            bubbles = newBubbles,
            latent = if (objChanged) registry.latentBubblesFor(newState) else frame.latent,
            enriching = update.running,
            found = newFound,
            relations = newRelations,
        )
        stack[index] = refreshed
        _ui.update { if (it.frame?.obj?.id == source.id) it.copy(frame = refreshed) else it }
        if (objChanged) {
            persistJourney() // #7: understanding survives process death together with the step
        }
    }

    internal companion object {
        /** Metadata that points at a file enrichment just wrote — a stale pointer would send
         *  «Распознать текст» to a scratch file from a previous run. The atoms sidecar (#257)
         *  is the same class of pointer: each OCR run writes a fresh atoms.tsv, and a stale ref
         *  would tear the text/atoms pair apart on restoreJourney re-enrichment. */
        val REFRESHABLE_META = setOf(
            com.point.core.flow.META_OCR_TEXT_REF,
            com.point.core.flow.META_OCR_ATOMS_REF,
        )

        /**
         * Что сказано человеку, отказавшемуся отправлять объект наружу (#541).
         *
         * Обе половины отказа: что произошло — не отправили, объект дома, действие не сделано; и
         * что дальше — тап никуда не делся, передумать можно тем же движением. Про сервис сказано
         * без имени: слова живут одни на все сетевые действия, а уезжают они в разные места.
         */
        const val CLOUD_DECLINED =
            "Ничего не отправлено — объект остался на телефоне, действие не выполнено. " +
                "Без отправки оно не работает: тапните ещё раз, если передумаете"
    }

    private fun cancelEnrichment() {
        enrichJobs.forEach { it.cancel() }
        enrichJobs.clear()
    }
}

private const val MAX_CLIP = 2000

/** How rarely Home re-asks the PC for its outbox (#161) — app switches with the PC away
 *  must not burn a connect timeout every time. */
private const val OUTBOX_THROTTLE_MS = 30_000L

/** Как часто телефон тихо переспрашивает круг (#475): устройства прибавляются редко. */
private const val CIRCLE_SYNC_THROTTLE_MS = 5 * 60_000L

/** The phone-side actions advertised to the paired PC (#161 v2) — deliberately few and
 *  non-interactive: each opens a system screen the user finishes themselves. */
private val PHONE_ADVERTISED = listOf(
    com.point.core.flow.PcRemoteAction("call", "Позвонить", kinds = setOf("TEXT")),
    com.point.core.flow.PcRemoteAction("event", "Создать событие", kinds = setOf("TEXT")),
)
private const val PREVIEW_MAX_PX = 640

/** Полный экран выделения читает страницу крупнее превью: слова должны быть различимы. */
private const val SELECTION_MAX_PX = 2048

/** Фрагмент — рабочая улика, не сувенир: жмём щадяще, чтобы зрячей модели было что читать. */
private const val FRAGMENT_JPEG_QUALITY = 92
