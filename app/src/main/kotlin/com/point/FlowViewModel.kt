package com.point

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Enrichment
import com.point.core.flow.ObjectStore
import com.point.core.flow.Resolver
import com.point.core.model.Bubble
import com.point.core.model.ActionResult
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
 */
@HiltViewModel
class FlowViewModel @Inject constructor(
    private val store: ObjectStore,
    private val registry: CapabilityRegistry,
    private val resolver: Resolver,
    private val enrichment: Enrichment,
) : ViewModel() {

    private val stack = ArrayDeque<FlowFrame>()

    /** The bubble whose capability returned NeedsInput and is awaiting a reply. */
    private var pendingBubble: Bubble? = null

    private val _ui = MutableStateFlow(FlowUiState())
    val ui: StateFlow<FlowUiState> = _ui.asStateFlow()

    /** Entry point: a Share source (Uri stringified) + its MIME. */
    fun onShared(sourceUri: String, mime: String) {
        _ui.update { it.copy(loading = true, message = null, inputPrompt = null) }
        viewModelScope.launch {
            runCatching {
                store.clear() // drop any prior/leaked flow before copy-in
                store.ingest(sourceUri, mime)
            }.onSuccess { pushFrame(it) }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, message = "Не удалось открыть: ${e.message}") }
                }
        }
    }

    fun onBubble(bubble: Bubble) {
        val top = stack.lastOrNull()?.obj ?: return
        _ui.update { it.copy(loading = true, message = null, inputPrompt = null) }
        dispatch(bubble) { resolver.realizerFor(bubble.capabilityId).perform(top, null) }
    }

    /** User answered a NeedsInput prompt. */
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

    private fun dispatch(bubble: Bubble, action: suspend () -> ActionResult) {
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { result -> handleResult(result, bubble) }
                .onFailure { e -> _ui.update { it.copy(loading = false, message = e.message ?: "Ошибка") } }
        }
    }

    private suspend fun handleResult(result: ActionResult, bubble: Bubble) {
        when (result) {
            is ActionResult.Success -> pushFrame(store.put(result.result))
            is ActionResult.Done -> _ui.update { it.copy(loading = false, message = result.message) }
            is ActionResult.Failure -> _ui.update { it.copy(loading = false, message = result.reason) }
            is ActionResult.NeedsInput -> {
                pendingBubble = bubble
                _ui.update { it.copy(loading = false, inputPrompt = result.prompt) }
            }
        }
    }

    /** @return true if a frame was popped; false if already at the first frame. */
    fun onBack(): Boolean {
        if (_ui.value.inputPrompt != null) {
            cancelInput()
            return true
        }
        if (stack.size <= 1) return false
        stack.removeLast()
        _ui.update { it.copy(frame = stack.last(), message = null) }
        return true
    }

    fun hasFlow(): Boolean = stack.isNotEmpty()

    /** Abandon the flow and wipe scratch (also used on Activity finish). */
    fun endFlow() {
        stack.clear()
        pendingBubble = null
        _ui.update { FlowUiState() }
        viewModelScope.launch { runCatching { store.clear() } }
    }

    private fun pushFrame(obj: PointObject) {
        val frame = FlowFrame(obj, registry.bubblesFor(obj.state))
        stack.addLast(frame)
        _ui.update { it.copy(loading = false, frame = frame, message = null, inputPrompt = null) }
        enrichInBackground(obj)
    }

    /**
     * Progressive disclosure: after the first paint, peek the object's content
     * off the main path and, if new features surface, refine the top frame's
     * bubbles in place. Only applies while that same object is still on top.
     */
    private fun enrichInBackground(obj: PointObject) {
        viewModelScope.launch {
            val extra = runCatching { enrichment.enrich(obj) }.getOrDefault(emptySet())
            if (extra.isEmpty()) return@launch

            val topIndex = stack.lastIndex
            val top = stack.getOrNull(topIndex) ?: return@launch
            if (top.obj.id != obj.id) return@launch // user already moved on

            val enrichedState = extra.fold(top.obj.state) { state, feature -> state.with(feature) }
            if (enrichedState == top.obj.state) return@launch

            val enrichedObj = top.obj.copy(state = enrichedState)
            val refreshed = FlowFrame(enrichedObj, registry.bubblesFor(enrichedState))
            stack[topIndex] = refreshed
            _ui.update { if (it.frame === top) it.copy(frame = refreshed) else it }
        }
    }
}
