package com.point.desktop

import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.ObjectKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Working(val title: String, val stage: String?, val startedAt: Long)

class DesktopState(
    private val registry: CapabilityRegistry,
    private val resolver: Resolver,
    private val clipboard: TextClipboard,
    private val outbox: Outbox? = null,
    private val persistPhoneCaps: (List<com.point.core.flow.PcRemoteAction>) -> Unit = {},

    private val journalStore: JournalStore? = null,

    private val clock: Clock = Clock { System.currentTimeMillis() },

    private val reopenPath: (String) -> InboxItem? = { null },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _items = MutableStateFlow<List<InboxItem>>(emptyList())
    val items: StateFlow<List<InboxItem>> = _items.asStateFlow()

    private val _journal = MutableStateFlow(runCatching { journalStore?.load() }.getOrNull().orEmpty())

    val journal: StateFlow<List<JournalEntry>> = _journal.asStateFlow()

    private val _working = MutableStateFlow<Working?>(null)

    val working: StateFlow<Working?> get() = _working.asStateFlow()

    private var work: kotlinx.coroutines.Job? = null

    fun cancelWork() {
        work?.cancel()
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _clipboardText = MutableStateFlow<String?>(null)

    val clipboardText: StateFlow<String?> = _clipboardText.asStateFlow()

    private val _phoneCaps = MutableStateFlow<List<com.point.core.flow.PcRemoteAction>>(emptyList())

    val phoneCaps = _phoneCaps.asStateFlow()

    private val _lastContact = MutableStateFlow<Long?>(null)
    val lastContact: StateFlow<Long?> = _lastContact.asStateFlow()

    fun heard() {
        _lastContact.value = System.currentTimeMillis()
    }

    fun bubblesFor(item: InboxItem): List<Bubble> {

        // Тот же вывод уместного смысла, что и на телефоне: из знания объекта (ADR-0001 §14).
        val graph = com.point.core.flow.GraphState(item.obj)
        return registry.bubblesFor(graph.copy(intent = com.point.core.flow.leadingIntent(graph)))
    }

    fun say(text: String) { _message.value = text }

    fun runRemoteAction(id: String, item: InboxItem) {
        scope.launch { perform(id, item) }
    }

    /**
     * Бюджет здесь — про синхронный ответ телефону, не про работу (телефон ждёт
     * ответа считанные секунды). Долгое действие не обрывается: оно доводится в
     * scope компьютера, а готовый результат уезжает существующей очередью
     * ПК→телефон вместе со знанием (Product Constitution PC2/PC4). Телефону сразу
     * уходит честное «ещё работаю» вместо ложного «отправлено».
     */
    fun runRemoteActionNow(id: String, item: InboxItem, budgetMs: Long = 10_000): ActionResult? {
        val work = kotlinx.coroutines.CompletableDeferred<ActionResult?>()
        scope.launch { work.complete(runCatching { perform(id, item) }.getOrNull()) }
        val quick = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(budgetMs) { work.await() }
        }
        if (quick != null) return quick

        scope.launch {
            val late = runCatching { work.await() }.getOrNull()
            if (late is ActionResult.Success) {
                runCatching {
                    outbox?.add(
                        com.point.core.model.PointObject(
                            id = java.util.UUID.randomUUID().toString(),
                            mime = late.result.mime,
                            uri = late.result.uri,
                            state = com.point.core.model.ObjectState(late.result.type),
                            metadata = late.result.metadata,
                        ),
                    )
                }
            }
        }
        return ActionResult.Done(STILL_WORKING)
    }

    companion object {
        const val STILL_WORKING = "Компьютер ещё работает — готовое появится в списке «с компьютера»"
    }

    private suspend fun perform(id: String, item: InboxItem, stationTitle: String? = null): ActionResult? {
        val title = stationTitle ?: titleOf(id, item)
        _message.value = null
        _working.value = Working(title, stage = null, startedAt = clock.now())
        val result = try {
            runCatching {

                kotlinx.coroutines.withContext(
                    com.point.core.flow.ActionProgress { stage ->
                        _working.value = _working.value?.copy(stage = stage)
                    } +

                        com.point.core.flow.RequestOrigin(here = stationTitle != null),
                ) {
                    resolver.realizerFor(com.point.core.model.CapabilityId(id), item.obj.state)
                        .perform(item.obj, null)
                }
            }.getOrElse { e ->

                if (e is kotlinx.coroutines.CancellationException) throw e
                ActionResult.Failure("Действие не выполнилось — попробуйте ещё раз", recoverable = true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _working.value = null
            _message.value = "Отменено"
            note(item, id, title, ActionResult.Failure("отменено", recoverable = true))
            throw e
        } finally {
            _working.value = null
        }

        if (result is ActionResult.Success) {
            val born = runCatching { reopenPath(result.result.uri.value) }.getOrNull()
            if (born != null) {
                val named = result.result.metadata["name"]
                val item2 = if (named.isNullOrBlank()) {
                    born
                } else {
                    born.copy(obj = born.obj.copy(metadata = born.obj.metadata + ("name" to named)))
                }
                onReceived(item2, ObjectSource.LOCAL)
            }
        }
        _message.value = when (result) {
            is ActionResult.Done -> result.message
            is ActionResult.Failure -> result.reason
            is ActionResult.Success -> result.result.metadata["name"] ?: "Готово"
            else -> _message.value
        }

        note(item, id, if (stationTitle != null) title else "$title · с телефона", result)
        return result
    }

    fun setPhoneCaps(caps: List<com.point.core.flow.PcRemoteAction>) {
        _phoneCaps.value = caps
        runCatching { persistPhoneCaps(caps) }
    }

    fun phoneActionsFor(item: InboxItem): List<com.point.core.flow.PcRemoteAction> {
        val mine = registry.all().map { it.id.value }.toSet()
        val has = item.obj.state.features.map { it.name }.toSet()
        return _phoneCaps.value.filter { action ->
            action.unavailable == null &&

                (action.kinds.isEmpty() || item.obj.state.kind.name in action.kinds) &&

                (action.features.isEmpty() || action.features.any { it in has }) &&

                action.id !in mine
        }
    }

    fun sendToPhone(item: InboxItem, action: com.point.core.flow.PcRemoteAction) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                outbox?.add(item.obj.copy(metadata = item.obj.metadata + ("pc.action" to action.id)))
            }.onSuccess {

                val silent = com.point.core.flow.linkStateOf(_lastContact.value, clock.now()) !is
                    com.point.core.flow.LinkState.Live
                _message.value = if (silent) {
                    "${action.label} — ждёт телефона: он сейчас не на связи"
                } else {
                    "${action.label} — ждёт телефона"
                }
                note(item, action.id, "${action.label} · ждёт телефона", ActionResult.Done("ждёт телефона"))
            }.onFailure {
                _message.value = "Не удалось положить в очередь"
                note(
                    item, action.id, "${action.label} · на телефон",
                    ActionResult.Failure("Не удалось отправить — проверьте, что на диске есть место", recoverable = true),
                )
            }
        }
    }

    fun onReceived(item: InboxItem, source: ObjectSource = ObjectSource.LOCAL) {
        _items.update { listOf(item) + it }
        rememberArrival(item, source)

        if (item.obj.state.kind == ObjectKind.TEXT) {
            val text = runCatching { File(item.obj.uri.value).readText() }.getOrNull()
            if (text != null) {
                runCatching { clipboard.copy(text) }
                _clipboardText.value = text
                _message.value = null
            }
        } else {
            _message.value = "Получено: ${item.obj.metadata["name"]}"
        }
    }

    fun onBubble(item: InboxItem, bubble: Bubble) {
        work = scope.launch(Dispatchers.IO) { perform(bubble.capabilityId.value, item, bubble.title) }
    }

    fun openAgain(entry: JournalEntry) {
        val live = _items.value.firstOrNull { it.obj.uri.value == entry.path }
        if (live != null) return
        val item = runCatching { reopenPath(entry.path) }.getOrNull()
        if (item == null) {

            _message.value = "Файла больше нет: ${entry.name}"
            return
        }

        _items.update { listOf(item) + it }
    }

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

    private fun note(item: InboxItem, capabilityId: String, title: String, result: ActionResult) {
        updateJournal {
            recordStep(it, item.obj.uri.value, stepOf(capabilityId, title, clock.now(), result))
        }
    }

    private fun titleOf(id: String, item: InboxItem): String =
        runCatching { registry.byId(com.point.core.model.CapabilityId(id)).label(item.obj.state) }
            .getOrDefault(id)

    @Synchronized
    private fun updateJournal(transform: (List<JournalEntry>) -> List<JournalEntry>) {
        val next = transform(_journal.value)
        _journal.value = next
        runCatching { journalStore?.save(next) }
    }

    fun copyClipboardAgain() {
        _clipboardText.value?.let { runCatching { clipboard.copy(it) } }
    }

    fun forget(entry: JournalEntry) {
        val file = java.io.File(entry.path)
        if (entry.source != ObjectSource.DROPPED) runCatching { file.delete() }
        _items.update { list -> list.filterNot { it.obj.uri.value == entry.path } }
        updateJournal { it.filterNot { e -> e.path == entry.path } }
    }

    fun forgetEverything(wipeFiles: () -> Unit) {
        runCatching { wipeFiles() }
        _items.value = emptyList()
        _clipboardText.value = null
        updateJournal { emptyList() }
    }

    fun clearClipboard() {
        _clipboardText.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }
}
