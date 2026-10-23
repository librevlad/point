package com.point.core.flow

import com.point.core.model.tr
import com.point.core.model.trPlural
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
 *
 * @param serviceSaid чужой ответ дословно — для журнала обменов, не для человека (#1236).
 *   Пока он стоял в тексте исключения, он уезжал на баннер; выбросить его совсем значило
 *   оставить отладочный стенд без единого следа, чем ответил сервис на 400 и 500.
 */
class AiServiceRefusal(
    val serviceId: String,
    val status: Int?,
    message: String,
    val serviceSaid: String? = null,
) : IllegalStateException(message)

val KEY_NOT_ACCEPTED: String get() = tr("ключ не принят")

/**
 * Молчащий сервис — в конец очереди (#433).
 *
 * Исход каждого обращения уже помнится, а порядок его не читал: сервис, промолчавший минуту
 * назад, снова шёл первым, и человек снова ждал таймаут. Теперь он стоит последним, пока не
 * выйдет срок, — но из очереди не выпадает: если больше некому, пробуется и он.
 *
 * Срок зависит от того, чем кончилось. Промолчал — это минуты: сеть и сервисы
 * восстанавливаются быстро. Лимит или непринятый ключ сами не проходят — час.
 */
fun benched(fact: AiFact?, now: Long): Boolean {
    val ago = now - (fact ?: return false).at
    if (ago < 0) return false
    return when (fact.outcome) {
        AiOutcome.SILENT -> ago < SILENT_BENCH_MS
        AiOutcome.LIMIT, AiOutcome.BAD_KEY -> ago < SPENT_BENCH_MS
        AiOutcome.ANSWERED -> false
    }
}

/** Та же очередь, только недавно молчавшие — в конце; порядок остальных не меняется. */
fun <T> awakeFirst(queue: List<T>, now: Long, factOf: (T) -> AiFact?): List<T> =
    queue.sortedBy { benched(factOf(it), now) }

const val SILENT_BENCH_MS = 10 * 60_000L
const val SPENT_BENCH_MS = 60 * 60_000L

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

    // Признак «ключ не приняли» — из общего словаря (#1236): подстроки «HTTP 401/403»
    // держались здесь за строку с кодом ответа и куском чужого JSON, которую человек читал
    // на баннере. Теперь такие клиенты бросают [AiServiceRefusal] с кодом, а по тексту
    // признак узнаётся там же, где его узнаёт разбор чужого ответа.
    looksLikeKeyRefusal(message) -> AiOutcome.BAD_KEY
    else -> AiOutcome.SILENT
}

/**
 * Какой из отказов цепочки пересказывать дальше (#1236).
 *
 * Перебор оставлял наружу последний отказ, и на смешанном наборе признак терялся: первая
 * модель ответила «лимит исчерпан», вторая промолчала пятисотой — и человек читал «сервис не
 * отвечает», а память сервиса запоминала «не отвечал» вместо «лимит исчерпан». Громче тот,
 * чей исход человек может изменить: непринятый ключ и исчерпанный предел сильнее молчания.
 * При равной громкости остаётся первый — он и случился раньше.
 */
fun louderRefusal(kept: Exception?, next: Exception): Exception =
    if (kept == null || refusalLoudness(next) > refusalLoudness(kept)) next else kept

private fun refusalLoudness(failure: Throwable): Int = when (aiOutcomeOf(failure)) {
    AiOutcome.BAD_KEY, AiOutcome.LIMIT -> 2
    AiOutcome.SILENT -> 1
    AiOutcome.ANSWERED -> 0
}

/** Пока к сервису не обращались — говорить о нём в строке нечего (#887). */
val NEVER_ASKED: String get() = tr("ещё не обращались")

/** Последний факт словами человека: «ответил 3 минуты назад», «лимит исчерпан в 23:26». */
fun aiFactLine(fact: AiFact?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (fact == null) return NEVER_ASKED
    val what = when (fact.outcome) {
        AiOutcome.ANSWERED -> tr("ответил")
        AiOutcome.LIMIT -> tr("лимит исчерпан")
        AiOutcome.BAD_KEY -> tr("ключ не подошёл")
        AiOutcome.SILENT -> tr("не отвечал")
    }
    return "$what ${whenWord(fact.at, now, zone)}"
}

/** Возраст сведений: пока человек не нажал «Проверить все», они могут быть вчерашними. */
fun aiCheckedLine(facts: Map<String, AiFact>, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val newest = facts.values.maxByOrNull { it.at } ?: return tr("Ещё не проверяли")
    return tr("Проверено {0}", whenWord(newest.at, now, zone))
}

/**
 * Итог проверки одной строкой: сколько сервисов с ключом отвечают и когда это узнали.
 *
 * После «Проверить все» человек видел только «Проверено только что» и, чтобы понять, всё ли
 * в порядке, обходил строки глазами: беда стоит в строке, а благополучие молчит. Итог —
 * ответ на вопрос, ради которого нажимали. Пока о каком-то сервисе с ключом сведений нет,
 * счёт был бы неправдой — остаётся возраст сведений.
 */
fun aiChainLine(
    lines: List<AiServiceLine>,
    facts: Map<String, AiFact>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val ready = lines.filter { it.ready }.map { it.providerId }
    val known = ready.mapNotNull { facts[it] }
    if (ready.isEmpty() || known.size < ready.size) return aiCheckedLine(facts, now, zone)
    val answered = known.count { it.outcome == AiOutcome.ANSWERED }
    val count = if (answered == ready.size) tr("Отвечают все {0}", ready.size) else tr("Отвечают {0} из {1}", answered, ready.size)
    return tr("{0} · проверено {1}", count, whenWord(known.maxOf { it.at }, now, zone))
}

fun whenWord(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val ago = now - at
    if (ago in 0 until MINUTE) return tr("только что")
    if (ago in 0 until HOUR) {
        val minutes = ago / MINUTE
        return tr("{0} {1} назад", minutes, trPlural(minutes, "минуту", "минуты", "минут"))
    }
    val then = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
    val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate()
    val clock = "${pad(then.hour)}:${pad(then.minute)}"
    return when (then.toLocalDate()) {
        today -> tr("в {0}", clock)
        today.minusDays(1) -> tr("вчера в {0}", clock)
        else -> tr("{0} {1} в {2}", then.dayOfMonth, MONTHS_OF[then.monthValue - 1], clock)
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

    /** Беда сервиса, если она была: «ответил» — ожидаемое, о нём строка молчит (#902). */
    val trouble: String? = null,

    /** Место в очереди обращения: «03» перед именем объясняет порядок без слов. */
    val place: Int = 0,

    /**
     * Цена ключа у сервиса, к которому ключа нет ни у кого: «бесплатный уровень», «платно».
     * Человек выбирает, куда идти за ключом, — и видит главное, не раскрывая строку.
     */
    val offer: String? = null,
)

/** Цена ключа у сервиса без бесплатного уровня. */
val AI_PAID_OFFER: String get() = tr("платно")

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
    val provider = AI_PROVIDERS.firstOrNull { it.id == id }
    return AiServiceLine(
        providerId = id,
        name = name,
        what = what,
        trouble = aiTroubleLine(facts[id], now, zone),
        place = aiChainPlace(id),
        offer = if (mine.isNotEmpty() || ours || provider == null) null else provider.freeNote ?: AI_PAID_OFFER,
        keyLine = when {
            mine.isNotEmpty() -> tr("ваш ключ {0}", maskedKey(mine))
            ours -> tr("работает на ключе Point")
            else -> tr("ключа нет — этот сервис молчит")
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
    return if (keys.mine.isEmpty()) tr("{0} — Point работает на своих", counted) else counted
}

/** Только счёт: на самом экране ключей длинная приписка уже сказана выше (#887). */
fun aiKeysCount(keys: UserAiKeys): String = tr("Свои ключи: {0} из {1}", keys.mine.size, AI_PROVIDERS.size)

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
    tr("января"), tr("февраля"), tr("марта"), tr("апреля"), tr("мая"), tr("июня"),
    tr("июля"), tr("августа"), tr("сентября"), tr("октября"), tr("ноября"), tr("декабря"),
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
    MINE(tr("Ваши ключи")),
    OURS(tr("Работают на ключе Point")),
    SILENT(tr("Нужен ваш ключ")),
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

/**
 * Очередь названа прямо: это снимает вопрос «надо выбрать один?».
 *
 * Две мысли, а не семь строк: экран ключей — панель состояния, а не документация. Остальное
 * ждёт за «Как это работает» (#902, разбор 13.08.2026).
 */
val AI_CHAIN_WHAT: String get() =
    tr("Point обращается к сервисам по очереди — отвечает первый доступный. Свой ключ не " +
        "обязателен: он снимает общий лимит и пускает Point работать на вашей квоте.")

/** Подробное объяснение — по запросу, а не поверх экрана. */
val AI_CHAIN_MORE: String get() =
    tr("«Понять», «Перевести», «AI» и расшифровку записи делает модель. У большинства сервисов " +
        "ключ бесплатный. Ключ живёт только на этом устройстве. Проверка сервисов идёт только " +
        "по вашему нажатию — сам Point ничего не проверяет.")

/**
 * Почему «Ваши ключи» стоят не подряд: 05, 09, 12 — это их места в общей очереди (#911).
 * Без этой строки номера заставляли искать логику сортировки там, где её нет.
 */
val AI_MINE_KEEP_PLACE: String get() = tr("Ваши ключи сохраняют свои места в общей очереди.")

/**
 * Вставка ключа, ещё не знающего своего сервиса: он сам встаёт к своему по приметам начала.
 * Раньше человек искал строку сервиса среди двенадцати, раскрывал её, вставлял и отдельно
 * нажимал «Проверить».
 */
val PASTE_ANY_KEY: String get() = tr("Вставить ключ из буфера")

val PASTE_ANY_KEY_WHAT: String get() = tr("Ключ встанет к своему сервису и сразу проверится.")

val NO_KEY_IN_CLIPBOARD: String get() = tr("В буфере нет ключа — скопируйте его на странице сервиса и вернитесь.")

/** Ключ без приметы сервиса: Point спрашивает, а не гадает. */
val WHOSE_KEY_TITLE: String get() = tr("Чей это ключ?")

val WHOSE_KEY_WHAT: String get() =
    tr("По виду ключа сервис не узнать. Нажмите тот, где вы его взяли, — проверять ключ Point будет только там.")

val WHOSE_KEY_DISMISS: String get() = tr("Не вставлять")

/** Ключ другого сервиса узнан по началу — сказать до проверки, а не объявить его негодным. */
fun keyOfAnotherService(owner: String, here: String): String = tr("Похоже, это ключ {0}, а не {1}.", owner, here)

fun checkAsKeyOf(owner: String): String = tr("Проверить как ключ {0}", owner)

/** Место сервиса в очереди: «03» перед именем объясняет порядок обращения без слов. */
fun aiChainPlace(providerId: String): Int =
    AI_PROVIDERS.indexOfFirst { it.id == providerId }.let { if (it >= 0) it + 1 else AI_PROVIDERS.size + 1 }

/**
 * Что сказать про сервис в закрытой строке.
 *
 * «Ответил» — ожидаемое, и говорить о нём нечего: строка молчит. Новость — это отказ,
 * исчерпанный лимит или молчание сервиса: их человек и должен увидеть, не раскрывая строку.
 */
fun aiTroubleLine(fact: AiFact?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? =
    fact?.takeIf { it.outcome != AiOutcome.ANSWERED }?.let { aiFactLine(it, now, zone) }

/**
 * Что стоит во второй строке сервиса — одно правило на телефон и компьютер. Общее про группу
 * уже сказано её заголовком, поэтому здесь остаётся личное: свой ключ, последний факт, ход
 * проверки.
 */
fun aiServiceState(line: AiServiceLine, checking: Boolean, open: Boolean): String? = when {
    checking -> tr("проверяю…")
    open && line.mine -> line.what + "\n" + line.keyLine + " · " + line.factLine
    open -> line.what
    line.mine -> "${line.keyLine} · ${line.factLine}"
    // «Ответил» — ожидаемое, о нём строка молчит. Новость — отказ, исчерпанный лимит или
    // молчание сервиса: их видно, не раскрывая строку (#902). У сервиса без ключа новость —
    // цена: по ней выбирают, куда идти за ключом.
    else -> line.trouble ?: line.offer
}
