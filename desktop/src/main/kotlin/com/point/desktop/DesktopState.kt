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

/**
 * The desktop's state holder (the VM analogue, hand-wired): received objects, a
 * transient message and the link to the phone.
 *
 * Ещё он ведёт журнал (#407): что приезжало, откуда и что с этим делали. Память живёт за швом
 * [JournalStore] — экран читает её тем же способом, каким читает всё остальное состояние.
 */
/**
 * Идущая работа: что делается, что делается ПРЯМО сейчас и когда началось.
 *
 * [stage] пуст, пока реализатор о себе молчит, — и это законно: экран тогда показывает имя работы
 * и время, а не выдуманный ход. Выдуманный ход хуже пустоты (тот же довод, что у стадий телефона).
 */
data class Working(val title: String, val stage: String?, val startedAt: Long)

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

    // Уборка файлов (#602) память НЕ трогает, и это разные вещи. Копились гигабайты снимков и
    // чеков, а путь объекта весит килобайты и есть вся ценность «где я был». Запись, за которой
    // файла уже нет, честно отказывает тапом («Файла больше нет»), а не исчезает: стёртая история
    // — это не аккуратность. Уходит она только вместе с человеком, при выходе из аккаунта.
    private val _journal = MutableStateFlow(runCatching { journalStore?.load() }.getOrNull().orEmpty())
    /** Путь объектов, переживший перезапуск (#407): самое свежее первым. */
    val journal: StateFlow<List<JournalEntry>> = _journal.asStateFlow()

    private val _working = MutableStateFlow<Working?>(null)

    /**
     * Что компьютер делает прямо сейчас — и можно ли передумать.
     *
     * До этого тап по действию не менял на экране ничего. «Прочитать в облаке» ждёт ответа сервиса
     * до двух минут, и всё это время человек смотрел на неподвижный экран без единого слова и без
     * выхода. На телефоне такое правило уже есть: работа дольше секунды обязана говорить, что она
     * делает, и обязана отменяться.
     */
    val working: StateFlow<Working?> get() = _working.asStateFlow()

    private var work: kotlinx.coroutines.Job? = null

    /**
     * Передумал.
     *
     * Отмена настоящая: корутина действия прекращается, объект остаётся тем, чем был, а в пути
     * появляется станция «отменено» — потому что стёртая из памяти попытка это не аккуратность,
     * а враньё.
     */
    fun cancelWork() {
        work?.cancel()
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _clipboardText = MutableStateFlow<String?>(null)
    /** The latest text that crossed into the PC clipboard — shown as a live «Буфер» card. */
    val clipboardText: StateFlow<String?> = _clipboardText.asStateFlow()

    private val _phoneCaps = MutableStateFlow<List<com.point.core.flow.PcRemoteAction>>(emptyList())
    /** The paired phone's advertised actions (#161 v2) — cards grow «… · телефон» buttons. */
    val phoneCaps = _phoneCaps.asStateFlow()

    // Когда телефон приходил в последний раз (#412). Без этого экран молчал, и человек не мог
    // отличить «связи нет» от «ничего не произошло». Путь один, и запоминать его больше нечего (#475).
    private val _lastContact = MutableStateFlow<Long?>(null)
    val lastContact: StateFlow<Long?> = _lastContact.asStateFlow()

    /** Телефон дал о себе знать. */
    fun heard() {
        _lastContact.value = System.currentTimeMillis()
    }

    fun bubblesFor(item: InboxItem): List<Bubble> = registry.bubblesFor(item.obj.state)

    /** Сказать человеку словами. Пустой буфер и прочие «ничего не вышло» обязаны звучать. */
    fun say(text: String) { _message.value = text }

    /** #80: the phone asked to run one of the advertised actions on the received object.
     *  Фоновая форма — для тех, кто исхода не ждёт. */
    fun runRemoteAction(id: String, item: InboxItem) {
        scope.launch { perform(id, item) }
    }

    /**
     * То же действие, но телефон **ждёт исход** (#114).
     *
     * Телефон говорил «Напечатать на ПК — готово», получив 200 на доставку файла. Теперь ответ
     * несёт то, чем действие кончилось здесь, — и «готово» произносит тот, кто это сделал.
     *
     * Ждём ограниченно ([timeoutMs] < чтения телефона): сборка PDF может идти минутами, и висеть
     * на сокете ради неё нельзя. Не дождались — `null`, то есть «доставлено, исход неизвестен»:
     * человек прочтёт «Отправлено на компьютер», что правда, а не «готово», что домысел.
     */
    fun runRemoteActionNow(id: String, item: InboxItem, timeoutMs: Long = 10_000): ActionResult? =
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { perform(id, item) }
        }

    /** Одна работа на оба пути: экран компьютера и его журнал обновляются одинаково. */
    private suspend fun perform(id: String, item: InboxItem, stationTitle: String? = null): ActionResult? {
        val title = stationTitle ?: titleOf(id, item)
        _message.value = null
        _working.value = Working(title, stage = null, startedAt = clock.now())
        val result = try {
            runCatching {
                // Реализатор рассказывает, что делает сейчас, тем же каналом, что и на телефоне:
                // кто умеет — говорит, кто молчит — тому экран показывает идущее время и отмену.
                kotlinx.coroutines.withContext(
                    com.point.core.flow.ActionProgress { stage ->
                        _working.value = _working.value?.copy(stage = stage)
                    },
                ) {
                    resolver.realizerFor(com.point.core.model.CapabilityId(id), item.obj.state)
                        .perform(item.obj, null)
                }
            }.getOrElse { e ->
                // Отмену нельзя проглатывать: пойманная как обычная ошибка, она превращает
                // «передумал» в «сломалось» и оставляет корутину живой.
                if (e is kotlinx.coroutines.CancellationException) throw e
                ActionResult.Failure(e.message ?: "не получилось", recoverable = true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _working.value = null
            _message.value = "Отменено"
            note(item, id, title, ActionResult.Failure("отменено", recoverable = true))
            throw e
        } finally {
            _working.value = null
        }
        // Результат становится ОБЪЕКТОМ здесь же (#595). Пока этого не было, работа на компьютере
        // обрывалась после первого действия: «Сделать легче» отдавало сжатый снимок, а на экране
        // оставался исходный — и следующее действие применялось к нему. Журнал владельца поймал
        // это дословно: «Сделать легче → 124 КБ», затем «Прочитать в облаке → снимок 1 МБ,
        // сначала Сделать легче».
        //
        // Формула продукта одна на оба устройства: Object → Action → Object. На телефоне
        // последняя стрелка была всегда, на компьютере её не было вовсе.
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
        // Тап был на телефоне, а работа шла здесь — иначе, вернувшись к компьютеру, человек
        // не поймёт, откуда взялся результат. Поэтому в пути станция названа с автором (#407).
        // Автор станции важен: вернувшись к компьютеру, человек должен понимать, откуда взялся
        // результат. Тап здесь — просто название; просьба с телефона — с пометкой (#407).
        note(item, id, if (stationTitle != null) title else "$title · с телефона", result)
        return result
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

    /**
     * Тап по действию на самом компьютере.
     *
     * Идёт тем же [perform], что и просьба с телефона (#595). Раньше это были ДВА разных пути с
     * почти одинаковым кодом, и результат действия игнорировался в обоих — но чинить пришлось бы
     * дважды, и второй раз про это забыли бы. Одна работа — одно место.
     */
    fun onBubble(item: InboxItem, bubble: Bubble) {
        work = scope.launch(Dispatchers.IO) { perform(bubble.capabilityId.value, item, bubble.title) }
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

    /** Re-copy the current clipboard text (after copying something else on the PC). */
    fun copyClipboardAgain() {
        _clipboardText.value?.let { runCatching { clipboard.copy(it) } }
    }

    /** «Выйти»: компьютер забывает и файлы, и путь — иначе следующий человек увидит чужое. */
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

