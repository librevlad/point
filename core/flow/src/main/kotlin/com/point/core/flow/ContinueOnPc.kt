package com.point.core.flow

import java.nio.ByteBuffer

/**
 * Продолжение на компьютере (#147) — язык, на котором говорят обе стороны. Чистый Kotlin: телефон
 * собирает им кадры, компьютер их разбирает. Первое воплощение «текучего софта»: состояние объекта
 * (байты И понимание о нём) переезжает между устройствами.
 */

/**
 * Компьютер из круга аккаунта — всё, что телефону нужно, чтобы с ним заговорить (#475).
 *
 * Связывания как действия больше нет: устройство, вошедшее в тот же аккаунт, оказывается в круге
 * само, и здесь остаётся ровно то, что круг про него рассказал. Ни адреса, ни порта, ни токена
 * пары: адрес письма — [deviceId], а [key] — открытая половина ключа соседа, из которой считается
 * общий секрет ([DeviceKeys.sharedSecret]).
 *
 * Пустой [key] — законное состояние, а не поломка: компьютер вошёл сборкой без ключей или круг
 * приехал раньше, чем тот успел объявиться. Отправить туда нечего, и отказ об этом скажет словами.
 */
data class LinkedPc(
    val deviceId: String,
    val name: String,
    val key: String = "",
)

/**
 * The object as it crosses the server: [meta] (name/mime/understanding) + raw [bytes], framed so the
 * far side reconstructs it. The whole frame is sealed by RelayCrypto (#161 v2) — the server
 * only ever holds the ciphertext.
 */
class PcFrame(val meta: Map<String, String>, val bytes: ByteArray)

/** `[4-byte header length][encodePcMeta header][raw bytes]` — binary-safe, so any object survives. */
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

/**
 * Understanding travels with the object: metadata as `key=value` lines. Values lose
 * line breaks (collapsed to spaces) — the format stays trivially parseable on any side.
 */
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

/** One action the paired PC can run on a received object (#80).
 *  [kinds] — ObjectKind names the action makes sense for; empty = any kind.
 *
 *  [unavailable] (#316) — не `null`, если это компьютер **умеет**, но прямо сейчас сделать не
 *  может, и строка объясняет почему («нет принтера»). Раньше такое действие просто не
 *  объявлялось, и человек читал молчание как «Point не умеет печатать» — хотя умеет, печатать
 *  некуда именно сейчас. Пустая строка = «недоступно, причина не названа»: тапнуть всё равно
 *  нельзя (`null` и «не смог объяснить» — разные состояния, и второе не должно стать первым). */
data class PcRemoteAction(
    val id: String,
    val label: String,
    val kinds: Set<String> = emptySet(),
    val unavailable: String? = null,
    /**
     * Увезёт ли эта реализация объект **из круга устройств** — к чужому сервису (контракт
     * 06.08.2026, граница молчаливого выбора).
     *
     * Между своими устройствами объект едет запечатанным, и спрашивать нечего. Но компьютер умеет
     * и другое: прочитать снимок чужим сервисом, спросить модель, расшифровать речь. Тап по такому
     * действию сделан на телефоне — значит и согласие должно спрашиваться там, ДО отправки, а не
     * на компьютере, где человека в этот момент нет.
     *
     * Пока признака не было, объект уходил наружу без единого вопроса на обеих поверхностях:
     * действия компьютера объявлены на телефоне несетевыми (и правильно — до чужого сервиса
     * доходит не каждое), а какие именно доходят, телефон знать не мог.
     */
    val leavesCircle: Boolean = false,
    /**
     * Какие признаки объекта нужны этому действию — «любой из перечисленных» (#597).
     *
     * Пустое множество: действие смотрит только на вид объекта. Непустое: действие живёт признаком
     * — «Позвонить» нужен телефонный номер в объекте, «Написать письмо» — почта.
     *
     * Без этого поля признаковое измерение схлопывалось: «Позвонить» принимает объект с номером —
     * значит принимает пробу каждого вида — значит объявлялось принимающим **любой** объект. На
     * экране компьютера из-за этого стояло 32 строки на картинку, из них десять бессмысленных.
     */
    val features: Set<String> = emptySet(),
)

/**
 * `id=label` per line, optionally `id=label<TAB>KIND1,KIND2` — the same dumb-simple
 * line codec as [encodePcMeta]; a tab never appears in a human label.
 *
 * Недоступное действие (#316) едет строкой `=id=label<TAB>KINDS<TAB>причина` — ведущий `=`
 * выбран не за красоту: старый декодер отбрасывает строку ровно по правилу `indexOf('=') <= 0`
 * (то же, что уже роняло мусорную строку `=безид` в тесте кодека). То есть старый телефон
 * встречает незнакомую форму, молча её игнорирует и ведёт себя как раньше — кнопки, которую
 * нельзя нажать, у него не появится. Доступные действия кодируются байт-в-байт как прежде,
 * поэтому старый ПК, не знающий о признаке, для нового телефона не меняется.
 */
fun encodePcCaps(caps: List<PcRemoteAction>): String =
    caps.joinToString("\n") { action ->
        val label = oneLine(action.label)
        val kinds = action.kinds.joinToString(",")
        val why = action.unavailable
        // Поля строки по порядку: имя · виды · причина недоступности · «увезёт из круга» · какие
        // признаки нужны. Каждое следующее дописано позже предыдущего, и старая сторона берёт
        // поля по индексу, а лишнее молча игнорирует (ворота 10). Поэтому хвост строится с
        // конца: пустые поля отбрасываются, и строка без новостей остаётся байт в байт прежней.
        val fields = listOf(
            kinds,
            why?.let(::oneLine).orEmpty(),
            if (action.leavesCircle) "out" else "",
            action.features.sorted().joinToString(","),
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
        // Лишние поля будущих версий просто игнорируются — расширять формат можно, не ломая нас.
        val fields = line.substring(eq + 1).split('\t')
        val label = fields[0].trim()
        val kinds = fields.getOrElse(1) { "" }.split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        val why = if (unavailableLine) fields.getOrElse(2) { "" }.trim() else null
        val leaves = fields.getOrElse(3) { "" }.trim() == "out"
        val needs = fields.getOrElse(4) { "" }.split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        if (id.isEmpty() || label.isEmpty()) null else PcRemoteAction(id, label, kinds, why, leaves, needs)
    }.toList()

/** Метка недоступного действия в начале строки (#316) — см. [encodePcCaps]. */
const val PC_CAP_UNAVAILABLE = "="

/**
 * Ответ компьютера на `/receive`: доставка **и** исход заказанного действия (#114).
 *
 * Формат нарочно дописан к прежнему «ok» второй строкой, а не заменён: старая сборка ПК отвечает
 * одним «ok», и новый телефон обязан прочитать это как «доехало, про исход неизвестно» — то есть
 * сказать «Отправлено на компьютер», а не «готово». Старый телефон вторую строку не читает вовсе,
 * и для него ничего не меняется.
 *
 * ```
 * ok
 * action: done В очереди «HP LaserJet» · проверьте принтер
 * ```
 */
fun encodePcReceiveReply(outcome: PcActionOutcome?): String = when (outcome) {
    null -> PC_RECEIVE_OK
    is PcActionOutcome.Done ->
        PC_RECEIVE_OK + "\n" + PC_ACTION_LINE + "done" + (outcome.detail?.let { " " + oneLine(it) } ?: "")
    is PcActionOutcome.Failed ->
        PC_RECEIVE_OK + "\n" + PC_ACTION_LINE + "failed " + oneLine(outcome.reason).ifBlank { "причина не названа" }
}

/**
 * Обратно: `null` — компьютер об исходе ничего не сказал.
 *
 * Незнакомая форма — тоже `null`: молчание честнее выдуманного «готово». Именно это и защищает
 * человека от старой сборки ПК, которая отвечает просто «ok».
 */
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
        "failed" -> PcActionOutcome.Failed(detail.ifBlank { "причина не названа" })
        else -> null
    }
}

/**
 * Исход действия словами контракта — из результата, каким его вернул реализатор компьютера.
 *
 * `null` значит «неизвестно» и попадает на телефон как «отправлено»: `NeedsInput` на компьютере
 * спросить некого, а `null` результата — это работа, за которой мы не дождались.
 */
fun pcActionOutcomeOf(result: com.point.core.model.ActionResult?): PcActionOutcome? = when (result) {
    null -> null
    is com.point.core.model.ActionResult.Done -> PcActionOutcome.Done(result.message)
    is com.point.core.model.ActionResult.Success -> PcActionOutcome.Done(null)
    is com.point.core.model.ActionResult.Failure -> PcActionOutcome.Failed(result.reason)
    is com.point.core.model.ActionResult.NeedsInput, is com.point.core.model.ActionResult.NeedsImage ->
        PcActionOutcome.Failed("действие спрашивает на компьютере — ответить отсюда нечем")
}

private const val PC_RECEIVE_OK = "ok"

/** Вторая строка ответа `/receive`: «action: done …» или «action: failed …». */
const val PC_ACTION_LINE = "action: "

private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

/** One object waiting in the PC's outbox for the phone to pull (#161).
 *  The display name and mime live inside [meta] («name», «mime»). */
data class PcOutboxEntry(val id: Int, val meta: Map<String, String>)

/** `id<TAB>base64(encodePcMeta(meta))` per line — base64 keeps tabs and newlines
 *  inside metadata values from ever breaking the line format. */
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

/**
 * Что компьютер вернул, выполнив заказанное действие.
 *
 * До этого он возвращал только слово: «готово» или «не вышло». Объект оставался у него, и человек,
 * попросивший с телефона распознать снимок, шёл за текстом к компьютеру. Формула продукта одна на
 * оба устройства — объект, действие, снова объект, — и последней части здесь не было.
 *
 * Понимание возвращается вместе с байтами: заказчик кладёт его в метаданные нового объекта, и тот
 * приходит уже разобранным, а не голым файлом.
 */
class PcReturned(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
    val understanding: Map<String, String> = emptyMap(),
)

/**
 * Имена полей ответа, когда возвращается объект.
 *
 * Отдельные имена, а не переиспользование `name`/`mime` из запроса: в одном письме едут и то, что
 * прислали, и то, что вернули, и одинаковые имена однажды слиплись бы.
 */
object PcResultFields {
    const val NAME = "result.name"
    const val MIME = "result.mime"
    const val OUTCOME = "result.outcome"
    const val DETAIL = "result.detail"

    /** Понимание возвращённого объекта: остальные поля меты с этой приставкой. */
    const val UNDERSTOOD = "result.understood."

    const val DONE = "done"
    const val FAILED = "failed"

    /** Есть ли в этом ответе объект, а не только слово. */
    fun hasObject(meta: Map<String, String>): Boolean = !meta[NAME].isNullOrBlank()
}

