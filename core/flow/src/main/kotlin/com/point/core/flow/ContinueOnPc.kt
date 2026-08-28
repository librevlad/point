package com.point.core.flow

import java.nio.ByteBuffer

data class LinkedPc(
    val deviceId: String,
    val name: String,
    val key: String = "",
)

class PcFrame(val meta: Map<String, String>, val bytes: ByteArray)

fun encodePcFrame(meta: Map<String, String>, bytes: ByteArray): ByteArray {
    val header = encodePcMeta(meta).toByteArray(Charsets.UTF_8)
    return ByteBuffer.allocate(4 + header.size + bytes.size)
        .putInt(header.size).put(header).put(bytes).array()
}

fun decodePcFrame(blob: ByteArray): PcFrame {
    val buffer = ByteBuffer.wrap(blob)
    val headerLen = buffer.int
    require(headerLen in 0..buffer.remaining()) { "malformed frame" }
    val header = ByteArray(headerLen).also(buffer::get)
    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
    return PcFrame(decodePcMeta(String(header, Charsets.UTF_8)), bytes)
}

fun encodePcMeta(meta: Map<String, String>): String =
    meta.entries.joinToString("\n") { (k, v) ->
        "${k.replace('\n', ' ')}=${v.replace('\n', ' ')}"
    }

fun decodePcMeta(encoded: String): Map<String, String> =
    encoded.lineSequence()
        .filter { it.contains('=') }
        .associate { line ->
            val i = line.indexOf('=')
            line.substring(0, i) to line.substring(i + 1)
        }

data class PcRemoteAction(
    val id: String,
    val label: String,
    val kinds: Set<String> = emptySet(),
    val unavailable: String? = null,

    val leavesCircle: Boolean = false,

    val features: Set<String> = emptySet(),

    /** Польза действия по его собственной поверхности: чужие действия ранжируются вместе со своими (P10). */
    val priority: Int = PC_CAP_DEFAULT_PRIORITY,
)

/**
 * Годится ли чужое действие этому объекту — одно правило на обе стороны (#1092).
 *
 * Компьютер спрашивал вид и признаки, телефон — только вид: действие компьютера «только для
 * ссылки» на телефоне предлагалось объекту без ссылки. Правило одно: вид совпал, и, если
 * действие назвало признаки, хотя бы один есть у объекта.
 */
object PcActionFit {
    fun PcRemoteAction.fitsObject(state: com.point.core.model.ObjectState): Boolean =
        (kinds.isEmpty() || state.kind.name in kinds) &&
            (features.isEmpty() || features.any { named -> state.features.any { it.name == named } })
}

const val PC_CAP_DEFAULT_PRIORITY = 1000

fun encodePcCaps(caps: List<PcRemoteAction>): String =
    caps.joinToString("\n") { action ->
        val label = oneLine(action.label)
        val kinds = action.kinds.joinToString(",")
        val why = action.unavailable

        val fields = listOf(
            kinds,
            why?.let(::oneLine).orEmpty(),
            if (action.leavesCircle) "out" else "",
            action.features.sorted().joinToString(","),
            if (action.priority == PC_CAP_DEFAULT_PRIORITY) "" else action.priority.toString(),
        ).dropLastWhile(String::isEmpty)
        val head = if (why == null) action.id else PC_CAP_UNAVAILABLE + action.id
        if (fields.isEmpty()) "$head=$label" else "$head=$label\t" + fields.joinToString("\t")
    }

fun decodePcCaps(encoded: String): List<PcRemoteAction> =
    encoded.lineSequence().mapNotNull { raw ->
        val unavailableLine = raw.startsWith(PC_CAP_UNAVAILABLE)
        val line = if (unavailableLine) raw.substring(PC_CAP_UNAVAILABLE.length) else raw
        val eq = line.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val id = line.substring(0, eq).trim()

        val fields = line.substring(eq + 1).split('\t')
        val label = fields[0].trim()
        val kinds = fields.getOrElse(1) { "" }.split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        val why = if (unavailableLine) fields.getOrElse(2) { "" }.trim() else null
        val leaves = fields.getOrElse(3) { "" }.trim() == "out"
        val needs = fields.getOrElse(4) { "" }.split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        val priority = fields.getOrElse(5) { "" }.trim().toIntOrNull() ?: PC_CAP_DEFAULT_PRIORITY
        if (id.isEmpty() || label.isEmpty()) null else PcRemoteAction(id, label, kinds, why, leaves, needs, priority)
    }.toList()

const val PC_CAP_UNAVAILABLE = "="

fun encodePcReceiveReply(outcome: PcActionOutcome?): String = when (outcome) {
    null -> PC_RECEIVE_OK
    is PcActionOutcome.Done ->
        PC_RECEIVE_OK + "\n" + PC_ACTION_LINE + "done" + (outcome.detail?.let { " " + oneLine(it) } ?: "")
    is PcActionOutcome.Failed ->
        PC_RECEIVE_OK + "\n" + PC_ACTION_LINE + "failed " + oneLine(outcome.reason).ifBlank { "причина не названа" }
}

fun decodePcReceiveReply(body: String): PcActionOutcome? {
    val line = body.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(PC_ACTION_LINE) }
        ?.removePrefix(PC_ACTION_LINE)
        ?.trim()
        ?: return null
    val verdict = line.substringBefore(' ')
    val detail = line.substringAfter(' ', "").trim()
    return when (verdict) {
        "done" -> PcActionOutcome.Done(detail.takeIf { it.isNotBlank() })
        "failed" -> PcActionOutcome.Failed(detail.ifBlank { PC_REASON_UNNAMED })
        else -> null
    }
}

/**
 * Просьба соседа ждёт ответа человека — отправлять ли наружу (#1269).
 *
 * Согласие спрашивается там, где действие исполняется, и до «да» объект никуда не уходит.
 * Исход у такого шага честный: не «сделано» и не «не вышло», а «ждёт»
 * ([PcResultFields.AWAITING]). Иначе устройство, попросившее сделать, показывало галочку
 * либо провал, хотя работа ещё не начиналась.
 */
const val AWAITS_CONSENT_TEXT = "Ждёт вашего ответа на телефоне: отправлять ли наружу"

/**
 * Сказанное «нет» — тоже ответ, и просивший его ждёт (#1269).
 *
 * Отказ гас там, где его произнесли, и на компьютере навсегда оставалось
 * [AWAITS_CONSENT_TEXT]: человек ждал ответа, которого уже не будет. Исход у отказа
 * терминальный — работа не пошла, и это окончательно, пока не попросят снова.
 */
const val CONSENT_DECLINED_TEXT = "Отказано на телефоне: наружу объект не отправлен"

fun pcActionOutcomeOf(result: com.point.core.model.ActionResult?): PcActionOutcome? = when (result) {
    null -> null
    is com.point.core.model.ActionResult.Done -> PcActionOutcome.Done(result.message)
    is com.point.core.model.ActionResult.Success -> PcActionOutcome.Done(null)
    is com.point.core.model.ActionResult.Failure -> PcActionOutcome.Failed(result.reason)
    is com.point.core.model.ActionResult.NeedsInput, is com.point.core.model.ActionResult.NeedsImage ->
        PcActionOutcome.Failed("действие спрашивает на компьютере — ответить отсюда нечем")
}

private const val PC_RECEIVE_OK = "ok"

const val PC_ACTION_LINE = "action: "

/** Отказ, у которого не нашлось слов, всё равно называется отказом. */
private const val PC_REASON_UNNAMED = "причина не названа"

private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

data class PcOutboxEntry(val id: Int, val meta: Map<String, String>)

fun encodePcOutbox(entries: List<PcOutboxEntry>): String =
    entries.joinToString("\n") { entry ->
        val meta = java.util.Base64.getEncoder().encodeToString(encodePcMeta(entry.meta).toByteArray())
        "${entry.id}\t$meta"
    }

fun decodePcOutbox(encoded: String): List<PcOutboxEntry> =
    encoded.lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val id = line.substring(0, tab).trim().toIntOrNull() ?: return@mapNotNull null
        val meta = runCatching {
            decodePcMeta(String(java.util.Base64.getDecoder().decode(line.substring(tab + 1).trim())))
        }.getOrNull() ?: return@mapNotNull null
        PcOutboxEntry(id, meta)
    }.toList()

class PcReturned(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
    val understanding: Map<String, String> = emptyMap(),
)

/**
 * Просьба исполнить действие — не переезд объекта (ADR-0001 §7, §20).
 *
 * Дом объекта и место исполнения — разные вещи. Устройство, которое умеет нужное, —
 * исполнитель, а не новый дом: объект остаётся там, где с ним работает человек, а к соседу
 * уезжает только то, что нужно для работы, и возвращается результат.
 *
 * Своей машины состояний у этого нет: это поля того же письма, каким объекты и знание ездят
 * между устройствами. По [HOME] результат находит свой объект, по [REQUEST] — свой шаг.
 */
object PcExecFields {

    /** Какую способность просят исполнить. */
    const val ACTION = "exec.action"

    /** Чем эта просьба называется человеку на той стороне. */
    const val LABEL = "exec.label"

    /** Тождество шага: по нему возвращённый результат находит свою просьбу. */
    const val REQUEST = "exec.request"

    /** Объект у себя дома — туда вернётся результат. */
    const val HOME = "exec.home"

    /** Из какого объекта сделан этот результат (родословная, ADR-0001 §2). */
    const val OF = "exec.of"

    /** Каким действием он сделан. */
    const val CREATOR = "exec.creator"

    /** Каким путём получен — тем же словарём `Provenance`, что и дома. */
    const val SOURCE = "exec.src"

    /** Кто именно исполнил (#1127). */
    const val BY = "exec.executor"
}

/**
 * Родословная объекта, уезжающего к другому устройству.
 *
 * Поля объекта письмом не ездят — едут только метаданные, — и без этих трёх строк результат
 * приезжал на ту сторону сиротой: новый идентификатор, происхождение «дано» и ни следа
 * объекта, из которого он сделан.
 */
fun lineageMeta(
    sourceId: String?,
    creator: String?,
    provenance: com.point.core.model.Provenance,
    executor: String? = null,
): Map<String, String> = buildMap {
    sourceId?.takeIf { it.isNotBlank() }?.let { put(PcExecFields.OF, it) }
    creator?.takeIf { it.isNotBlank() }?.let { put(PcExecFields.CREATOR, it) }
    put(PcExecFields.SOURCE, provenance.wire)
    executor?.takeIf { it.isNotBlank() }?.let { put(PcExecFields.BY, it) }
}

/** Та же родословная, восстановленная на той стороне: приехавший результат — не сирота. */
fun withLineage(obj: com.point.core.model.PointObject, meta: Map<String, String>): com.point.core.model.PointObject {
    val of = meta[PcExecFields.OF]?.takeIf { it.isNotBlank() }
    val creator = meta[PcExecFields.CREATOR]?.takeIf { it.isNotBlank() }
    val source = meta[PcExecFields.SOURCE]?.let { com.point.core.model.provenanceOf(it) }
    if (of == null && creator == null && source == null) return obj
    return obj.copy(
        provenance = source ?: obj.provenance,
        sourceObjects = if (of == null) obj.sourceObjects else listOf(of),
        creatorAction = creator ?: obj.creatorAction,
    )
}

/** Что из письма — служебное и знанием об объекте не является. */
val PC_EXEC_META: Set<String> = setOf(
    PcExecFields.ACTION, PcExecFields.LABEL, PcExecFields.REQUEST, PcExecFields.HOME,
    PcExecFields.OF, PcExecFields.CREATOR, PcExecFields.SOURCE, PcExecFields.BY,
)

object PcResultFields {
    const val NAME = "result.name"
    const val MIME = "result.mime"
    const val OUTCOME = "result.outcome"
    const val DETAIL = "result.detail"

    const val UNDERSTOOD = "result.understood."

    const val DONE = "done"
    const val FAILED = "failed"

    /**
     * Шаг не кончился, а ждёт человека (#1269, ADR-0001 §18).
     *
     * Терминальных исхода у операции три, и «ждёт» ни одним из них не является: работа
     * ещё не начиналась и не срывалась. Слов на проводе было два, поэтому незавершённый
     * шаг уезжал соседу как «не вышло» — и человек за компьютером читал провал там, где у
     * него всего лишь спрашивают согласие.
     */
    const val AWAITING = "awaiting"

    fun hasObject(meta: Map<String, String>): Boolean = !meta[NAME].isNullOrBlank()

    /**
     * Исход — полями письма (#1073).
     *
     * Тот же словарь, каким телефон возвращает компьютеру исход его просьбы: так и поздний
     * исход просьбы соседу без объекта — «Отменено» у диалога сохранения, отказ принтера —
     * едет очередью компьютера домой, а не теряется там, где файла не родилось.
     */
    fun of(outcome: PcActionOutcome): Map<String, String> = when (outcome) {
        is PcActionOutcome.Done -> buildMap {
            put(OUTCOME, DONE)
            outcome.detail?.takeIf { it.isNotBlank() }?.let { put(DETAIL, it) }
        }
        is PcActionOutcome.Failed -> mapOf(OUTCOME to FAILED, DETAIL to outcome.reason)
    }

    /** Исход, записанный в полях письма, или `null` — письмо про исход не говорит. */
    fun outcomeOf(meta: Map<String, String>): PcActionOutcome? = when (meta[OUTCOME]) {
        DONE -> PcActionOutcome.Done(meta[DETAIL]?.takeIf { it.isNotBlank() })
        FAILED -> PcActionOutcome.Failed(meta[DETAIL].orEmpty().ifBlank { PC_REASON_UNNAMED })
        else -> null
    }

    /** Письмо об одном исходе, без объекта (#1073): забирать с него нечего, это слова домой. */
    fun outcomeOnly(meta: Map<String, String>): Boolean = outcomeOf(meta) != null && !hasObject(meta)
}
