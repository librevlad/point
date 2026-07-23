package com.point

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.point.core.flow.AppLauncher
import com.point.core.flow.AppTarget
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.Enrichment
import com.point.core.flow.FavoritesStore
import com.point.core.flow.HistoryStore
import com.point.core.flow.ObjectStore
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.Resolver
import com.point.core.flow.UsageEvent
import com.point.core.flow.UsageEventType
import com.point.core.flow.UsageJournal
import com.point.core.flow.UserAiConfig
import com.point.core.flow.UserKeyStore
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.FavoriteChain
import com.point.core.model.HistoryEntry
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.executors.OpenInCapability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) : ViewModel() {

    private val stack = ArrayDeque<FlowFrame>()
    private var pendingBubble: Bubble? = null
    /** A cloud action deferred until the user grants consent (#10); run on confirm. */
    private var pendingCloud: (() -> Unit)? = null
    private var allFavorites: List<FavoriteChain> = emptyList()

    private val _ui = MutableStateFlow(FlowUiState())
    val ui: StateFlow<FlowUiState> = _ui.asStateFlow()

    private val _recent = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val recent: StateFlow<List<HistoryEntry>> = _recent.asStateFlow()

    private val _clipboard = MutableStateFlow<String?>(null)
    /** Actionable text sitting in the clipboard when Point opened — a dismissible Home suggestion (#72). */
    val clipboard: StateFlow<String?> = _clipboard.asStateFlow()
    private var lastClipboard: String? = null

    init {
        viewModelScope.launch { loadFavorites() }
    }

    fun onShared(sourceUri: String, mime: String) {
        _ui.update { it.copy(busy = "Открываю…", busyNetwork = false, message = null, inputPrompt = null) }
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
            stack.clear()
            pushFrame(obj)
        }
    }

    /** Several shared files → one COLLECTION (the inbound half of collections;
     *  e.g. several photos to merge into a PDF). */
    fun onSharedMultiple(sources: List<String>) {
        _ui.update { it.copy(busy = "Открываю…", busyNetwork = false, message = null, inputPrompt = null) }
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
        _clipboard.value = t.takeIf {
            it.isNotBlank() && it.length <= MAX_CLIP && it != lastClipboard && ACTIONABLE_CLIP.containsMatchIn(it)
        }
    }

    /** Dismiss the clipboard suggestion and remember it, so the same text is not re-offered. */
    fun dismissClipboard() {
        lastClipboard = _clipboard.value
        _clipboard.value = null
    }

    fun openFromHistory(entry: HistoryEntry) {
        _ui.update { it.copy(busy = "Открываю…", busyNetwork = false, message = null, inputPrompt = null) }
        viewModelScope.launch {
            val obj = runCatching { history.open(entry.id) }.getOrNull()
            if (obj == null) {
                _ui.update { it.copy(busy = null, message = "Объект недоступен") }
                return@launch
            }
            runCatching { store.clear() }
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
            requireCloudConsent { runOnObject(bubble, top) }
            return
        }
        runOnObject(bubble, top)
    }

    private fun runOnObject(bubble: Bubble, top: PointObject) {
        _ui.update { it.copy(busy = bubble.title, busyNetwork = isCloud(bubble.capabilityId), message = null, inputPrompt = null, selectedIntent = null, intentBubbles = emptyList()) }
        dispatch(bubble) { resolver.realizerFor(bubble.capabilityId).perform(top, null) }
    }

    private fun isCloud(id: CapabilityId) =
        runCatching { registry.byId(id).meta.network }.getOrDefault(false)

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
                _ui.update { it.copy(cloudConsent = true, selectedIntent = null, intentBubbles = emptyList()) }
            }
        }
    }

    /** Intent-first: the user picks a goal (Понять / Подготовить / Отправить). If exactly
     *  one capability serves it for this object, run it; otherwise reveal that intent's
     *  capabilities as the next choice. */
    fun onIntent(intent: Intent) {
        val top = stack.lastOrNull() ?: return
        val caps = top.bubbles.filter { intent in registry.byId(it.capabilityId).intents(top.obj.state) }
        when (caps.size) {
            0 -> return
            1 -> onBubble(caps.first())
            else -> _ui.update { it.copy(selectedIntent = intent, intentBubbles = caps) }
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
        _ui.update { it.copy(busy = bubble.title, busyNetwork = isCloud(bubble.capabilityId), inputPrompt = null) }
        dispatch(bubble) { resolver.realizerFor(bubble.capabilityId).perform(top, text) }
    }

    fun cancelInput() {
        pendingBubble = null
        _ui.update { it.copy(inputPrompt = null, busy = null) }
    }

    // --- Bring-your-own AI key (#19). Summoned on demand or from the Home gear. ---

    fun openKeySettings() {
        // A tiny prefs read; the store is warmed when it's created (Activity start), so it
        // is in-memory by the time the gear or an AI-no-key failure summons the screen.
        _ui.update {
            it.copy(keyScreen = userKeys.read() ?: UserAiConfig.DEFAULT, busy = null, message = null, inputPrompt = null)
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
        _ui.update { it.copy(busy = "Ищу приложения…", message = null, inputPrompt = null, selectedIntent = null, intentBubbles = emptyList()) }
        viewModelScope.launch {
            val direct = runCatching { appLauncher.handlers(obj) }.getOrDefault(emptyList())
            val apps = direct + bridgedHandlers(obj)
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
        _ui.update { it.copy(busy = "Преобразую…") }
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
        _ui.update { it.copy(busy = "Выполняю цепочку…", busyNetwork = false, message = null, inputPrompt = null) }
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
                    is ActionResult.NeedsInput -> {
                        _ui.update { it.copy(busy = null, message = "Цепочка требует ввода — прервана") }
                        return@launch
                    }
                }
            }
        }
    }

    private fun dispatch(bubble: Bubble, action: suspend () -> ActionResult) {
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
            is ActionResult.Success -> pushFrame(store.put(result.result), bubble.capabilityId, bubble.title)
            is ActionResult.Done -> {
                // A flow carried to a terminal (Share/Save/Open) — a task handled in Point.
                runCatching { journal.record(UsageEvent(UsageEventType.COMPLETED, bubble.capabilityId.value)) }
                _ui.update { it.copy(busy = null, message = result.message) }
            }
            is ActionResult.Failure ->
                // A "no AI key" failure summons the key screen on demand instead of just erroring.
                if (result.reason.contains("задайте свой ключ")) openKeySettings()
                else _ui.update { it.copy(busy = null, message = result.reason) }
            is ActionResult.NeedsInput -> {
                pendingBubble = bubble
                _ui.update { it.copy(busy = null, inputPrompt = result.prompt) }
            }
        }
    }

    fun onBack(): Boolean {
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
        if (_ui.value.inputPrompt != null) {
            cancelInput()
            return true
        }
        if (_ui.value.selectedIntent != null) {
            _ui.update { it.copy(selectedIntent = null, intentBubbles = emptyList()) }
            return true
        }
        if (stack.size <= 1) return false
        stack.removeLast()
        val top = stack.last()
        _ui.update {
            it.copy(
                frame = top, message = null,
                intents = registry.intentsFor(top.obj.state), selectedIntent = null, intentBubbles = emptyList(),
            )
        }
        refreshFavorites()
        return true
    }

    fun hasFlow(): Boolean = stack.isNotEmpty()

    fun endFlow() {
        stack.clear()
        pendingBubble = null
        _ui.update { FlowUiState() }
        viewModelScope.launch { runCatching { store.clear() } }
    }

    private fun pushFrame(obj: PointObject, via: CapabilityId? = null, viaTitle: String? = null) {
        val frame = FlowFrame(obj, registry.bubblesFor(obj.state), via, viaTitle)
        stack.addLast(frame)
        _ui.update {
            it.copy(
                busy = null, frame = frame, message = null, inputPrompt = null,
                intents = registry.intentsFor(obj.state), selectedIntent = null, intentBubbles = emptyList(),
            )
        }
        refreshFavorites()
        enrichInBackground(obj)
        loadChildrenIfCollection(obj)
        loadTextPreviewIfText(obj)
    }

    /** For a TEXT frame, read a bounded preview of its content and attach it to the
     *  frame (only while that object is still on top). Mirrors [enrichInBackground]. */
    private fun loadTextPreviewIfText(obj: PointObject) {
        if (obj.state.kind != ObjectKind.TEXT) return
        viewModelScope.launch {
            val text = runCatching { store.readText(obj, limit = 100_000) }.getOrDefault("")
            if (text.isBlank()) return@launch

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

    private fun enrichInBackground(obj: PointObject) {
        viewModelScope.launch {
            val extra = runCatching { enrichment.enrich(obj) }.getOrDefault(emptySet())
            if (extra.isEmpty()) return@launch

            val topIndex = stack.lastIndex
            val top = stack.getOrNull(topIndex) ?: return@launch
            if (top.obj.id != obj.id) return@launch

            val enrichedState = extra.fold(top.obj.state) { state, feature -> state.with(feature) }
            if (enrichedState == top.obj.state) return@launch

            val enrichedObj = top.obj.copy(state = enrichedState)
            val refreshed = top.copy(obj = enrichedObj, bubbles = registry.bubblesFor(enrichedState))
            stack[topIndex] = refreshed
            _ui.update {
                if (it.frame === top) it.copy(frame = refreshed, intents = registry.intentsFor(enrichedState)) else it
            }
            refreshFavorites()
        }
    }
}

private const val MAX_CLIP = 2000

/** Clipboard text worth offering an action for: a URL, an email, or a phone-ish number. */
private val ACTIONABLE_CLIP = Regex("""(https?://\S+)|([\w.+-]+@[\w-]+\.[\w.-]+)|(\+?\d[\d\s()-]{6,}\d)""")
