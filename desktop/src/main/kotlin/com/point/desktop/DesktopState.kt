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
 *
 * Ещё он ведёт журнал (#407): что приезжало, откуда и что с этим делали. Память живёт за швом
 * [JournalStore] — экран читает её тем же способом, каким читает всё остальное состояние.
 */
class DesktopState(
    private val registry: CapabilityRegistry,
    private val resolver: Resolver,
    private val clipboard: TextClipboard,
    private val outbox: Outbox? = null,
    private val persistPhoneCaps: (List<com.point.core.flow.PcRemoteAction>) -> Unit = {},
    /** Память компьютера о пути объектов (#407). `null` — journal-less state (тесты экрана). */
    private val journalStore: JournalStore? = null,
    /** Часы за швом: время станции в тесте обязано быть предсказуемым. */
    private val clock: Clock = Clock { System.currentTimeMillis() },
    /**
     * Открыть заново файл, о котором помнит журнал. Возвращает `null`, если файла больше нет —
     * тогда человеку об этом говорят, а не открывают пустоту. В `Main` это `Inbox::addFile`.
     */
    private val reopenPath: (String) -> InboxItem? = { null },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _items = MutableStateFlow<List<InboxItem>>(emptyList())
    val items: StateFlow<List<InboxItem>> = _items.asStateFlow()

    private val _journal = MutableStateFlow(runCatching { journalStore?.load() }.getOrNull().orEmpty())
    /** Путь объектов, переживший перезапуск (#407): самое свежее первым. */
    val journal: StateFlow<List<JournalEntry>> = _journal.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _clipboardText = MutableStateFlow<String?>(null)
    /** The latest text that crossed into the PC clipboard — shown as a live «Буфер» card. */
    val clipboardText: StateFlow<String?> = _clipboardText.asStateFlow()

    private val _phoneCaps = MutableStateFlow<List<com.point.core.flow.PcRemoteAction>>(emptyList())
    /** The paired phone's advertised actions (#161 v2) — cards grow «… · телефон» buttons. */
    val phoneCaps = _phoneCaps.asStateFlow()

    // Когда и каким путём телефон приходил в последний раз (#412). Без этого экран молчал, и
    // человек не мог отличить «связи нет» от «ничего не произошло».
    private val _lastContact = MutableStateFlow<Pair<Long, com.point.core.flow.LinkPath>?>(null)
    val lastContact: StateFlow<Pair<Long, com.point.core.flow.LinkPath>?> = _lastContact.asStateFlow()

    /** Телефон дал о себе знать. Путь запоминается: он объясняет человеку скорость. */
    fun heard(path: com.point.core.flow.LinkPath) {
        _lastContact.value = System.currentTimeMillis() to path
    }

    private val _pairRequest = MutableStateFlow<PairRequest?>(null)
    val pairRequest: StateFlow<PairRequest?> = _pairRequest.asStateFlow()

    fun bubblesFor(item: InboxItem): List<Bubble> = registry.bubblesFor(item.obj.state)

    /** Сказать человеку словами. Пустой буфер и прочие «ничего не вышло» обязаны звучать. */
    fun say(text: String) { _message.value = text }

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
            // Тап был на телефоне, а работа шла здесь — иначе, вернувшись к компьютеру, человек
            // не поймёт, откуда взялся результат. Поэтому в пути станция названа с автором (#407).
            result?.let { note(item, id, "${titleOf(id, item)} · с телефона", it) }
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
                note(item, action.id, "${action.label} · на телефон", ActionResult.Done("отправлено на телефон"))
            }.onFailure {
                _message.value = "Не удалось положить в очередь"
                note(
                    item, action.id, "${action.label} · на телефон",
                    ActionResult.Failure(it.message ?: "не удалось положить в очередь", recoverable = true),
                )
            }
        }
    }

    fun onReceived(item: InboxItem, source: ObjectSource = ObjectSource.LOCAL) {
        _items.update { listOf(item) + it }
        rememberArrival(item, source)
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
            note(item, bubble.capabilityId.value, bubble.title, result)
        }
    }

    /**
     * Открыть заново то, что помнит журнал (#407) — по явному тапу человека.
     *
     * Только достаёт объект обратно на конвейер: ни одно действие не повторяется само. Point не
     * строит автоматических цепочек, и «открыть заново» не становится исключением.
     */
    fun openAgain(entry: JournalEntry) {
        val live = _items.value.firstOrNull { it.obj.uri.value == entry.path }
        if (live != null) return
        val item = runCatching { reopenPath(entry.path) }.getOrNull()
        if (item == null) {
            // Файла может не быть: его унесли, переименовали, вычистили. Честный отказ вместо
            // пустого экрана — то же правило, что и везде.
            _message.value = "Файла больше нет: ${entry.name}"
            return
        }
        // Приезд заново не записывается: объект тот же, и переписанное время приезда солгало бы
        // о том, когда он на самом деле появился на компьютере.
        _items.update { listOf(item) + it }
    }

    /** Путь этого объекта, если компьютер его помнит. Ключ — файл: id объекта переживает не всё. */
    fun pathOf(item: InboxItem): JournalEntry? =
        _journal.value.firstOrNull { it.path == item.obj.uri.value }

    private fun rememberArrival(item: InboxItem, source: ObjectSource) {
        val file = java.io.File(item.obj.uri.value)
        updateJournal {
            recordArrival(
                it,
                JournalEntry(
                    path = item.obj.uri.value,
                    name = item.obj.metadata["name"] ?: file.name,
                    kind = item.obj.state.kind.name,
                    mime = item.obj.mime,
                    source = source,
                    at = item.receivedAt,
                ),
            )
        }
    }

    /** Станция пути: что применили и чем кончилось. */
    private fun note(item: InboxItem, capabilityId: String, title: String, result: ActionResult) {
        updateJournal {
            recordStep(it, item.obj.uri.value, stepOf(capabilityId, title, clock.now(), result))
        }
    }

    /** Имя возможности из реестра; незнакомый id остаётся собой — выдумывать название нечем. */
    private fun titleOf(id: String, item: InboxItem): String =
        runCatching { registry.byId(com.point.core.model.CapabilityId(id)).label(item.obj.state) }
            .getOrDefault(id)

    @Synchronized
    private fun updateJournal(transform: (List<JournalEntry>) -> List<JournalEntry>) {
        val next = transform(_journal.value)
        _journal.value = next
        runCatching { journalStore?.save(next) }
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
