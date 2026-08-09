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

    private val consent: com.point.core.flow.PrivacyConsent? = null,
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

    /** Факт забирается в буфер одним кликом — на ПК буфер и есть главная валюта (P4). */
    fun copyFact(value: String) {
        runCatching { clipboard.copy(value) }
        _message.value = "В буфере: $value"
    }

    /** Человеческое имя вопроса знания; вопросы без имени на экран не выходят (P2). */
    fun questionName(id: com.point.core.model.CapabilityId, state: com.point.core.model.ObjectState): String? =
        runCatching { registry.byId(id).label(state) }.getOrNull() ?: PHONE_QUESTIONS[id.value]

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
                }.onFailure {
                    val why = "Результат не лёг в очередь для телефона — проверьте, что на диске есть место"
                    _message.value = why
                    note(item, id, titleOf(id, item) + " · результат в очередь", ActionResult.Failure(why, recoverable = true))
                }
            }
        }
        return ActionResult.Done(STILL_WORKING)
    }

    companion object {
        const val STILL_WORKING = "Компьютер ещё работает — готовое появится в списке «с компьютера»"

        /** Имена вопросов, заданных другой поверхностью: её capability здесь не зарегистрированы. */
        private val PHONE_QUESTIONS = mapOf(
            "image-text" to "Текст на снимке",
            "qr-content" to "QR-код",
            "understand" to "Понимание",
            "entities" to "Контакты и номера",
        )
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
                if (e is NoWayHere) {
                    ActionResult.Failure(e.why, recoverable = false)
                } else {
                    ActionResult.Failure("Действие не выполнилось — попробуйте ещё раз", recoverable = true)
                }
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

        // Знание из шага ложится в сам объект — тем же mergeKnowledge, что и на телефоне
        // (Конституция §4: обогащение не создаёт версию объекта; аудит 2026-08-09, блок 1.1).
        val findings = (result as? ActionResult.Done)?.findings
        if (findings != null && !findings.isEmpty) landFindings(item, findings)
        _message.value = when (result) {
            is ActionResult.Done -> result.message
            is ActionResult.Failure -> result.reason
            is ActionResult.Success -> result.result.metadata["name"] ?: "Готово"
            else -> _message.value
        }

        note(item, id, if (stationTitle != null) title else "$title · с телефона", result)
        return result
    }

    private fun landFindings(item: InboxItem, findings: com.point.core.model.Findings) {
        val current = _items.value.firstOrNull { it.obj.id == item.obj.id } ?: item
        val newState = findings.features.fold(current.obj.state) { state, feature -> state.with(feature) }
        val newMeta = com.point.core.flow.mergeKnowledge(
            current.obj.metadata,
            findings.metadata,
            com.point.core.flow.REFRESHABLE_KNOWLEDGE,
        )
        if (newState == current.obj.state && newMeta == current.obj.metadata) return
        val updated = current.copy(obj = current.obj.copy(state = newState, metadata = newMeta))
        _items.update { list -> list.map { if (it.obj.id == item.obj.id) updated else it } }

        // findings.objects (узлы-сущности) появятся на экране ПК в фазе B редизайна.
        updateJournal { recordKnowledge(it, updated.obj.uri.value, newMeta) }
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
        autoInvestigate(item)
    }

    /**
     * Прибывший объект сразу продолжает цикл понимания (Конституция §9, §11): дешёвое
     * локальное исследование — без клика и без индикации операции. Облачные исполнители
     * сюда не попадают: автоматизм не пересекает границу устройств.
     */
    private fun autoInvestigate(item: InboxItem) {
        if (item.obj.state.kind != ObjectKind.TEXT) return
        val question = com.point.core.model.CapabilityId("pc-entities")
        val asked = com.point.core.flow.investigationStateOf(item.obj.metadata, question)
        if (asked != com.point.core.flow.InvestigationState.NOT_INVESTIGATED) return
        scope.launch {
            val realizer = runCatching { resolver.realizerFor(question, item.obj.state) }.getOrNull()
                ?: return@launch
            if (realizer.meta.kind == com.point.core.flow.RealizerKind.CLOUD) return@launch
            val result = runCatching { realizer.perform(item.obj, null) }.getOrNull()
            val findings = (result as? ActionResult.Done)?.findings ?: return@launch
            if (!findings.isEmpty) landFindings(item, findings)
        }
    }

    /** Вопрос согласия в момент выбора: объект уходит с устройств только после «да» (P11). */
    data class CloudAsk(
        val item: InboxItem,
        val bubble: Bubble,
        val scope: com.point.core.flow.CloudScope,
        val title: String,
        val destination: String,
        val confirm: String,
    )

    private val _cloudAsk = MutableStateFlow<CloudAsk?>(null)
    val cloudAsk: StateFlow<CloudAsk?> = _cloudAsk.asStateFlow()

    fun onBubble(item: InboxItem, bubble: Bubble) {
        work = scope.launch(Dispatchers.IO) {
            val guard = consent
            if (guard != null && resolver.leavesDevice(bubble.capabilityId)) {
                val needed = com.point.core.flow.cloudScopeOf(bubble.capabilityId)
                val ok = runCatching { guard.allowed(needed) }.getOrDefault(false)
                if (!ok) {
                    _cloudAsk.value = CloudAsk(
                        item, bubble, needed,
                        title = com.point.core.flow.cloudAskTitle(needed),
                        destination = com.point.core.flow.cloudDestination(bubble.capabilityId),
                        confirm = com.point.core.flow.cloudAskConfirm(needed),
                    )
                    return@launch
                }
            }
            perform(bubble.capabilityId.value, item, bubble.title)
        }
    }

    fun approveCloud() {
        val ask = _cloudAsk.value ?: return
        _cloudAsk.value = null
        work = scope.launch(Dispatchers.IO) {
            runCatching { consent?.allow(ask.scope) }
            perform(ask.bubble.capabilityId.value, ask.item, ask.bubble.title)
        }
    }

    fun declineCloud() {
        _cloudAsk.value = null

        // Отказ не наказывает: действие остаётся доступным на потом (P11).
        _message.value = "Ничего не отправлено — объект остался на компьютере. Действие доступно, если передумаете"
    }

    /**
     * Клик по истории всегда отвечает: живым объектом ленты, переоткрытым файлом
     * или честным «файла больше нет». Молчание выглядело мёртвой кнопкой
     * (живой прогон 2026-08-09) — выбор возвращённого делает вызвавший экран.
     */
    fun openAgain(entry: JournalEntry): InboxItem? {
        val live = _items.value.firstOrNull { it.obj.uri.value == entry.path }
        if (live != null) return live
        val reopened = runCatching { reopenPath(entry.path) }.getOrNull()
        if (reopened == null) {

            _message.value = "Файла больше нет: ${entry.name}"
            return null
        }

        // Переоткрытый файл — тот же объект: журнальное знание и имя возвращаются к нему (PC2/PC5).
        val item = reopened.copy(obj = reopened.obj.copy(metadata = reopened.obj.metadata + entry.meta))
        _items.update { listOf(item) + it }
        return item
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
                    meta = item.obj.metadata,
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
