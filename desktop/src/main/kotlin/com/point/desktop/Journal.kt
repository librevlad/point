package com.point.desktop

import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import com.point.core.model.ActionResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

/**
 * Путь объекта на компьютере (#407).
 *
 * До этого ПК ничего не помнил: объект приезжал, над ним выполняли действие — и след пропадал
 * вместе с окном. Человек не мог ответить себе на два простых вопроса: «что сюда уже приезжало»
 * и «я это уже печатал или мне кажется». Конвейер (#285) рисовал полосу пути заглушкой именно
 * поэтому — данных под ней не было.
 *
 * Здесь — только запись и правила. Никакого автозапуска: журнал говорит, что **было**, а
 * повторить действие или открыть объект заново человек решает сам (инвариант «Point никогда не
 * строит автоматические цепочки»).
 *
 * Всё в этом файле чистое, кроме [FileJournalStore]: файл и часы — за швами [JournalStore] и
 * [Clock], в тестах подставляются подделки.
 */

/** Откуда объект взялся на этом компьютере. Происхождение видно человеку, а не только логу. */
enum class ObjectSource {
    /** С телефона напрямую по локальной сети. */
    PHONE_LAN,

    /** С телефона через релей — устройства были в разных сетях. */
    PHONE_RELAY,

    /** Перетащен в окно мышью. */
    DROPPED,

    /** Взят из буфера обмена (Ctrl+Shift+V). */
    CLIPBOARD,

    /** Пришёл с самого компьютера: из проводника, аргументом запуска. */
    LOCAL,
}

/**
 * Одна станция пути: применённая возможность и чем она кончилась.
 *
 * [ok] и [note] раздельны намеренно: «не глотай ошибки» — провал обязан остаться провалом с
 * причиной словами, а не исчезнуть из истории как будто ничего не было.
 */
data class JournalStep(
    val capabilityId: String,
    val title: String,
    val at: Long,
    val ok: Boolean,
    val note: String,
)

/**
 * Один объект и весь его путь.
 *
 * Ключ записи — [path], а не id объекта: id рождается заново при каждом переоткрытии файла
 * (`Inbox.addFile` выдаёт новый UUID), и путь объекта рвался бы на куски при каждом рестарте.
 * Файл на диске — то, что человек считает «тем самым объектом».
 */
data class JournalEntry(
    val path: String,
    val name: String,
    /** Имя `ObjectKind` строкой: старый файл журнала переживает появление новых видов. */
    val kind: String,
    val mime: String,
    val source: ObjectSource,
    val at: Long,
    val steps: List<JournalStep> = emptyList(),
)

/** Сколько объектов помним. Журнал — память о последнем, а не архив всего. */
const val JOURNAL_LIMIT = 40

/** Сколько станций помним у одного объекта; лишние старые отпадают с головы. */
const val JOURNAL_STEPS_LIMIT = 20

/**
 * Объект приехал.
 *
 * Тот же файл не заводит вторую запись: путь **продолжается** — накопленные станции остаются,
 * запись поднимается наверх с новым временем. Иначе повторно расшаренный с телефона счёт
 * размножался бы в списке, а его история пряталась бы в старой копии.
 */
fun recordArrival(
    entries: List<JournalEntry>,
    arrival: JournalEntry,
    limit: Int = JOURNAL_LIMIT,
): List<JournalEntry> {
    val known = entries.firstOrNull { it.path == arrival.path }
    val merged = arrival.copy(steps = known?.steps ?: arrival.steps)
    return (listOf(merged) + entries.filterNot { it.path == arrival.path }).take(limit)
}

/**
 * К объекту применили возможность.
 *
 * Шаг к неизвестному объекту не заводит запись из воздуха: журнал — память о том, что приезжало,
 * а не догадка о том, что где-то было. Такой шаг просто теряется, и это честнее выдуманной строки.
 */
fun recordStep(
    entries: List<JournalEntry>,
    path: String,
    step: JournalStep,
    stepsLimit: Int = JOURNAL_STEPS_LIMIT,
): List<JournalEntry> =
    entries.map { entry ->
        if (entry.path != path) entry else entry.copy(steps = (entry.steps + step).takeLast(stepsLimit))
    }

/** Что из журнала показать отдельным списком «было раньше»: всё, чего нет на экране сейчас. */
fun recentBesides(
    entries: List<JournalEntry>,
    livePaths: Set<String>,
    limit: Int = 8,
): List<JournalEntry> = entries.filterNot { it.path in livePaths }.take(limit)

/** Результат шага — станцией пути. Перевод в одном месте: иначе каждый вызов врал бы по-своему. */
fun stepOf(capabilityId: String, title: String, at: Long, result: ActionResult): JournalStep = when (result) {
    is ActionResult.Done -> JournalStep(capabilityId, title, at, ok = true, note = result.message)
    is ActionResult.Success -> JournalStep(capabilityId, title, at, ok = true, note = "получился новый объект")
    is ActionResult.Failure -> JournalStep(capabilityId, title, at, ok = false, note = result.reason)
    is ActionResult.NeedsInput -> JournalStep(capabilityId, title, at, ok = false, note = "остановилось на вопросе: ${result.prompt}")
    is ActionResult.NeedsImage -> JournalStep(capabilityId, title, at, ok = false, note = "остановилось на выборе картинки")
}

/**
 * Откуда приехал — словами продукта.
 *
 * «в этой сети» / «через интернет» — та же речь, какой о связи говорит `linkLabel` в `:core:flow`:
 * человеку важно, быстро ли и работает ли вдали от дома, а не название транспорта.
 */
fun sourceLabel(source: ObjectSource): String = when (source) {
    ObjectSource.PHONE_LAN -> "с телефона · в этой сети"
    ObjectSource.PHONE_RELAY -> "с телефона · через интернет"
    ObjectSource.DROPPED -> "перетащен в окно"
    ObjectSource.CLIPBOARD -> "взят из буфера"
    ObjectSource.LOCAL -> "с этого компьютера"
}

/** Короткая форма для узкого дока — там строка живёт под именем файла. */
fun sourceShort(source: ObjectSource): String = when (source) {
    ObjectSource.PHONE_LAN, ObjectSource.PHONE_RELAY -> "с телефона"
    ObjectSource.DROPPED -> "перетащен"
    ObjectSource.CLIPBOARD -> "из буфера"
    ObjectSource.LOCAL -> "с компьютера"
}

/**
 * Когда это было.
 *
 * Часы, а не «5 минут назад»: журнал переживает перезапуск, и относительное время в нём
 * превращается в «17 часов назад» — величину, которую человек всё равно пересчитывает в «вчера
 * вечером». Зона передаётся снаружи, чтобы функция оставалась чистой и проверяемой.
 */
fun whenLabel(at: Long, now: Long, zone: ZoneId): String {
    val day = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = "%02d:%02d".format(day.hour, day.minute)
    return when (day.toLocalDate()) {
        today -> "сегодня $clock"
        today.minusDays(1) -> "вчера $clock"
        else -> "${day.dayOfMonth} ${monthOf(day.toLocalDate())} · $clock"
    }
}

private fun monthOf(date: LocalDate): String = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)[date.monthValue - 1]

/**
 * Хранилище журнала. Файл — за швом: в тестах подставляется список в памяти.
 */
interface JournalStore {
    fun load(): List<JournalEntry>
    fun save(entries: List<JournalEntry>)
}

/** Часы за швом — чтобы время шага в тесте было предсказуемым, а не «сейчас». */
fun interface Clock {
    fun now(): Long
}

/**
 * Кодек журнала: строка на запись, `base64(encodePcMeta(...))`.
 *
 * Тот же приём, что у `encodePcOutbox` в протоколе, и тот же кодек `k=v` — второго способа
 * хранить состояние в модуле не заводится. Base64 снаружи спасает от переводов строки и табов
 * в именах и причинах отказа: испортить чужую строку они уже не могут.
 */
fun encodeJournal(entries: List<JournalEntry>): String =
    entries.joinToString("\n") { entry ->
        val meta = buildMap {
            put("path", entry.path)
            put("name", entry.name)
            put("kind", entry.kind)
            put("mime", entry.mime)
            put("source", entry.source.name)
            put("at", entry.at.toString())
            entry.steps.forEachIndexed { i, step ->
                put("step.$i.id", step.capabilityId)
                put("step.$i.title", step.title)
                put("step.$i.at", step.at.toString())
                put("step.$i.ok", if (step.ok) "1" else "0")
                put("step.$i.note", step.note)
            }
        }
        Base64.getEncoder().encodeToString(encodePcMeta(meta).toByteArray(Charsets.UTF_8))
    }

/**
 * Разбор журнала. Битая строка выбрасывается молча — из-за одной испорченной записи не теряется
 * вся память компьютера; неизвестное `source` становится [ObjectSource.LOCAL], потому что
 * выдумывать телефон, которого могло не быть, хуже, чем сказать «с этого компьютера».
 */
fun decodeJournal(encoded: String): List<JournalEntry> =
    encoded.lineSequence().mapNotNull { line ->
        runCatching {
            val meta = decodePcMeta(String(Base64.getDecoder().decode(line.trim()), Charsets.UTF_8))
            val path = meta["path"].orEmpty()
            if (path.isBlank()) return@mapNotNull null
            JournalEntry(
                path = path,
                name = meta["name"].orEmpty(),
                kind = meta["kind"].orEmpty(),
                mime = meta["mime"].orEmpty(),
                source = runCatching { ObjectSource.valueOf(meta["source"].orEmpty()) }
                    .getOrDefault(ObjectSource.LOCAL),
                at = meta["at"]?.toLongOrNull() ?: 0L,
                steps = decodeSteps(meta),
            )
        }.getOrNull()
    }.toList()

private fun decodeSteps(meta: Map<String, String>): List<JournalStep> =
    meta.keys.filter { it.startsWith("step.") && it.endsWith(".id") }
        .mapNotNull { it.removePrefix("step.").removeSuffix(".id").toIntOrNull() }
        .sorted().mapNotNull { i ->
        val id = meta["step.$i.id"] ?: return@mapNotNull null
        JournalStep(
            capabilityId = id,
            title = meta["step.$i.title"].orEmpty(),
            at = meta["step.$i.at"]?.toLongOrNull() ?: 0L,
            ok = meta["step.$i.ok"] != "0",
            note = meta["step.$i.note"].orEmpty(),
        )
    }

/**
 * Журнал в файле рядом с остальным состоянием ПК (`~/.point-pc/journal`).
 *
 * Запись идёт через временный файл с атомарной подменой: оборвавшаяся на середине запись иначе
 * оставила бы обрезанный журнал — то есть тихо стёрла бы память о работе человека.
 */
class FileJournalStore(private val file: File) : JournalStore {

    @Synchronized
    override fun load(): List<JournalEntry> =
        runCatching { decodeJournal(file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    override fun save(entries: List<JournalEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(encodeJournal(entries))
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
