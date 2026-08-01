package com.point.desktop

import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.ObjectKind
import java.io.File
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A pending pair request the window must answer (allow/deny). */
class PairRequest(val deviceName: String, private val decide: (Boolean) -> Unit) {
    fun allow() = decide(true)
    fun deny() = decide(false)
}

/**
 * The desktop's state holder (the VM analogue, hand-wired): received objects, a
 * transient message, the connection card, and the pending pair dialog.
 */
class DesktopState(
    private val registry: CapabilityRegistry,
    private val resolver: Resolver,
    private val clipboard: TextClipboard,
    private val outbox: Outbox? = null,
    private val persistPhoneCaps: (List<com.point.core.flow.PcRemoteAction>) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _items = MutableStateFlow<List<InboxItem>>(emptyList())
    val items: StateFlow<List<InboxItem>> = _items.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _clipboardText = MutableStateFlow<String?>(null)
    /** The latest text that crossed into the PC clipboard — shown as a live «Буфер» card. */
    val clipboardText: StateFlow<String?> = _clipboardText.asStateFlow()

    private val _phoneCaps = MutableStateFlow<List<com.point.core.flow.PcRemoteAction>>(emptyList())
    /** The paired phone's advertised actions (#161 v2) — cards grow «… · телефон» buttons. */
    val phoneCaps = _phoneCaps.asStateFlow()

    private val _pairRequest = MutableStateFlow<PairRequest?>(null)
    val pairRequest: StateFlow<PairRequest?> = _pairRequest.asStateFlow()

    fun bubblesFor(item: InboxItem): List<Bubble> = registry.bubblesFor(item.obj.state)

    /** #80: the phone asked to run one of the advertised actions on the received object. */
    fun runRemoteAction(id: String, item: InboxItem) {
        scope.launch {
            val result = runCatching {
                resolver.realizerFor(com.point.core.model.CapabilityId(id)).perform(item.obj, null)
            }.getOrNull()
            _message.value = when (result) {
                is com.point.core.model.ActionResult.Done -> result.message
                else -> _message.value
            }
        }
    }

    fun setPhoneCaps(caps: List<com.point.core.flow.PcRemoteAction>) {
        _phoneCaps.value = caps
        runCatching { persistPhoneCaps(caps) }
    }

    /** Phone actions that make sense for this item — empty kinds means any kind.
     *  #316: объявленное телефоном как недоступное кнопкой на ПК не становится — симметрия
     *  того же признака, каким компьютер объясняет своё «не сейчас» телефону. */
    fun phoneActionsFor(item: InboxItem): List<com.point.core.flow.PcRemoteAction> =
        _phoneCaps.value.filter {
            it.unavailable == null && (it.kinds.isEmpty() || item.obj.state.kind.name in it.kinds)
        }

    /** «<действие> · телефон» (#161 v2): queue the object with the intent riding its metadata. */
    fun sendToPhone(item: InboxItem, action: com.point.core.flow.PcRemoteAction) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                outbox?.add(item.obj.copy(metadata = item.obj.metadata + ("pc.action" to action.id)))
            }.onSuccess {
                _message.value = "${action.label} — заберите на телефоне (плашка на главном экране)"
            }.onFailure {
                _message.value = "Не удалось положить в очередь"
            }
        }
    }

    fun onReceived(item: InboxItem) {
        _items.update { listOf(item) + it }
        // Owner's decision: text from the phone lands straight in the clipboard —
        // arrived → Ctrl+V, the shortest possible path.
        if (item.obj.state.kind == ObjectKind.TEXT) {
            val text = runCatching { File(item.obj.uri.value).readText() }.getOrNull()
            if (text != null) {
                runCatching { clipboard.copy(text) }
                _clipboardText.value = text // a live «Буфер» card, not just a transient line
                _message.value = null
            }
        } else {
            _message.value = "Получено: ${item.obj.metadata["name"]}"
        }
    }

    fun onBubble(item: InboxItem, bubble: Bubble) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching { resolver.realizerFor(bubble.capabilityId).perform(item.obj, null) }
                .getOrElse { ActionResult.Failure(it.message ?: "Ошибка", recoverable = true) }
            _message.value = when (result) {
                is ActionResult.Done -> result.message
                is ActionResult.Failure -> result.reason
                else -> null
            }
        }
    }

    /** Called from the HTTP thread; suspends it until the window answers or 60s pass. */
    fun askPair(deviceName: String): Boolean {
        val decision = java.util.concurrent.CompletableFuture<Boolean>()
        _pairRequest.value = PairRequest(deviceName) { allowed ->
            _pairRequest.value = null
            decision.complete(allowed)
        }
        return runCatching {
            decision.get(60, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrDefault(false).also { _pairRequest.value = null }
    }

    /** Re-copy the current clipboard text (after copying something else on the PC). */
    fun copyClipboardAgain() {
        _clipboardText.value?.let { runCatching { clipboard.copy(it) } }
    }

    fun clearClipboard() {
        _clipboardText.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }
}

/** All site-local IPv4 addresses — the multi-homed PC shows every candidate. */
fun siteLocalAddresses(): List<String> =
    runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { it.isSiteLocalAddress && it.address.size == 4 }
            .map { it.hostAddress }
            .toList()
    }.getOrDefault(emptyList())
