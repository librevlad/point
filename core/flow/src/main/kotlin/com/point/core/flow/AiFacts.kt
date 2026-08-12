package com.point.core.flow

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Чем кончилось последнее настоящее обращение к сервису (#699). Не квота и не
 * прогноз: сервисы процентов не отдают, а Point не выдумывает того, чего не знает.
 */
enum class AiOutcome { ANSWERED, LIMIT, BAD_KEY, SILENT }

data class AiFact(val outcome: AiOutcome, val at: Long)

/**
 * Память об исходах: переживает перезапуск. Состояние операции, а не знание об
 * объекте — поэтому живёт отдельно от Graph.
 */
interface AiFacts {

    fun all(): Map<String, AiFact>

    fun remember(providerId: String, outcome: AiOutcome)
}

/**
 * Отказ конкретного сервиса: человеку — слова, Point — ещё и код ответа, чтобы
 * «лимит исчерпан» не превращался в «не отвечал» при пересказе.
 */
class AiServiceRefusal(
    val serviceId: String,
    val status: Int?,
    message: String,
) : IllegalStateException(message)

const val KEY_NOT_ACCEPTED = "ключ не принят"

fun aiOutcomeOf(failure: Throwable): AiOutcome =
    if (failure is AiServiceRefusal) {
        aiOutcomeOfStatus(failure.status)
    } else {
        aiOutcomeOfFailure(failure.message.orEmpty())
    }

fun aiOutcomeOfStatus(status: Int?): AiOutcome = when {
    status == null -> AiOutcome.SILENT
    status in 200..299 -> AiOutcome.ANSWERED
    status == 401 || status == 403 -> AiOutcome.BAD_KEY
    status == 402 || status == 429 -> AiOutcome.LIMIT
    else -> AiOutcome.SILENT
}

fun aiOutcomeOfFailure(message: String): AiOutcome = when {
    looksLikeQuotaFailure(message) -> AiOutcome.LIMIT
    KEY_FAILURE_MARKS.any { message.contains(it, ignoreCase = true) } -> AiOutcome.BAD_KEY
    else -> AiOutcome.SILENT
}

private val KEY_FAILURE_MARKS =
    listOf(KEY_NOT_ACCEPTED, "HTTP 401", "HTTP 403", "(401)", "(403)")

/** Пока к сервису не обращались — говорить о нём в строке нечего (#887). */
const val NEVER_ASKED = "ещё не обращались"

/** Последний факт словами человека: «ответил 3 минуты назад», «лимит исчерпан в 23:26». */
fun aiFactLine(fact: AiFact?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (fact == null) return NEVER_ASKED
    val what = when (fact.outcome) {
        AiOutcome.ANSWERED -> "ответил"
        AiOutcome.LIMIT -> "лимит исчерпан"
        AiOutcome.BAD_KEY -> "ключ не подошёл"
        AiOutcome.SILENT -> "не отвечал"
    }
    return "$what ${whenWord(fact.at, now, zone)}"
}

/** Возраст сведений: пока человек не нажал «Проверить все», они могут быть вчерашними. */
fun aiCheckedLine(facts: Map<String, AiFact>, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val newest = facts.values.maxByOrNull { it.at } ?: return "Ещё не проверяли"
    return "Проверено ${whenWord(newest.at, now, zone)}"
}

fun whenWord(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val ago = now - at
    if (ago in 0 until MINUTE) return "только что"
    if (ago in 0 until HOUR) {
        val minutes = ago / MINUTE
        return "$minutes ${plural(minutes, "минуту", "минуты", "минут")} назад"
    }
    val then = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
    val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate()
    val clock = "${pad(then.hour)}:${pad(then.minute)}"
    return when (then.toLocalDate()) {
        today -> "в $clock"
        today.minusDays(1) -> "вчера в $clock"
        else -> "${then.dayOfMonth} ${MONTHS_OF[then.monthValue - 1]} в $clock"
    }
}

/** Одна строка экрана ключей: имя, что умеет, есть ли ключ и последний факт. */
data class AiServiceLine(
    val providerId: String,
    val name: String,
    val what: String,
    val keyLine: String,
    val factLine: String,
    val mine: Boolean,
    val ready: Boolean,
)

/**
 * Все известные сервисы списком — в том порядке, в каком Point к ним обращается.
 * Свой адрес человека встаёт в конец, чтобы его ключ не пропал из виду.
 */
fun aiServiceLines(
    keys: UserAiKeys,
    builtIn: Set<String>,
    facts: Map<String, AiFact>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<AiServiceLine> {
    val known = AI_PROVIDERS.map { provider ->
        line(provider.id, provider.name, provider.what, keys, builtIn, facts, now, zone)
    }
    val own = keys.of(OWN_SERVICE_ID)
        ?.let { line(OWN_SERVICE_ID, OWN_SERVICE_NAME, OWN_SERVICE_WHAT, keys, builtIn, facts, now, zone) }
    return known + listOfNotNull(own)
}

private fun line(
    id: String,
    name: String,
    what: String,
    keys: UserAiKeys,
    builtIn: Set<String>,
    facts: Map<String, AiFact>,
    now: Long,
    zone: ZoneId,
): AiServiceLine {
    val mine = keys.keyFor(id)
    val ours = id in builtIn
    return AiServiceLine(
        providerId = id,
        name = name,
        what = what,
        keyLine = when {
            mine.isNotEmpty() -> "ваш ключ ${maskedKey(mine)}"
            ours -> "работает на ключе Point"
            else -> "ключа нет — этот сервис молчит"
        },
        factLine = aiFactLine(facts[id], now, zone),
        mine = mine.isNotEmpty(),
        ready = mine.isNotEmpty() || ours,
    )
}

/**
 * Строка раздела в настройках: сколько своих ключей, без выдуманных процентов.
 *
 * Счёт стоит и в нуле: «Свои ключи: 0 из 11» человек читает за долю секунды и сразу видит,
 * сколько их вообще бывает. Прежнее «Своих ключей пока нет» этого не говорило, и понять,
 * стоит ли туда заходить, можно было только зайдя (#886).
 */
fun aiKeysSummary(keys: UserAiKeys): String {
    val counted = aiKeysCount(keys)
    return if (keys.mine.isEmpty()) "$counted — Point работает на своих" else counted
}

/** Только счёт: на самом экране ключей длинная приписка уже сказана выше (#887). */
fun aiKeysCount(keys: UserAiKeys): String = "Свои ключи: ${keys.mine.size} из ${AI_PROVIDERS.size}"

fun encodeAiFacts(facts: Map<String, AiFact>): String = facts.entries.joinToString("\n") {
    "${it.key}\t${it.value.outcome.name}\t${it.value.at}"
}

fun decodeAiFacts(text: String?): Map<String, AiFact> {
    val lines = text?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
    return lines.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@mapNotNull null
        val outcome = AiOutcome.entries.firstOrNull { it.name == parts[1].trim() } ?: return@mapNotNull null
        val at = parts[2].trim().toLongOrNull() ?: return@mapNotNull null
        parts[0].trim() to AiFact(outcome, at)
    }.toMap()
}

private fun pad(value: Int): String = value.toString().padStart(2, '0')

private val MONTHS_OF = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/**
 * Группы сервисов на экране ключей (#887).
 *
 * Одиннадцать строк подряд носили один и тот же хвост — «работает на ключе Point · ещё не
 * обращались». Девять раз одно и то же читается как шум, а главный вопрос человека — «мне
 * что, выбрать один из одиннадцати?» — оставался без ответа.
 *
 * Общее говорится заголовком группы один раз, а в строке остаётся имя сервиса. Решение
 * владельца по мокапам 12.08.2026: «вариант Б — общее сказано один раз».
 */
enum class AiServiceGroup(val title: String) {
    MINE("Ваши ключи"),
    OURS("Работают на ключе Point"),
    SILENT("Молчат — нужен ваш ключ"),
}

fun aiServiceGroupOf(line: AiServiceLine): AiServiceGroup = when {
    line.mine -> AiServiceGroup.MINE
    line.ready -> AiServiceGroup.OURS
    else -> AiServiceGroup.SILENT
}

/** Список по группам, в том же порядке обращения. Пустая группа не показывается. */
fun aiServiceGroups(lines: List<AiServiceLine>): List<Pair<AiServiceGroup, List<AiServiceLine>>> =
    AiServiceGroup.entries.mapNotNull { group ->
        lines.filter { aiServiceGroupOf(it) == group }
            .takeIf { it.isNotEmpty() }
            ?.let { group to it }
    }

/** Очередь названа прямо: это снимает вопрос «надо выбрать один?». */
const val AI_CHAIN_WHAT =
    "Point обращается к сервисам по очереди, сверху вниз: отвечает первый доступный. " +
        "Свой ключ не обязателен — он снимает общий лимит и пускает Point работать на вашей квоте."
