package com.point

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.Enrichment
import com.point.core.flow.FavoritesStore
import com.point.core.flow.HistoryStore
import com.point.core.flow.ObjectStore
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.FavoriteChain
import com.point.core.model.HistoryEntry
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
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
) : ViewModel() {

    private val stack = ArrayDeque<FlowFrame>()
    private var pendingBubble: Bubble? = null
    private var allFavorites: List<FavoriteChain> = emptyList()

    private val _ui = MutableStateFlow(FlowUiState())
    val ui: StateFlow<FlowUiState> = _ui.asStateFlow()

    private val _recent = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val recent: StateFlow<List<HistoryEntry>> = _recent.asStateFlow()

    init {
        viewModelScope.launch { loadFavorites() }
    }

    fun onShared(sourceUri: String, mime: String) {
        _ui.update { it.copy(loading = true, message = null, inputPrompt = null) }
        viewModelScope.launch {
            val obj = runCatching {
                store.clear()
                store.ingest(sourceUri, mime)
            }.getOrElse { e ->
                _ui.update { it.copy(loading = false, message = "Не удалось открыть: ${e.message}") }
                return@launch
            }
            runCatching { history.record(obj) }
            stack.clear()
            pushFrame(obj)
        }
    }

    /** Several shared files → one COLLECTION (the inbound half of collections;
     *  e.g. several photos to merge into a PDF). */
    fun onSharedMultiple(sources: List<String>) {
        _ui.update { it.copy(loading = true, message = null, inputPrompt = null) }
        viewModelScope.launch {
            val obj = runCatching {
                store.clear()
                store.ingestMultiple(sources)
            }.getOrElse { e ->
                _ui.update { it.copy(loading = false, message = "Не удалось открыть: ${e.message}") }
                return@launch
            }
            // A collection is a transient scratch directory — History copies a single file, so skip it.
            stack.clear()
            pushFrame(obj)
        }
    }

    fun loadRecent() {
        viewModelScope.launch {
            _recent.value = runCatching { history.recent() }.getOrDefault(emptyList())
        }
    }

    fun openFromHistory(entry: HistoryEntry) {
        _ui.update { it.copy(loading = true, message = null, inputPrompt = null) }
        viewModelScope.launch {
            val obj = runCatching { history.open(entry.id) }.getOrNull()
            if (obj == null) {
                _ui.update { it.copy(loading = false, message = "Объект недоступен") }
                return@launch
            }
            runCatching { store.clear() }
            stack.clear()
            pushFrame(obj)
        }
    }

    fun onBubble(bubble: Bubble) {
        val top = stack.lastOrNull()?.obj ?: return
        _ui.update { it.copy(loading = true, message = null, inputPrompt = null, selectedIntent = null, intentBubbles = emptyList()) }
        dispatch(bubble) { resolver.realizerFor(bubble.capabilityId).perform(top, null) }
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
        _ui.update { it.copy(loading = true, inputPrompt = null) }
        dispatch(bubble) { resolver.realizerFor(bubble.capabilityId).perform(top, text) }
    }

    fun cancelInput() {
        pendingBubble = null
        _ui.update { it.copy(inputPrompt = null, loading = false) }
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
        _ui.update { it.copy(loading = true, message = null, inputPrompt = null) }
        viewModelScope.launch {
            var current = start
            for (capId in chain.steps) {
                val realizer = runCatching { resolver.realizerFor(capId) }.getOrNull()
                if (realizer == null) {
                    _ui.update { it.copy(loading = false, message = "Шаг цепочки недоступен") }
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
                        _ui.update { it.copy(loading = false, message = result.message) }
                        return@launch
                    }
                    is ActionResult.Failure -> {
                        _ui.update { it.copy(loading = false, message = "Цепочка прервана: ${result.reason}") }
                        return@launch
                    }
                    is ActionResult.NeedsInput -> {
                        _ui.update { it.copy(loading = false, message = "Цепочка требует ввода — прервана") }
                        return@launch
                    }
                }
            }
        }
    }

    private fun dispatch(bubble: Bubble, action: suspend () -> ActionResult) {
        viewModelScope.launch {
            runCatching { usage.record(bubble.capabilityId) } // learning signal for BubblePolicy
            runCatching { action() }
                .onSuccess { result -> handleResult(result, bubble) }
                .onFailure { e -> _ui.update { it.copy(loading = false, message = e.message ?: "Ошибка") } }
        }
    }

    private suspend fun handleResult(result: ActionResult, bubble: Bubble) {
        when (result) {
            is ActionResult.Success -> pushFrame(store.put(result.result), bubble.capabilityId, bubble.title)
            is ActionResult.Done -> _ui.update { it.copy(loading = false, message = result.message) }
            is ActionResult.Failure -> _ui.update { it.copy(loading = false, message = result.reason) }
            is ActionResult.NeedsInput -> {
                pendingBubble = bubble
                _ui.update { it.copy(loading = false, inputPrompt = result.prompt) }
            }
        }
    }

    fun onBack(): Boolean {
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
                loading = false, frame = frame, message = null, inputPrompt = null,
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
