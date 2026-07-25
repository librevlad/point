package com.point

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.point.core.flow.AppLauncher
import com.point.core.flow.AppTarget
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.Enrichment
import com.point.core.flow.EnrichmentUpdate
import com.point.core.flow.FavoritesStore
import com.point.core.flow.FlowSnapshotStore
import com.point.core.flow.HistoryStore
import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.Resolver
import com.point.core.flow.SensoryFeedback
import com.point.core.flow.SensorySettings
import com.point.core.flow.UsageEvent
import com.point.core.flow.UsageEventType
import com.point.core.flow.UsageJournal
import com.point.core.flow.UserAiConfig
import com.point.core.flow.UserKeyStore
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.FavoriteChain
import com.point.core.model.FlowSnapshotFrame
import com.point.core.model.HistoryEntry
import com.point.core.model.Intent
import com.point.core.model.BubbleTier
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.ui.likelyCount
import com.point.executors.Bitmaps
import com.point.executors.OpenInCapability
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
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
    private val enrichment: Enrichment,
    private val history: HistoryStore,
    private val favorites: FavoritesStore,
    private val usage: CapabilityUsage,
    private val userKeys: UserKeyStore,
    private val journal: UsageJournal,
    private val consent: PrivacyConsent,
    private val appLauncher: AppLauncher,
    private val pdfRasterizer: PdfRasterizer,
    private val sensory: SensoryFeedback,
    private val sensorySettings: SensorySettings,
    private val flowSnapshot: FlowSnapshotStore,
) : ViewModel() {

    private val stack = ArrayDeque<FlowFrame>()
    private val enrichJobs = mutableListOf<Job>()
    private var pendingBubble: Bubble? = null
    /** A cloud action deferred until the user grants consent (#10); run on confirm. */
    private var pendingCloud: (() -> Unit)? = null
    /** A bubble whose preview is shown, deferred until the user confirms it (#97). */
    private var pendingPreviewBubble: Bubble? = null
    private var allFavorites: List<FavoriteChain> = emptyList()

    private val _ui = MutableStateFlow(FlowUiState())
    val ui: StateFlow<FlowUiState> = _ui.asStateFlow()

    private val _recent = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val recent: StateFlow<List<HistoryEntry>> = _recent.asStateFlow()

    private val _clipboard = MutableStateFlow<String?>(null)
    /** Actionable text sitting in the clipboard when Point opened — a dismissible Home suggestion (#72). */
    val clipboard: StateFlow<String?> = _clipboard.asStateFlow()
    private var lastClipboard: String? = null

    /** Set synchronously by a fresh share BEFORE its coroutine runs — a stale snapshot
     *  must never race over the user's new intent (#7). */
    private var freshShareArrived = false

    init {
        viewModelScope.launch { loadFavorites() }
        restoreJourney()
    }

    /** #7: re-materialise the flow after process death. Scratch files survive (clear()
     *  runs only at flow end), so the journey resumes on the same object and step —
     *  features re-derive instantly from the kept metadata via enrichment. */
    private fun restoreJourney() {
        viewModelScope.launch {
            val frames = runCatching { flowSnapshot.load() }.getOrDefault(emptyList())
            if (frames.isEmpty() || freshShareArrived || stack.isNotEmpty()) return@launch
            val alive = frames.filter { runCatching { java.io.File(it.ref).isFile }.getOrDefault(false) }
            if (alive.isEmpty()) {
                runCatching { flowSnapshot.clear() }
                return@launch
            }
            alive.forEach { f ->
                pushFrame(
                    PointObject(f.id, f.mime, com.point.core.model.ScratchRef(f.ref),
                        com.point.core.model.ObjectState(f.kind), f.metadata),
                    via = f.viaCapabilityId?.let { CapabilityId(it) },
                    viaTitle = f.viaTitle,
                )
            }
        }
    }

    fun onShared(sourceUri: String, mime: String) {
        freshShareArrived = true
        _ui.update { it.copy(busy = "Открываю…", busyNetwork = false, busyQuiet = false, message = null, inputPrompt = null) }
        viewModelScope.launch {
            val obj = runCatching {
                store.clear()
                store.ingest(sourceUri, mime)
            }.getOrElse { e ->
                _ui.update { it.copy(busy = null, message = "Не удалось открыть: ${e.message}") }
                return@launch
            }
            runCatching { history.record(obj) }
            runCatching { journal.record(UsageEvent(UsageEventType.SHARED, obj.state.kind.name)) }
            cancelEnrichment()
            stack.clear()
            pushFrame(obj)
        }
    }

    /** Several shared files → one COLLECTION (the inbound half of collections;
     *  e.g. several photos to merge into a PDF). */
    fun onSharedMultiple(sources: List<String>) {
        freshShareArrived = true
        _ui.update { it.copy(busy = "Открываю…", busyNetwork = false, busyQuiet = false, message = null, inputPrompt = null) }
        viewModelScope.launch {
            val obj = runCatching {
                store.clear()
                store.ingestMultiple(sources)
            }.getOrElse { e ->
                _ui.update { it.copy(busy = null, message = "Не удалось открыть: ${e.message}") }
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
     * Offer to act on clipboard text when Point opens — only if it looks actionable (a phone/email/
     * URL) and is new. The Activity reads the clipboard **foreground-only** (Android 10+ rule); Point
     * never watches the clipboard in the background (#72). Reaches messengers: copy → open Point → act.
     */
    fun offerClipboard(text: String?) {
        val t = text?.trim().orEmpty()
        _clipboard.value = t.takeIf { it.isNotBlank() && it.length <= MAX_CLIP && it != lastClipboard }
    }

    /** Dismiss the clipboard suggestion and remember it, so the same text is not re-offered. */
    fun dismissClipboard() {
        lastClipboard = _clipboard.value
        _clipboard.value = null
    }

    fun openFromHistory(entry: HistoryEntry) {
        freshShareArrived = true
        _ui.update { it.copy(busy = "Открываю…", busyNetwork = false, busyQuiet = false, message = null, inputPrompt = null) }
        viewModelScope.launch {
            val obj = runCatching { history.open(entry.id) }.getOrNull()
            if (obj == null) {
                _ui.update { it.copy(busy = null, message = "Объект недоступен") }
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
        _ui.update {
            it.copy(
                busy = bubble.title, busyNetwork = isCloud(bubble.capabilityId),
                busyQuiet = isQuietAction(bubble.capabilityId), message = null,
                inputPrompt = null,
            )
        }
        viewModelScope.launch {
            val preview = runCatching { resolver.realizerFor(bubble.capabilityId).preview(top) }.getOrNull()
            if (preview == null) {
                dispatch(bubble) { resolver.realizerFor(bubble.capabilityId).perform(top, null) }
            } else {
                pendingPreviewBubble = bubble
                _ui.update { it.copy(busy = null, preview = preview) }
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

    private fun runOnObject(bubble: Bubble, top: PointObject) {
        _ui.update { it.copy(busy = bubble.title, busyNetwork = isCloud(bubble.capabilityId), busyQuiet = isQuietAction(bubble.capabilityId), message = null, inputPrompt = null) }
        dispatch(bubble) { resolver.realizerFor(bubble.capabilityId).perform(top, null) }
    }

    private fun isCloud(id: CapabilityId) =
        runCatching { registry.byId(id).meta.network }.getOrDefault(false)

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

    fun submitAmendment(text: String) {
        val bubble = pendingBubble ?: return
        val top = stack.lastOrNull()?.obj ?: return
        pendingBubble = null
        _ui.update {
            it.copy(
                busy = bubble.title, busyNetwork = isCloud(bubble.capabilityId),
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

    // --- Bring-your-own AI key (#19). Summoned on demand or from the Home gear. ---

    fun openKeySettings() {
        // A tiny prefs read; the store is warmed when it's created (Activity start), so it
        // is in-memory by the time the gear or an AI-no-key failure summons the screen.
        _ui.update {
            it.copy(
                keyScreen = userKeys.read() ?: UserAiConfig.DEFAULT, busy = null, message = null, inputPrompt = null,
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
        _ui.update { it.copy(busy = "Ищу приложения…", busyQuiet = false, message = null, inputPrompt = null) }
        viewModelScope.launch {
            val direct = runCatching { appLauncher.handlers(obj) }.getOrDefault(emptyList())
            // Dedup by package: an app that also appears as a bridged target must not double —
            // the picker keys rows by package, and duplicates crash the list. Direct wins.
            val apps = (direct + bridgedHandlers(obj)).distinctBy { it.packageName }
            _ui.update {
                if (apps.isEmpty()) it.copy(busy = null, message = "Нет приложения для этого объекта")
                else it.copy(busy = null, appPicker = apps)
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
            val toOpen = if (via != null) bridge(obj, via) else obj
            if (toOpen == null) {
                _ui.update { it.copy(busy = null, message = "Не удалось преобразовать") }
                return@launch
            }
            runCatching { appLauncher.launch(target, toOpen) }
                .onSuccess { _ui.update { it.copy(busy = null, message = "Открываю в ${target.label}") } }
                .onFailure { e -> _ui.update { it.copy(busy = null, message = e.message ?: "Не удалось открыть") } }
        }
    }

    /** Run one transform to produce the object the bridged app can open (#79.1); null on failure. */
    private suspend fun bridge(obj: PointObject, viaCapId: String): PointObject? {
        _ui.update { it.copy(busy = "Преобразую…", busyQuiet = false) }
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
            _ui.update { it.copy(keyScreen = null, message = "Ключ AI сохранён") }
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
            _ui.update { it.copy(message = "Цепочка сохранена: $name") }
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
        _ui.update { it.copy(busy = "Выполняю цепочку…", busyNetwork = false, busyQuiet = false, message = null, inputPrompt = null) }
        viewModelScope.launch {
            var current = start
            for (capId in chain.steps) {
                val realizer = runCatching { resolver.realizerFor(capId) }.getOrNull()
                if (realizer == null) {
                    _ui.update { it.copy(busy = null, message = "Шаг цепочки недоступен") }
                    return@launch
                }
                val label = runCatching { registry.byId(capId).label(current.state) }.getOrDefault("")
                val result = runCatching { realizer.perform(current, null) }
                    .getOrElse { ActionResult.Failure(it.message ?: "Ошибка", recoverable = true) }
                when (result) {
                    is ActionResult.Success -> {
                        current = store.put(result.result)
                        pushFrame(current, capId, label)
                    }
                    is ActionResult.Done -> {
                        _ui.update { it.copy(busy = null, message = result.message) }
                        return@launch
                    }
                    is ActionResult.Failure -> {
                        _ui.update { it.copy(busy = null, message = "Цепочка прервана: ${result.reason}") }
                        return@launch
                    }
                    is ActionResult.NeedsInput, is ActionResult.NeedsImage -> {
                        _ui.update { it.copy(busy = null, message = "Цепочка требует ввода — прервана") }
                        return@launch
                    }
                }
            }
        }
    }

    private fun dispatch(bubble: Bubble, action: suspend () -> ActionResult) {
        runCatching { sensory.tap() } // M4: the choice answers in the hand at once
        viewModelScope.launch {
            runCatching { usage.record(bubble.capabilityId) } // learning signal for BubblePolicy
            runCatching { journal.record(UsageEvent(UsageEventType.ACTION, bubble.capabilityId.value)) }
            runCatching { action() }
                .onSuccess { result -> handleResult(result, bubble) }
                .onFailure { e -> _ui.update { it.copy(busy = null, message = e.message ?: "Ошибка") } }
        }
    }

    private suspend fun handleResult(result: ActionResult, bubble: Bubble) {
        when (result) {
            is ActionResult.Success -> {
                runCatching { sensory.success() } // M4: the transformation lands in the hand
                pushFrame(store.put(result.result), bubble.capabilityId, bubble.title)
            }
            is ActionResult.Done -> {
                runCatching { sensory.success() }
                // A flow carried to a terminal (Share/Save/Open) — a task handled in Point.
                runCatching { journal.record(UsageEvent(UsageEventType.COMPLETED, bubble.capabilityId.value)) }
                _ui.update { it.copy(busy = null, message = result.message) }
            }
            is ActionResult.Failure -> {
                runCatching { sensory.failure() } // M4: a failure bumps, never buzzes long
                // A "no AI key" failure summons the key screen on demand instead of just erroring.
                if (result.reason.contains("задайте свой ключ")) openKeySettings()
                else _ui.update { it.copy(busy = null, message = result.reason) }
            }
            is ActionResult.NeedsInput -> {
                pendingBubble = bubble
                _ui.update { it.copy(busy = null, inputPrompt = result.prompt, inputSuggestions = result.suggestions) }
            }
            is ActionResult.NeedsImage -> {
                // Same pending-bubble mechanism as NeedsInput; the picked image URI is fed back
                // through submitAmendment (the host opens the photo picker on this flag).
                pendingBubble = bubble
                _ui.update { it.copy(busy = null, needsImage = result.prompt) }
            }
        }
    }

    fun onBack(): Boolean {
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
        _ui.update { it.copy(frame = top, message = null, path = currentPath()) }
        refreshFavorites()
        persistJourney()
        return true
    }

    /** Timeline tap (#114): pop back to the [index]-th step of the journey in one move. */
    fun jumpTo(index: Int) {
        if (index < 0 || index >= stack.size - 1) return
        while (stack.size - 1 > index) stack.removeLast()
        val top = stack.last()
        _ui.update { it.copy(frame = top, message = null, path = currentPath()) }
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
        )
        stack.addLast(frame)
        _ui.update {
            it.copy(
                busy = null, frame = frame, message = null, inputPrompt = null, inputSuggestions = emptyList(),
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

    /** For a visual frame (IMAGE / PDF), decode a real thumbnail off-main and attach it (only
     *  while that object is still on the stack). The hero is the object, not an icon (#114);
     *  a PDF shows its rendered first page via [previewSource]. */
    private fun loadObjectPreview(obj: PointObject) {
        if (obj.state.kind != ObjectKind.IMAGE && obj.state.kind != ObjectKind.PDF) return
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
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

    /** Apply one enrichment snapshot to its object's frame — found by id, not by top:
     *  a slow OCR finishing after the user moved on still lands on the frame below,
     *  so its findings are there when they come back. */
    private fun applyEnrichment(source: PointObject, update: EnrichmentUpdate) {
        val index = stack.indexOfLast { it.obj.id == source.id }
        val frame = stack.getOrNull(index) ?: return
        val newState = update.features.fold(frame.obj.state) { state, feature -> state.with(feature) }
        val newMetadata = frame.obj.metadata + update.metadata
        val objChanged = newState != frame.obj.state || newMetadata != frame.obj.metadata
        if (!objChanged && update.running == frame.enriching) return

        val newBubbles = if (objChanged) registry.bubblesFor(newState) else frame.bubbles
        val refreshed = frame.copy(
            obj = frame.obj.copy(state = newState, metadata = newMetadata),
            bubbles = newBubbles,
            latent = if (objChanged) registry.latentBubblesFor(newState) else frame.latent,
            enriching = update.running,
            discover = if (objChanged) discoverFor(newBubbles) else frame.discover,
        )
        stack[index] = refreshed
        _ui.update { if (it.frame?.obj?.id == source.id) it.copy(frame = refreshed) else it }
        if (objChanged) {
            refreshFavorites()
            persistJourney() // #7: understanding survives process death together with the step
        }
    }

    private fun cancelEnrichment() {
        enrichJobs.forEach { it.cancel() }
        enrichJobs.clear()
    }
}

private const val MAX_CLIP = 2000
private const val PREVIEW_MAX_PX = 640
