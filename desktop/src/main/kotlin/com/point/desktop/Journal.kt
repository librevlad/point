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

enum class ObjectSource {

    PHONE_LAN,

    PHONE_RELAY,

    DROPPED,

    CLIPBOARD,

    LOCAL,
}

/**
 * Чем кончился шаг — ADR-0001 §18.
 *
 * Исхода три, и «ещё не кончился» исходом не является. Прежде в журнале стояла галочка
 * «получилось»: шаг, поставленный в очередь к телефону, записывался как выполненный,
 * хотя не принёс ничего и мог ещё не выйти (#1112). Ожидание продолжения — не успех и
 * не провал, и называть его надо своим словом.
 */
enum class StepOutcome(val wire: String) {

    DONE("done"),

    FAILED("failed"),

    AWAITING("awaiting"),
}

data class JournalStep(
    val capabilityId: String,
    val title: String,
    val at: Long,
    val outcome: StepOutcome,
    val note: String,
) {

    /** Совместимость с прежним журналом- галочка достаётся только состоявшемуся шагу. */
    val ok: Boolean get() = outcome == StepOutcome.DONE
}

data class JournalEntry(
    val path: String,
    val name: String,

    val kind: String,
    val mime: String,
    val source: ObjectSource,
    val at: Long,
    val steps: List<JournalStep> = emptyList(),

    /** Знание объекта (metadata): переживает рестарт, иначе перенос теряет знание (PC2/PC5). */
    val meta: Map<String, String> = emptyMap(),
)

const val JOURNAL_LIMIT = 40

const val JOURNAL_STEPS_LIMIT = 20

/** Значения длиннее — содержимое, а не строка знания: в журнал не пишутся. */
const val JOURNAL_META_VALUE_LIMIT = 4_000

fun recordArrival(
    entries: List<JournalEntry>,
    arrival: JournalEntry,
    limit: Int = JOURNAL_LIMIT,
): List<JournalEntry> {
    val known = entries.firstOrNull { it.path == arrival.path }
    val merged = arrival.copy(
        steps = known?.steps ?: arrival.steps,
        meta = (known?.meta ?: emptyMap()) + arrival.meta,
    )
    return (listOf(merged) + entries.filterNot { it.path == arrival.path }).take(limit)
}

/**
 * Записать шаг в журнал объекта.
 *
 * Шаг, который ещё ждёт, не размножается (#1269): «ждёт телефона» → «ждёт согласия» →
 * «готово» — это один шаг, менявший состояние, а не три разных. Пока последняя запись того
 * же умения ждёт, новая её заменяет; законченный шаг не трогается никогда, и история
 * прежних запусков остаётся целой.
 */
fun recordStep(
    entries: List<JournalEntry>,
    path: String,
    step: JournalStep,
    stepsLimit: Int = JOURNAL_STEPS_LIMIT,
): List<JournalEntry> =
    entries.map { entry ->
        if (entry.path != path) {
            entry
        } else {
            val stillWaiting = entry.steps.lastOrNull()
                ?.let { it.outcome == StepOutcome.AWAITING && it.capabilityId == step.capabilityId }
                ?: false
            val steps = if (stillWaiting) entry.steps.dropLast(1) + step else entry.steps + step
            entry.copy(steps = steps.takeLast(stepsLimit))
        }
    }

/** Знание объекта после merge: журнал держит уже слитое состояние, не дельту. */
fun recordKnowledge(
    entries: List<JournalEntry>,
    path: String,
    meta: Map<String, String>,
): List<JournalEntry> =
    entries.map { entry -> if (entry.path != path) entry else entry.copy(meta = meta) }

/**
 * Всё, что Point ещё помнит, кроме открытого прямо сейчас (#1098).
 *
 * Здесь стоял предел в восемь записей: журнал держал сорок объектов со знанием, а в окне
 * человек доставал восемь — остальное было недостижимо ни прокруткой, ни поиском. Сколько
 * строк показать сразу — дело списка, а не памяти: список листается сам.
 */
fun recentBesides(
    entries: List<JournalEntry>,
    livePaths: Set<String>,
): List<JournalEntry> = entries.filterNot { it.path in livePaths }

fun stepOf(capabilityId: String, title: String, at: Long, result: ActionResult): JournalStep = when (result) {
    is ActionResult.Done -> JournalStep(capabilityId, title, at, StepOutcome.DONE, result.message)
    is ActionResult.Success -> JournalStep(capabilityId, title, at, StepOutcome.DONE, "получился новый объект")
    is ActionResult.Failure -> JournalStep(capabilityId, title, at, StepOutcome.FAILED, result.reason)

    // Вопрос и выбор картинки — ожидание продолжения, а не провал (ADR-0001 §18).
    is ActionResult.NeedsInput ->
        JournalStep(capabilityId, title, at, StepOutcome.AWAITING, "остановилось на вопросе: ${result.prompt}")

    is ActionResult.NeedsImage ->
        JournalStep(capabilityId, title, at, StepOutcome.AWAITING, "остановилось на выборе картинки")
}

/** Шаг ждёт продолжения на другом устройстве: исхода у него пока нет (#1112). */
fun awaitingStep(capabilityId: String, title: String, at: Long, note: String): JournalStep =
    JournalStep(capabilityId, title, at, StepOutcome.AWAITING, note)

fun sourceLabel(source: ObjectSource): String = when (source) {
    ObjectSource.PHONE_LAN, ObjectSource.PHONE_RELAY -> "с телефона"
    ObjectSource.DROPPED -> "перетащен в окно"
    ObjectSource.CLIPBOARD -> "взят из буфера"
    ObjectSource.LOCAL -> "с этого компьютера"
}

fun sourceShort(source: ObjectSource): String = when (source) {
    ObjectSource.PHONE_LAN, ObjectSource.PHONE_RELAY -> "с телефона"
    ObjectSource.DROPPED -> "перетащен"
    ObjectSource.CLIPBOARD -> "из буфера"
    ObjectSource.LOCAL -> "с компьютера"
}

fun stepsWord(count: Int): String {
    val mod100 = count % 100
    val word = when {
        mod100 in 11..14 -> "действий"
        count % 10 == 1 -> "действие"
        count % 10 in 2..4 -> "действия"
        else -> "действий"
    }
    return "$count $word"
}

fun whenLabel(at: Long, now: Long, zone: ZoneId): String {
    val day = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = "%02d:%02d".format(day.hour, day.minute)
    val date = day.toLocalDate()

    val year = if (date.year == today.year) "" else " ${date.year}"
    return when (date) {
        today -> "сегодня $clock"
        today.minusDays(1) -> "вчера $clock"
        else -> "${date.dayOfMonth} ${monthOf(date)}$year · $clock"
    }
}

private fun monthOf(date: LocalDate): String = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)[date.monthValue - 1]

interface JournalStore {
    fun load(): List<JournalEntry>
    fun save(entries: List<JournalEntry>)
}

fun interface Clock {
    fun now(): Long
}

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
                put("step.$i.outcome", step.outcome.wire)
                put("step.$i.note", step.note)
            }
            entry.meta.forEach { (key, value) ->
                if (value.length <= JOURNAL_META_VALUE_LIMIT) {
                    // Base64 — чтобы многострочное знание не расплющилось кодеком строк.
                    put("meta.$key", Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)))
                }
            }
        }
        Base64.getEncoder().encodeToString(encodePcMeta(meta).toByteArray(Charsets.UTF_8))
    }

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
                meta = decodeMeta(meta),
            )
        }.getOrNull()
    }.toList()

private fun decodeMeta(fields: Map<String, String>): Map<String, String> =
    fields.entries
        .filter { it.key.startsWith("meta.") }
        .mapNotNull { (key, value) ->
            runCatching {
                key.removePrefix("meta.") to String(Base64.getDecoder().decode(value), Charsets.UTF_8)
            }.getOrNull()
        }
        .toMap()

private fun decodeSteps(meta: Map<String, String>): List<JournalStep> =
    meta.keys.filter { it.startsWith("step.") && it.endsWith(".id") }
        .mapNotNull { it.removePrefix("step.").removeSuffix(".id").toIntOrNull() }
        .sorted().mapNotNull { i ->
        val id = meta["step.$i.id"] ?: return@mapNotNull null
        JournalStep(
            capabilityId = id,
            title = meta["step.$i.title"].orEmpty(),
            at = meta["step.$i.at"]?.toLongOrNull() ?: 0L,
            outcome = meta["step.$i.outcome"]
                ?.let { wire -> StepOutcome.entries.firstOrNull { it.wire == wire } }
                ?: if (meta["step.$i.ok"] != "0") StepOutcome.DONE else StepOutcome.FAILED,
            note = meta["step.$i.note"].orEmpty(),
        )
    }

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
