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

data class Working(
    val title: String,
    val stage: String?,
    val startedAt: Long,

    /** Чей это шаг: из списка видно, куда вернуться к работе. */
    val objectId: String? = null,

    /** Уходит ли работа наружу: от этого зависит, что честно сказать про ожидание (#901). */
    val network: Boolean = false,
)

class DesktopState(
    private val registry: CapabilityRegistry,
    private val resolver: Resolver,
    private val clipboard: TextClipboard,
    private val outbox: Outbox? = null,
    private val persistPhoneCaps: (List<com.point.core.flow.PcRemoteAction>) -> Unit = {},

    private val journalStore: JournalStore? = null,

    private val clock: Clock = Clock { System.currentTimeMillis() },

    private val reopenPath: (String) -> InboxItem? = { null },

    internal val consent: com.point.core.flow.PrivacyConsent? = null,

    /** Выбранный человеком режим отправки: спрашивается у настроек, а не помнится копией. */
    private val privacyLevel: () -> com.point.core.flow.PrivacyLevel =
        { com.point.core.flow.PrivacyLevel.DEFAULT },

    /** Прибытие объявляется наружу (peek-плашка): и с телефона, и готовое здесь. */
    private val announce: (InboxItem, ObjectSource) -> Unit = { _, _ -> },

    /**
     * Исполняет ли телефон просьбы компьютера (#785, включено в #817).
     *
     * Причина, по которой это было выключено, оказалась неверной. Она говорила: просьба
     * поедет почтой и будет стёрта чисткой ящика. На деле просьба почтой не едет — она
     * ложится в папку `outbox` на диске самого компьютера, а телефон сам спрашивает «что у
     * тебя для меня». Спрашивает он, значит стирать некому.
     *
     * Телефон при этом давно умеет выполнять названное действие: объект приходит с
     * `pc.action`, и `FlowViewModel` делает его сразу после приёма. Работа была сделана и
     * просто выключена флагом.
     *
     * Чтобы просьба не ждала случайного открытия Point, компьютер просит сервер постучать
     * в телефон. Стук несёт одно слово «зайди»; чего именно от него хотят, телефон
     * спрашивает у компьютера напрямую.
     */
    internal val phoneRunsRequests: Boolean = true,

    /**
     * Постучать в телефон: «зайди, для тебя что-то есть» (#817).
     *
     * Молчание не ломает работу: без ключа, без разрешения на уведомления и без сети
     * просьба всё равно дождётся — просто человек узнает о ней, открыв Point сам.
     */
    private val knockPhone: suspend () -> Unit = {},
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
        _lastContact.value = clock.now()
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
        _working.value = Working(
            title,
            stage = null,
            startedAt = clock.now(),
            objectId = item.obj.id,
            network = runCatching { resolver.leavesDevice(com.point.core.model.CapabilityId(id)) }.getOrDefault(false),
        )
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

    fun setPhoneCaps(caps: List<com.point.core.flow.PcRemoteAction>, persist: Boolean = true) {

        // Загрузка кэша с диска не пишет его обратно: иначе метка времени файла
        // выглядит свежей, хотя телефон мог не объявляться неделю (#624).
        _phoneCaps.value = caps
        if (persist) runCatching { persistPhoneCaps(caps) }
    }

    fun phoneActionsFor(item: InboxItem): List<com.point.core.flow.PcRemoteAction> {
        val mine = registry.all().map { it.id.value }.toSet()
        val has = item.obj.state.features.map { it.name }.toSet()
        return _phoneCaps.value.filter { action ->

            (action.kinds.isEmpty() || item.obj.state.kind.name in action.kinds) &&

                (action.features.isEmpty() || action.features.any { it in has }) &&

                action.id !in mine
        }
    }

    /** Одно действие единого списка: здешнее или телефонное, порядок — по пользе (P10). */
    data class ActionChoice(
        val title: String,
        val onPhone: Boolean,
        val unavailable: String? = null,
        val bubble: Bubble? = null,
        val remote: com.point.core.flow.PcRemoteAction? = null,

        /**
         * Тот же значок, что у этого действия на телефоне. Одно действие с двумя разными
         * лицами на двух экранах — два продукта, а не один.
         */
        val icon: String = bubble?.icon.orEmpty().ifBlank { "ai" },
    )

    /**
     * Единый список действий: свои и телефонные ранжируются вместе — по смыслу и пользе,
     * а не по тому, чей реестр их родил (аудит, блок 2.3). Недоступное телефонное видно
     * с причиной, а не скрыто (PC5: возможности дорастают на глазах).
     */
    fun actionsFor(item: InboxItem): List<ActionChoice> {
        val graph = com.point.core.flow.GraphState(item.obj)
        val intent = com.point.core.flow.leadingIntent(graph)
        val here = bubblesFor(item).map { bubble ->
            val capability = runCatching { registry.byId(bubble.capabilityId) }.getOrNull()
            Triple(
                ActionChoice(bubble.title, onPhone = false, bubble = bubble, icon = bubble.icon),
                capability?.meta?.priority ?: com.point.core.flow.PC_CAP_DEFAULT_PRIORITY,
                intent != null && capability != null && intent in capability.intents(item.obj.state),
            )
        }
        val phone = phoneActionsFor(item).map { action ->
            Triple(
                ActionChoice(
                    action.label,
                    onPhone = true,

                    // Причина видна до нажатия, а не после (#785): человек не должен
                    // узнавать о границе связки, ткнув в действие и подождав напрасно.
                    unavailable = when {
                        !phoneRunsRequests -> PHONE_DOES_NOT_RUN_REQUESTS
                        else -> action.unavailable?.ifBlank { "телефон сейчас не может это сделать" }
                    },
                    remote = action,
                    icon = "phone",
                ),
                action.priority,
                false,
            )
        }
        return (here + phone)
            .sortedWith(
                compareBy(
                    { (_, _, servesIntent) -> if (servesIntent) 0 else 1 },
                    { (choice, _, _) -> if (choice.unavailable == null) 0 else 1 },
                    { (_, priority, _) -> priority },
                    { (choice, _, _) -> choice.title },
                ),
            )
            .map { it.first }
    }

    /**
     * Вопрос до дела: телефон, который ответит через час, не может быть выбран за спиной
     * (срез 5 контракта связки, #611). Живой телефон выбирается молча — как любой свой
     * исполнитель; молчащий становится выбором человека: подождать или отказаться.
     */
    data class PhoneAsk(
        val item: InboxItem,
        val action: com.point.core.flow.PcRemoteAction,
        val title: String,
        val what: String,
    )

    /** Ожидание файла по ссылке — на компьютере оно тоже есть (#727). */
    private val _receiving = MutableStateFlow<ReceiveOnPc.Waiting?>(null)
    val receiving: StateFlow<ReceiveOnPc.Waiting?> = _receiving.asStateFlow()

    fun showReceiving(waiting: ReceiveOnPc.Waiting?) { _receiving.value = waiting }

    private val _phoneAsk = MutableStateFlow<PhoneAsk?>(null)
    val phoneAsk: StateFlow<PhoneAsk?> = _phoneAsk.asStateFlow()

    fun sendToPhone(item: InboxItem, action: com.point.core.flow.PcRemoteAction) {

        // Страховка на случай вызова мимо списка действий (#785).
        if (!phoneRunsRequests) {
            _message.value = PHONE_DOES_NOT_RUN_REQUESTS
            return
        }
        val link = com.point.core.flow.linkStateOf(_lastContact.value, clock.now())
        if (link !is com.point.core.flow.LinkState.Live) {
            _phoneAsk.value = PhoneAsk(
                item = item,
                action = action,
                title = "«${action.label}» делает телефон, а он сейчас не на связи",
                // Просьба лежит здесь, на этом компьютере, — не «в почте»: телефон сам
                // придёт за ней, когда человек его откроет (#817).
                what = "Просьба подождёт здесь и выполнится, когда вы откроете Point на телефоне " +
                    "и заберёте объект. Пока этого не случилось, здесь ничего не изменится.",
            )
            return
        }
        queueForPhone(item, action)
    }

    /** Согласился ждать — просьба ложится в почту телефона. */
    fun approvePhone() {
        val ask = _phoneAsk.value ?: return
        _phoneAsk.value = null
        queueForPhone(ask.item, ask.action, silent = true)
    }

    /** Отказ не наказывает: действие остаётся доступным на потом. */
    fun declinePhone() {
        _phoneAsk.value = null
        _message.value = "Ничего не отправлено — объект остался на компьютере. Действие доступно, если передумаете"
    }

    private fun queueForPhone(
        item: InboxItem,
        action: com.point.core.flow.PcRemoteAction,
        silent: Boolean = false,
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                outbox?.add(
                    item.obj.copy(
                        metadata = item.obj.metadata +
                            ("pc.action" to action.id) +
                            // Название работы человеческими словами кладёт компьютер: он его
                            // и показывал человеку. Телефону иначе неоткуда взять слова для
                            // уведомления, а звать реестр ради названия — лишний путь.
                            ("pc.action.label" to action.label),
                    )
                )
            }.onSuccess {
                _message.value = if (silent) {
                    "${action.label} — ждёт телефона: выполнится, когда вы его откроете"
                } else {
                    "${action.label} — ждёт телефона: откройте на телефоне главный экран Point и заберите объект"
                }

                // Шаг поставлен в очередь, а не выполнен (#1112): исхода у него ещё нет, и
                // галочка «получилось» здесь была неправдой — на компьютере ничего не появилось.
                noteAwaiting(item, action.id, "${action.label} · ждёт телефона", "ждёт телефона")
                runCatching { knockPhone() }
            }.onFailure {
                _message.value = "Не удалось положить в очередь"
                note(
                    item, action.id, "${action.label} · на телефон",
                    ActionResult.Failure("Не удалось отправить — проверьте, что на диске есть место", recoverable = true),
                )
            }
        }
    }

    /** Непросмотренные прибытия: след живёт, пока человек не открыл объект (PC3). */
    private val _fresh = MutableStateFlow<Set<String>>(emptySet())
    val fresh: StateFlow<Set<String>> = _fresh.asStateFlow()

    fun markSeen(objectId: String) {
        _fresh.update { it - objectId }
    }

    fun onReceived(item: InboxItem, source: ObjectSource = ObjectSource.LOCAL) {
        _items.update { listOf(item) + it }
        _fresh.update { it + item.obj.id }
        rememberArrival(item, source)
        runCatching { announce(item, source) }

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
     *
     * Единообразно, а не по одному жёстко зашитому id (владелец, 10.08.2026): любая
     * Capability компьютера с `investigation = true`, подходящая объекту, подключается
     * сама — новой способности обогащения на ПК не нужна отдельная правка здесь.
     */
    private fun autoInvestigate(item: InboxItem) {
        val questions = registry.all()
            .filter { it.meta.investigation && it.accepts(item.obj.state) }
            .map { it.id }
        questions.forEach { question ->
            val asked = com.point.core.flow.investigationStateOf(item.obj.metadata, question)
            if (asked != com.point.core.flow.InvestigationState.NOT_INVESTIGATED) return@forEach
            scope.launch {
                val realizer = runCatching { resolver.realizerFor(question, item.obj.state) }.getOrNull()
                    ?: return@launch
                if (realizer.meta.kind == com.point.core.flow.RealizerKind.CLOUD) return@launch
                val result = runCatching { realizer.perform(item.obj, null) }.getOrNull()
                val findings = (result as? ActionResult.Done)?.findings ?: return@launch
                if (!findings.isEmpty) landFindings(item, findings)
            }
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
                // Выбранный человеком режим спрашивается ДО согласия: если он сказал
                // «только на этом устройстве», спрашивать «отправить?» уже поздно и
                // нечестно — объект туда не поедет в любом случае (#893).
                val level = privacyLevel()
                if (!com.point.core.flow.allowedAt(level, com.point.core.flow.AI_CHAIN_PRIVACY)) {
                    _message.value = com.point.core.flow.chainClosedBy(level)
                    return@launch
                }
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

    /** Шаг ушёл на телефон и ждёт его: исхода нет, и журнал говорит именно это (#1112). */
    private fun noteAwaiting(item: InboxItem, capabilityId: String, title: String, note: String) {
        updateJournal {
            recordStep(it, item.obj.uri.value, awaitingStep(capabilityId, title, clock.now(), note))
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

/**
 * Что компьютер честно говорит про работу, которую делает телефон (#785).
 *
 * Прежде здесь стояло обещание «просьба подождёт в его почте и выполнится, когда вы
 * откроете Point на телефоне». Не выполнялась никогда: телефон стирал её `Mailbox.drain`
 * при первом же своём обращении к серверу. Обещание было хуже отсутствия действия —
 * человек ждал результата, которого никто не собирался делать.
 */
const val PHONE_DOES_NOT_RUN_REQUESTS = "телефон пока не выполняет просьбы с компьютера"
