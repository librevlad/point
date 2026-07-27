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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _items = MutableStateFlow<List<InboxItem>>(emptyList())
    val items: StateFlow<List<InboxItem>> = _items.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

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

    fun onReceived(item: InboxItem) {
        _items.update { listOf(item) + it }
        // Owner's decision: text from the phone lands straight in the clipboard —
        // arrived → Ctrl+V, the shortest possible path.
        if (item.obj.state.kind == ObjectKind.TEXT) {
            runCatching { clipboard.copy(File(item.obj.uri.value).readText()) }
                .onSuccess { _message.value = "Текст уже в буфере — Ctrl+V" }
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
