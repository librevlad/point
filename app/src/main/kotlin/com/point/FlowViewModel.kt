package com.point

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.point.core.flow.AppLauncher
import com.point.core.flow.AppTarget
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.ChosenApp
import com.point.core.flow.ChosenApps
import com.point.core.flow.CrashLog
import com.point.core.flow.Enrichment
import com.point.core.flow.edgeDetail
import com.point.core.flow.EnrichmentUpdate
import com.point.core.flow.FavoritesStore
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
import com.point.core.flow.SnappedSelection
import com.point.core.flow.UserAiConfig
import com.point.core.flow.UserKeyStore
import com.point.core.flow.findOnPage
import com.point.core.flow.foundOnPageLabel
import com.point.core.ui.Outcome
import com.point.core.flow.snapSelection
import com.point.core.model.Feature
import com.point.core.model.ObjectState
import com.point.data.decodeSelectionFrame
import java.io.File
import com.point.core.model.ActionResult
import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.FavoriteChain
import com.point.core.model.FlowSnapshotFrame
import com.point.core.model.HistoryEntry
import com.point.core.model.Intent
import com.point.core.model.BubbleTier
import com.point.core.model.ObjectKind
import com.point.core.model.isFileBacked
import com.point.core.model.ValueRef
import com.point.core.model.ScratchRef
import com.point.core.model.ObjectRef
import com.point.core.model.PointObject
import com.point.core.ui.likelyCount
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
 * from which Favorite chains are saved and replayed.
 */
@HiltViewModel
class FlowViewModel @Inject constructor(
    private val store: ObjectStore,
    private val registry: CapabilityRegistry,
    private val resolver: Resolver,
    private val aiChatResponder: com.point.core.flow.AiChatResponder,
    private val enrichment: Enrichment,
    private val history: HistoryStore,
    private val favorites: FavoritesStore,
    private val usage: CapabilityUsage,
    private val chosenApps: ChosenApps,
    private val userKeys: UserKeyStore,
    private val journal: UsageJournal,
    private val consent: PrivacyConsent,
    private val appLauncher: AppLauncher,
    private val pdfRasterizer: PdfRasterizer,
    private val sensory: SensoryFeedback,
    private val sensorySettings: SensorySettings,
    private val flowSnapshot: FlowSnapshotStore,
    private val crashLog: CrashLog,
    private val ioDispatcher: CoroutineDispatcher,
    private val pins: PinnedActions,
    private val appIcons: AppIconResolver,
    private val pcPairings: com.point.core.flow.PcPairings,
    private val pcTransport: com.point.core.flow.PcTransport,
    private val pcDiscovery: com.point.core.flow.PcDiscovery,
    private val basket: com.point.core.flow.Basket,
    private val pcCaps: com.point.core.flow.PcCapsStore,
    private val pulledFiles: PulledFileFactory,
) : ViewModel() {

    /** Идущее действие — чтобы его можно было отменить (#288). */
    private var actionJob: kotlinx.coroutines.Job? = null

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
     *  везде, где ставится `busy`, — «Открываю…» и цепочка чужих слов носить тоже не должны. */
    private fun claimVoice(): Long = ++workVoice

    private val stack = ArrayDeque<FlowFrame>()
    private val enrichJobs = mutableListOf<Job>()
    private var pendingBubble: Bubble? = null
    /** A cloud action deferred until the user grants consent (#10); run on confirm. */
    private var pendingCloud: (() -> Unit)? = null
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
    private var allFavorites: List<FavoriteChain> = emptyList()

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

    private val _basketCount = MutableStateFlow(0)
    /** Items accumulated in the basket (#96) — Home offers to open the pile as one COLLECTION. */
    val basketCount: StateFlow<Int> = _basketCount.asStateFlow()

    private val _clipboard = MutableStateFlow<String?>(null)
    /** Actionable text sitting in the clipboard when Point opened — a dismissible Home suggestion (#72). */
    val clipboard: StateFlow<String?> = _clipboard.asStateFlow()
    private var lastClipboard: String? = null

    /** Set synchronously by a fresh share BEFORE its coroutine runs — a stale snapshot
     *  must never race over the user's new intent (#7). */
    private var freshShareArrived = false

    init {
        viewModelScope.launch { loadFavorites() }
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

    fun onShared(sourceUri: String, mime: String, autoAction: String? = null) {
        freshShareArrived = true
        claimVoice()
        _ui.update { it.copy(busy = "Открываю…", busyStage = null, busyNetwork = false, busyQuiet = false, message = null, messageIsFailure = false, inputPrompt = null) }
        _ui.update { it.copy(busy = "Открываю…", busyStage = null, busyNetwork = false, busyQuiet = false, message = null, messageOutcome = Outcome.NONE, inputPrompt = null) }
        viewModelScope.launch {
            val obj = runCatching {
                store.clear()
                store.ingest(sourceUri, mime)
            }.getOrElse {
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
            // #161 v2: the PC named an intent for this object — run it as if tapped.
            autoAction?.let { id ->
                onBubble(Bubble("pc", registry.byId(CapabilityId(id)).label(obj.state), CapabilityId(id), obj.state))
            }
        }
    }

    /** Several shared files → one COLLECTION (the inbound half of collections;
     *  e.g. several photos to merge into a PDF). */
    fun onSharedMultiple(sources: List<String>) {
        freshShareArrived = true
        claimVoice()
        _ui.update { it.copy(busy = "Открываю…", busyStage = null, busyNetwork = false, busyQuiet = false, message = null, messageIsFailure = false, inputPrompt = null) }
        _ui.update { it.copy(busy = "Открываю…", busyStage = null, busyNetwork = false, busyQuiet = false, message = null, messageOutcome = Outcome.NONE, inputPrompt = null) }
        viewModelScope.launch {
            val obj = runCatching {
                store.clear()
                store.ingestMultiple(sources)
            }.getOrElse {
                // То же, что в onShared: человеку — словами, причина — в логе приёмника.
                _ui.update { it.copy(busy = null, busyStage = null, message = "Не удалось открыть объект", messageOutcome = Outcome.FAILED) }
                return@launch
            }
            // A collection is a transient scratch directory — History copies a single file, so skip it.
            runCatching { journal.record(UsageEvent(UsageEventType.SHARED, obj.state.kind.name)) }
            cancelEnrichment()
            stack.clear()
            pushFrame(obj)
        }
    }

    fun loadRecent() {
        viewModelScope.launch {
            _recent.value = runCatching { history.recent() }.getOrDefault(emptyList())
            _basketCount.value = runCatching { basket.items().size }.getOrDefault(0)
        }
        refreshFromPc()
    }

    /** Quietly ask the paired PC for its outbox (#161) — throttled so app switches with the
     *  PC away don't burn a connect timeout every time; failures just mean no banner. */
    private fun refreshFromPc(force: Boolean = false) {
        val pairing = pcPairings.current() ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastOutboxFetchMs < OUTBOX_THROTTLE_MS) return
        lastOutboxFetchMs = now
        viewModelScope.launch {
            runCatching { pcTransport.fetchOutbox(pairing) }.getOrNull()?.let { entries ->
                fromPcEntries = entries
                _fromPcCount.value = entries.size
            }
        }
    }

    /** Pull everything the PC queued (#161): download → ingest → ack, in that order —
     *  a failed ack re-offers (at-least-once); a failed download acks nothing. */
    fun pullFromPc() {
        val pairing = pcPairings.current() ?: return
        claimVoice()
        _ui.update { it.copy(busy = "Забираю с компьютера…", busyStage = null, busyNetwork = false, busyQuiet = false, message = null, messageIsFailure = false) }
        _ui.update { it.copy(busy = "Забираю с компьютера…", busyStage = null, busyNetwork = false, busyQuiet = false, message = null, messageOutcome = Outcome.NONE) }
        viewModelScope.launch {
            // Pull what is on the PC RIGHT NOW — a fresh fetch, not the throttled banner snapshot. The
            // cached list can be up to OUTBOX_THROTTLE_MS stale, so an object queued after the last
            // fetch would be missed and a stale one pulled instead — the phone got «не то» (#161).
            val entries = runCatching { pcTransport.fetchOutbox(pairing) }.getOrNull().orEmpty()
            if (entries.isEmpty()) {
                fromPcEntries = emptyList()
                _fromPcCount.value = 0
                _ui.update { it.copy(busy = null) }
                return@launch
            }
            val pulled = entries.map { entry ->
                val name = entry.meta["name"] ?: "объект"
                val path = pulledFiles.create("${entry.id}-$name")
                val ok = runCatching { pcTransport.downloadOutboxFile(pairing, entry.id, path) }.getOrDefault(false)
                Triple(entry, path, ok)
            }
            if (pulled.any { !it.third }) {
                _ui.update { it.copy(busy = null, busyStage = null, message = "Компьютер недоступен — попробуйте ещё раз", messageOutcome = Outcome.FAILED) }
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
                runCatching { pcTransport.ackOutbox(pairing, entry.id) }
                    .recoverCatching { pcTransport.ackOutbox(pairing, entry.id) }
            }
            fromPcEntries = emptyList()
            _fromPcCount.value = 0
        }
    }

    /** Hide the banner until the next fetch — the objects stay on the PC (no ack). */
    fun hideFromPc() {
        _fromPcCount.value = 0
    }

    /** Open the accumulated pile (#96) as one COLLECTION flow — the basket itself
     *  keeps its copies; the flow works on fresh scratch ones (copy-in invariant). */
    fun openBasket() {
        viewModelScope.launch {
            val paths = runCatching { basket.items() }.getOrDefault(emptyList())
            if (paths.isEmpty()) { _basketCount.value = 0; return@launch }
            onSharedMultiple(paths.map { "file://$it" })
        }
    }

    fun clearBasket() {
        viewModelScope.launch {
            runCatching { basket.clear() }
            _basketCount.value = 0
        }
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
        claimVoice()
        _ui.update { it.copy(busy = "Открываю…", busyStage = null, busyNetwork = false, busyQuiet = false, message = null, messageIsFailure = false, inputPrompt = null) }
        _ui.update { it.copy(busy = "Открываю…", busyStage = null, busyNetwork = false, busyQuiet = false, message = null, messageOutcome = Outcome.NONE, inputPrompt = null) }
        viewModelScope.launch {
            val obj = runCatching { history.open(entry.id) }.getOrNull()
            if (obj == null) {
                _ui.update { it.copy(busy = null, busyStage = null, message = "Объект недоступен", messageOutcome = Outcome.FAILED) }
                return@launch
            }
            runCatching { store.clear() }
            cancelEnrichment()
            stack.clear()
            pushFrame(obj)
        }
    }

    fun onBubble(bubble: Bubble) {
        val top = stack.lastOrNull()?.obj ?: return
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
            requireCloudConsent { maybePreview(bubble, top) }
            return
        }
        maybePreview(bubble, top)
    }

    /** If the chosen realizer offers a preview (#97), show it and wait for confirm; otherwise run
     *  straight away. Busy is shown immediately (so feedback is instant and the preview computation —
     *  e.g. ML Kit for an address — is covered); the coroutine then reveals the preview or runs. */
    private fun maybePreview(bubble: Bubble, top: PointObject) {
        claimVoice()
        _ui.update {
            it.copy(
                busy = bubble.title, busyStage = null, busyNetwork = isCloud(bubble.capabilityId),
                busyQuiet = isQuietAction(bubble.capabilityId), message = null, messageOutcome = Outcome.NONE,
                inputPrompt = null,
            )
        }
        viewModelScope.launch {
            val preview = runCatching { resolver.realizerFor(bubble.capabilityId).preview(top) }.getOrNull()
            if (preview == null) {
                dispatch(bubble) { resolver.realizerFor(bubble.capabilityId).perform(top, null) }
            } else {
                pendingPreviewBubble = bubble
                _ui.update { it.copy(busy = null, busyStage = null, preview = preview) }
            }
        }
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
        val atomsRef = top.metadata[META_OCR_ATOMS_REF] ?: return
        viewModelScope.launch {
            val loaded = withContext(ioDispatcher) {
                runCatching {
                    val layer = AtomCodec.decode(File(atomsRef).readText())
                    decodeSelectionFrame(top.uri.value, SELECTION_MAX_PX)?.let { frame ->
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
        val bmp = com.point.data.cropRegion(
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
            _ui.update { it.copy(message = "Страница ещё не прочитана — искать не в чем", messageIsFailure = true) }
            return
        }
        viewModelScope.launch {
            val loaded = withContext(ioDispatcher) {
                runCatching {
                    val layer = AtomCodec.decode(File(atomsRef).readText())
                    decodeSelectionFrame(top.uri.value, SELECTION_MAX_PX)?.let { frame ->
                        Triple(layer, frame.transform, frame.bitmap.asImageBitmap())
                    }
                }.getOrNull()
            }
            if (loaded == null) {
                _ui.update { it.copy(message = "Не удалось открыть страницу для поиска", messageIsFailure = true) }
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
        claimVoice()
        _ui.update { it.copy(busy = bubble.title, busyStage = null, busyNetwork = isCloud(bubble.capabilityId), busyQuiet = isQuietAction(bubble.capabilityId), message = null, messageIsFailure = false, inputPrompt = null) }
        _ui.update { it.copy(busy = bubble.title, busyStage = null, busyNetwork = isCloud(bubble.capabilityId), busyQuiet = isQuietAction(bubble.capabilityId), message = null, messageOutcome = Outcome.NONE, inputPrompt = null) }
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
     */
    private fun requireCloudConsent(onGranted: () -> Unit) {
        viewModelScope.launch {
            if (runCatching { consent.cloudAllowed() }.getOrDefault(false)) {
                onGranted()
            } else {
                pendingCloud = onGranted
                _ui.update { it.copy(cloudConsent = true) }
            }
        }
    }

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
        claimVoice()
        _ui.update {
            it.copy(
                busy = bubble.title, busyStage = null, busyNetwork = isCloud(bubble.capabilityId),
                busyQuiet = isQuietAction(bubble.capabilityId),
                inputPrompt = null, inputSuggestions = emptyList(), needsImage = null,
            )
        }
        dispatch(bubble) { resolver.realizerFor(bubble.capabilityId).perform(top, text) }
    }

    fun cancelInput() {
        pendingBubble = null
        _ui.update { it.copy(inputPrompt = null, inputSuggestions = emptyList(), needsImage = null, busy = null) }
    }

    // --- AI chat (#4): a multi-turn conversation grounded in the object ---

    /** Open the chat over [obj] (from «Спросить AI»). Cloud consent is already granted by onBubble. */
    private fun openChat(obj: PointObject) {
        _ui.update {
            it.copy(
                chat = ChatState(obj = obj, suggestions = aiSuggestions(obj.state.kind)),
                busy = null, inputPrompt = null, message = null, messageOutcome = Outcome.NONE,
            )
        }
    }

    fun closeChat() = _ui.update { it.copy(chat = null) }

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
        _ui.update { it.copy(chat = chat.copy(messages = history + ChatMessage(ChatRole.USER, message), pending = true)) }
        viewModelScope.launch {
            val target = aiTransformTarget(message)
            if (target != null) {
                val result = runCatching { resolver.realizerFor(target).perform(obj, null) }.getOrNull()
                if (result is ActionResult.Success) {
                    runCatching { sensory.success() }
                    _ui.update { it.copy(chat = null) } // leave the chat and continue on the new object
                    pushFrame(store.put(result.result), target, null)
                } else {
                    appendChatAssistant((result as? ActionResult.Failure)?.reason ?: "Не удалось создать документ")
                }
            } else {
                val reply = runCatching { aiChatResponder.reply(obj, history, message) }
                    .getOrElse { "Не получилось ответить: ${it.message ?: "ошибка"}" }
                appendChatAssistant(reply)
            }
        }
    }

    private fun appendChatAssistant(text: String) {
        _ui.update { s ->
            val c = s.chat ?: return@update s
            s.copy(chat = c.copy(messages = c.messages + ChatMessage(ChatRole.ASSISTANT, text), pending = false))
        }
    }

    // --- Bring-your-own AI key (#19). Summoned on demand or from the Home gear. ---

    fun openKeySettings() {
        // A tiny prefs read; the store is warmed when it's created (Activity start), so it
        // is in-memory by the time the gear or an AI-no-key failure summons the screen.
        _ui.update {
            it.copy(
                keyScreen = userKeys.read() ?: UserAiConfig.DEFAULT, busy = null, message = null, messageOutcome = Outcome.NONE, inputPrompt = null,
                soundEnabled = runCatching { sensorySettings.isSoundEnabled() }.getOrDefault(true),
            )
        }
        refreshUsage()
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

    fun setUsageEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { journal.setEnabled(enabled) }
            refreshUsage()
        }
    }

    fun closeKeySettings() = _ui.update { it.copy(keyScreen = null) }

    // --- «Компьютер» (#147): pair once, then the «На компьютер» bubble appears. ---

    private var discoveryJob: Job? = null

    fun openPcSettings() {
        _ui.update {
            it.copy(pcScreen = PcScreenState(pairing = pcPairings.current()), busy = null, message = null, messageOutcome = Outcome.NONE)
        }
        // #80 v2: the natural sync point — the PC may have gained abilities since pairing.
        pcPairings.current()?.let { pairing ->
            viewModelScope.launch {
                runCatching { pcTransport.fetchCaps(pairing)?.let { caps -> pcCaps.save(caps) } }
            }
        }
        refreshFromPc(force = true)
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            runCatching {
                pcDiscovery.discover().collect { found ->
                    _ui.update { s ->
                        s.pcScreen?.let { s.copy(pcScreen = it.copy(discovered = found)) } ?: s
                    }
                }
            }
        }
    }

    fun closePcSettings() {
        refreshFromPc() // #161: Home is about to show — its banner must be current
        discoveryJob?.cancel()
        discoveryJob = null
        _ui.update { it.copy(pcScreen = null) }
    }

    /** Pair straight from a scanned QR / deep link (`point-pc://host:port/token`). The token
     *  rides in the payload, so — unlike [pairPc] — no `/pair` round-trip to the PC is needed:
     *  the QR being visible on the PC IS the consent, and pairing works even before the PC is
     *  reachable (e.g. firewall). Opens the «Компьютер» screen showing the paired state. */
    fun pairFromPayload(payload: String) {
        val pairing = com.point.core.flow.parsePcPairing(payload)
        if (pairing == null) {
            _ui.update { it.copy(message = "Это не код подключения Point для ПК", messageOutcome = Outcome.FAILED) }
            return
        }
        _ui.update { it.copy(pcScreen = PcScreenState(pairing = pcPairings.current(), busy = true), message = null, messageOutcome = Outcome.NONE) }
        viewModelScope.launch {
            runCatching { pcPairings.save(pairing) }
            runCatching { pcTransport.fetchCaps(pairing)?.let { caps -> pcCaps.save(caps) } }
            runCatching { pcTransport.pushPhoneCaps(pairing, PHONE_ADVERTISED) }
            refreshFromPc(force = true)
            _ui.update { it.copy(pcScreen = PcScreenState(pairing = pairing)) }
        }
    }

    fun pairPc(host: String, port: Int) {
        _ui.update { it.copy(pcScreen = PcScreenState(pairing = pcPairings.current(), busy = true)) }
        viewModelScope.launch {
            val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            val pairing = runCatching { pcTransport.pair(host, port, device) }.getOrNull()
            if (pairing != null) {
                runCatching { pcPairings.save(pairing) }
                // #80: remember what the PC can do — its actions become bubbles from
                // the next launch (synthesis reads the warm cache at process start).
                runCatching { pcTransport.fetchCaps(pairing)?.let { caps -> pcCaps.save(caps) } }
                runCatching { pcTransport.pushPhoneCaps(pairing, PHONE_ADVERTISED) }
                refreshFromPc(force = true) // #161: the fresh pairing may already have a queue
                _ui.update { it.copy(pcScreen = PcScreenState(pairing = pairing)) }
            } else {
                _ui.update {
                    it.copy(
                        pcScreen = PcScreenState(
                            pairing = pcPairings.current(),
                            error = "Не удалось связаться — проверьте адрес и подтвердите на компьютере",
                        ),
                    )
                }
            }
        }
    }

    fun unpairPc() {
        viewModelScope.launch {
            runCatching { pcPairings.clear() }
            runCatching { pcCaps.clear() }
            _ui.update { it.copy(pcScreen = PcScreenState(pairing = null)) }
        }
    }

    // --- Cloud consent (#10): nothing leaves the device before the user agrees once. ---

    fun confirmCloud() {
        val run = pendingCloud ?: return
        pendingCloud = null
        _ui.update { it.copy(cloudConsent = false) }
        viewModelScope.launch {
            runCatching { consent.allowCloud() } // remember, so we ask only once
            run()
        }
    }

    fun declineCloud() {
        pendingCloud = null
        _ui.update { it.copy(cloudConsent = false) }
    }

    // --- Device actions (#66): the installed apps that can open the object, shown inline. ---

    private fun showAppPicker(obj: PointObject) {
        claimVoice()
        _ui.update { it.copy(busy = "Ищу приложения…", busyStage = null, busyQuiet = false, message = null, messageIsFailure = false, inputPrompt = null) }
        _ui.update { it.copy(busy = "Ищу приложения…", busyStage = null, busyQuiet = false, message = null, messageOutcome = Outcome.NONE, inputPrompt = null) }
        viewModelScope.launch {
            val direct = runCatching { appLauncher.handlers(obj) }.getOrDefault(emptyList())
            // Dedup by package: an app that also appears as a bridged target must not double —
            // the picker keys rows by package, and duplicates crash the list. Direct wins.
            val apps = (direct + bridgedHandlers(obj)).distinctBy { it.packageName }
            _ui.update {
                if (apps.isEmpty()) it.copy(busy = null, busyStage = null, message = "Нет приложения для этого объекта", messageOutcome = Outcome.FAILED)
                else it.copy(busy = null, busyStage = null, appPicker = apps)
            }
        }
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
        viewModelScope.launch {
            // #66 slice 4: a direct pick is remembered — on the next launch this app is a
            // first-class bubble in the graph, learning through the same usage signal.
            // Bridged picks are skipped: their capability would need the transform re-run.
            if (via == null) {
                val pick = ChosenApp(obj.state.kind, target.packageName, target.activity, target.label)
                runCatching { chosenApps.record(pick) }
                runCatching { usage.record(CapabilityId("app:${target.packageName}#${obj.state.kind.name}")) }
            }
            val toOpen = if (via != null) bridge(obj, via) else obj
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
        }
    }

    /** Run one transform to produce the object the bridged app can open (#79.1); null on failure. */
    private suspend fun bridge(obj: PointObject, viaCapId: String): PointObject? {
        claimVoice()
        _ui.update { it.copy(busy = "Преобразую…", busyStage = null, busyQuiet = false) }
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
            _ui.update { it.copy(keyScreen = null, message = "Ключ AI сохранён", messageOutcome = Outcome.DONE) }
        }
    }

    /** Save the capabilities applied so far as a favorite chain (auto-named). */
    fun saveCurrentChain() {
        val steps = stack.mapNotNull { it.viaCapability }
        if (steps.isEmpty()) return
        val name = stack.mapNotNull { it.viaTitle }.joinToString(" → ").ifBlank { "Цепочка" }
        viewModelScope.launch {
            runCatching { favorites.save(name, steps) }
            loadFavorites()
            _ui.update { it.copy(message = "Цепочка сохранена: $name", messageOutcome = Outcome.DONE) }
        }
    }

    /** Replay a saved chain on the current object — one tap for a whole workflow. */
    fun applyFavorite(chain: FavoriteChain) {
        val start = stack.lastOrNull()?.obj ?: return
        // A saved chain can hide a cloud step — gate the whole replay on consent (#10),
        // so a favorite is not a back door around the privacy prompt.
        if (chain.steps.any { isCloud(it) }) {
            requireCloudConsent { replayChain(chain, start) }
            return
        }
        replayChain(chain, start)
    }

    private fun replayChain(chain: FavoriteChain, start: PointObject) {
        claimVoice()
        _ui.update { it.copy(busy = "Выполняю цепочку…", busyStage = null, busyNetwork = false, busyQuiet = false, message = null, messageIsFailure = false, inputPrompt = null) }
        _ui.update { it.copy(busy = "Выполняю цепочку…", busyStage = null, busyNetwork = false, busyQuiet = false, message = null, messageOutcome = Outcome.NONE, inputPrompt = null) }
        viewModelScope.launch {
            var current = start
            for (capId in chain.steps) {
                val realizer = runCatching { resolver.realizerFor(capId) }.getOrNull()
                if (realizer == null) {
                    _ui.update { it.copy(busy = null, busyStage = null, message = "Шаг цепочки недоступен", messageOutcome = Outcome.FAILED) }
                    return@launch
                }
                val label = runCatching { registry.byId(capId).label(current.state) }.getOrDefault("")
                val result = runCatching { realizer.perform(current, null) }
                    .getOrElse { ActionResult.Failure(it.message ?: "Не получилось", recoverable = true) }
                when (result) {
                    is ActionResult.Success -> {
                        current = store.put(result.result)
                        pushFrame(current, capId, label)
                    }
                    is ActionResult.Done -> {
                        _ui.update { it.copy(busy = null, busyStage = null, message = result.message, messageOutcome = Outcome.DONE) }
                        return@launch
                    }
                    is ActionResult.Failure -> {
                        _ui.update { it.copy(busy = null, busyStage = null, message = "Цепочка прервана: ${result.reason}", messageOutcome = Outcome.FAILED) }
                        return@launch
                    }
                    is ActionResult.NeedsInput, is ActionResult.NeedsImage -> {
                        // «Требует ввода» — слово контракта, а не человека: на экране это значит,
                        // что шаг хочет спросить, а цепочку человек запускал одним тапом.
                        _ui.update { it.copy(busy = null, busyStage = null, message = "Цепочка остановлена: шагу нужен ваш ответ", messageOutcome = Outcome.FAILED) }
                        return@launch
                    }
                }
            }
        }
    }

    private fun dispatch(bubble: Bubble, action: suspend () -> ActionResult) {
        runCatching { sensory.tap() } // M4: the choice answers in the hand at once
        // Задача действия хранится, потому что человек имеет право передумать (#288): «В Excel»
        // — это две последовательные модели по фото, минута и больше, и до сих пор прервать её
        // было нечем; экран обещал «несколько секунд» и упирался в последний шаг.
        actionJob?.cancel()
        val voice = claimVoice()
        actionJob = viewModelScope.launch {
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
                .onSuccess { result -> handleResult(result, bubble) }
                .onFailure { e ->
                    // Отмена — не ошибка: человек передумал, и сказать ему «Ошибка» было бы враньём.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // «Ошибка» — слово системы; человеку под объектом нужен исход, а не термин.
                    _ui.update { it.copy(busy = null, busyStage = null, message = e.message ?: "Не получилось", messageOutcome = Outcome.FAILED) }
                }
        }
    }

    /**
     * Отменить идущее действие (#288). Работа снимается, экран возвращается к объекту, и человек
     * видит, что произошло: молчаливое исчезновение экрана неотличимо от сбоя.
     */
    fun cancelAction() {
        val job = actionJob ?: return
        actionJob = null
        job.cancel()
        claimVoice() // остановленная работа замолкает сразу — её хвост ещё идёт
        _ui.update { it.copy(busy = null, busyStage = null, message = "Отменено") }

        // Отмена — не отказ: человек сам передумал, и знак исхода не имеет права ставить ему «✕».
        _ui.update { it.copy(busy = null, busyStage = null, message = "Отменено", messageIsFailure = false) }
        // Отмена — не отказ и не удача: человек сам передумал. Знак исхода не имеет права ставить
        // ему ни «✕», ни «✓ Готово» — работа не дошла до конца, и заявлять о ней нечего.
        _ui.update { it.copy(busy = null, busyStage = null, message = "Отменено", messageOutcome = Outcome.NONE) }
    }

    private suspend fun handleResult(result: ActionResult, bubble: Bubble) {
        when (result) {
            is ActionResult.Success -> {
                runCatching { sensory.success() } // M4: the transformation lands in the hand
                // #117 graph metrics: the edge actually traversed — kinds and id only.
                val fromKind = stack.lastOrNull()?.obj?.state?.kind?.name ?: "?"
                pushFrame(store.put(result.result), bubble.capabilityId, bubble.title)
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
                // A "no AI key" failure summons the key screen on demand instead of just erroring.
                if (result.reason.contains("задайте свой ключ")) openKeySettings()
                else _ui.update { it.copy(busy = null, busyStage = null, message = result.reason, messageOutcome = Outcome.FAILED) }
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
        if (_ui.value.chat != null) {
            closeChat() // #4: back leaves the chat, returning to the object
            return true
        }
        if (_ui.value.keyScreen != null) {
            closeKeySettings()
            return true
        }
        if (_ui.value.pcScreen != null) {
            closePcSettings()
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
        refreshFavorites()
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
        refreshFavorites()
        persistJourney()
    }

    private fun currentPath(): List<PathStep> =
        stack.map { PathStep(it.obj.state.kind, it.viaTitle) }

    /** Discover (#114): the first FOLDED action (beyond the big likely ones) the user has
     *  never tried — instant terminals are too obvious to be a discovery. Using it records
     *  usage, which retires the hint on the next frame by itself. */
    private fun discoverFor(bubbles: List<Bubble>): Bubble? {
        val counts = runCatching { usage.counts() }.getOrDefault(emptyMap())
        return bubbles.drop(likelyCount(bubbles.size)).firstOrNull {
            it.tier != BubbleTier.INSTANT && (counts[it.capabilityId] ?: 0) == 0
        }
    }

    fun hasFlow(): Boolean = stack.isNotEmpty()

    fun endFlow() {
        cancelEnrichment()
        stack.clear()
        pendingBubble = null
        pendingPreviewBubble = null
        _ui.update { FlowUiState() }
        viewModelScope.launch {
            runCatching { store.clear() }
            runCatching { flowSnapshot.clear() } // the journey ended on purpose — forget it (#7)
        }
    }

    private fun pushFrame(obj: PointObject, via: CapabilityId? = null, viaTitle: String? = null) {
        val bubbles = registry.bubblesFor(obj.state)
        val frame = FlowFrame(
            obj, bubbles, via, viaTitle,
            latent = registry.latentBubblesFor(obj.state),
            discover = discoverFor(bubbles),
            pinned = runCatching { pins.pinnedFor(obj.state.kind) }.getOrNull(),
        )
        stack.addLast(frame)
        _ui.update {
            it.copy(
                busy = null, busyStage = null, frame = frame, message = null, messageOutcome = Outcome.NONE, inputPrompt = null, inputSuggestions = emptyList(),
                needsImage = null, preview = null, path = currentPath(),
            )
        }
        refreshFavorites()
        persistJourney()
        enrichInBackground(obj)
        loadChildrenIfCollection(obj)
        loadTextPreviewIfText(obj)
        loadObjectPreview(obj)
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
            val items = runCatching { store.children(obj) }.getOrDefault(emptyList())
            if (items.isEmpty()) return@launch

            val topIndex = stack.lastIndex
            val top = stack.getOrNull(topIndex) ?: return@launch
            if (top.obj.id != obj.id) return@launch

            val refreshed = top.copy(items = items)
            stack[topIndex] = refreshed
            _ui.update { if (it.frame?.obj?.id == obj.id) it.copy(frame = refreshed) else it }
        }
    }

    /** Recompute which saved chains apply to the top object + whether the current
     *  path is savable. Pure/synchronous — reads the in-memory favorites. */
    private fun refreshFavorites() {
        val top = stack.lastOrNull()
        if (top == null) {
            _ui.update { it.copy(favorites = emptyList(), canSaveChain = false) }
            return
        }
        val applicable = allFavorites.filter { chain ->
            chain.steps.isNotEmpty() &&
                runCatching { registry.byId(chain.steps.first()).accepts(top.obj.state) }.getOrDefault(false)
        }
        val canSave = stack.any { it.viaCapability != null }
        _ui.update { it.copy(favorites = applicable, canSaveChain = canSave) }
    }

    private suspend fun loadFavorites() {
        allFavorites = runCatching { favorites.all() }.getOrDefault(emptyList())
        refreshFavorites()
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

        val newBubbles = if (objChanged) registry.bubblesFor(newState) else frame.bubbles
        val refreshed = frame.copy(
            obj = frame.obj.copy(state = newState, metadata = newMetadata),
            bubbles = newBubbles,
            latent = if (objChanged) registry.latentBubblesFor(newState) else frame.latent,
            enriching = update.running,
            discover = if (objChanged) discoverFor(newBubbles) else frame.discover,
            found = newFound,
            relations = newRelations,
        )
        stack[index] = refreshed
        _ui.update { if (it.frame?.obj?.id == source.id) it.copy(frame = refreshed) else it }
        if (objChanged) {
            refreshFavorites()
            persistJourney() // #7: understanding survives process death together with the step
        }
    }

    private companion object {
        /** Metadata that points at a file enrichment just wrote — a stale pointer would send
         *  «Распознать текст» to a scratch file from a previous run. The atoms sidecar (#257)
         *  is the same class of pointer: each OCR run writes a fresh atoms.tsv, and a stale ref
         *  would tear the text/atoms pair apart on restoreJourney re-enrichment. */
        val REFRESHABLE_META = setOf(
            com.point.core.flow.META_OCR_TEXT_REF,
            com.point.core.flow.META_OCR_ATOMS_REF,
        )
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
