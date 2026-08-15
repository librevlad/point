package com.point.core.flow

import com.point.core.model.Feature

data class Entity(
    val type: EntityType,
    val value: String,

    /** Строка документа вокруг значения — подпись при нём, а не оно само (#782). */
    val line: String? = null,
)

enum class EntityType { PHONE, EMAIL, URL, ADDRESS, DATE_TIME, PAYMENT_CARD, MONEY }

interface EntityExtractor {
    suspend fun extract(text: String): List<Entity>
}

fun plausibleEntities(entities: List<Entity>, sourceText: String = ""): List<Entity> {
    val numbers = numberRuns(sourceText)
    return entities.filter { it.isPlausible() && !it.isFragmentOf(numbers) }
        .flatMap { it.readDatesApart() }
}

/**
 * Один вход знания читает дату тем же правилом, что и все остальные (#782): движок
 * отдаёт весь размеченный кусок текста, а датой является только дата внутри него.
 * Интервал распадается на два дня, обёртка вокруг даты становится подписью.
 */
private fun Entity.readDatesApart(): List<Entity> {
    if (type != EntityType.DATE_TIME) return listOf(this)
    val whole = value.trim()
    return readDates(whole).map { day ->
        copy(value = day, line = line ?: whole.takeIf { it != day })
    }
}

private fun numberRuns(text: String): List<String> =
    NUMBER_TOKEN.findAll(text).map { it.value.filter(Char::isDigit) }.toList()

private val NUMBER_TOKEN = Regex("""\d[\d \-]*\d|\d""")

internal val BARE_CLOCK = Regex("""\d{1,2}:\d{2}(\s*[AaPp][Mm])?""")

fun bareClock(value: String): Boolean = BARE_CLOCK.matches(value.trim())

fun Entity.isBareClock(): Boolean = type == EntityType.DATE_TIME && bareClock(value)

/**
 * Относительное слово — указатель на день, а не день (#659): «вчера» из переписки и
 * «next minute» из письма становились знанием о дате, которого на кадре нет. Смысл
 * такого слова истёк в момент снимка — календарём оно не является ни в каком виде.
 */
fun relativeDayWord(value: String): Boolean = RELATIVE_DAY.matches(value.trim())

/**
 * Есть ли относительное слово ВНУТРИ значения (#784, решение владельца 11.08.2026:
 * «завтра до 09:00 это не дата»).
 *
 * Прежнее правило сверяло значение целиком, поэтому ловило «завтра» и пропускало «завтра до
 * 09:00» — а это тот же указатель на день, только с часом рядом. Час не чинит главного:
 * какого дня этот час, неизвестно никому, включая Point.
 */
fun holdsRelativeDayWord(value: String): Boolean = RELATIVE_INSIDE.containsMatchIn(value)

private val RELATIVE_INSIDE = Regex(
    "(?iu)(?<!\\p{L})(?:(?:поза)?(?:вчера|вчора)|сегодн[яi]|сьогодн[\u0456i]|" +
        "(?:після)?завтра|післязавтра|yesterday|today|tomorrow|tonight)(?!\\p{L})",
)

private val RELATIVE_DAY = Regex(
    """(?iu)(?:поза)?(?:вчера|вчора)[.,!]?|сегодн[яi]|сьогодн[іi]|(?:після)?завтра|післязавтра|
       |yesterday|today|tomorrow|tonight|(?:next|last|this)\s+\p{L}+""".trimMargin().replace("\n", ""),
)

private fun Entity.isFragmentOf(numbers: List<String>): Boolean {
    if (type != EntityType.PHONE) return false
    val digits = value.filter(Char::isDigit)
    if (digits.isEmpty()) return false
    return numbers.any { it.length > digits.length && it.contains(digits) }
}

fun Entity.isPlausible(): Boolean = when (type) {

    // Та же мерка, что у ответа модели: одна библиотека на все входы знания (#801).
    EntityType.PHONE -> PhoneNumbers.exists(value)

    EntityType.ADDRESS -> value.trim().length >= 5 && Regex("""\p{L}{3,}""").containsMatchIn(value)

    // Та же мерка, что и у знания от модели (#1028, #989): у ссылки правдоподобия не
    // спрашивали вовсе, и обломок домена `com.ua` вставал находкой наравне с телефоном.
    EntityType.URL -> looksLikeLink(value)

    else -> true
}

fun EntityType.asFeature(): Feature? = when (this) {
    EntityType.PHONE -> Feature.HAS_PHONE
    EntityType.EMAIL -> Feature.HAS_EMAIL
    EntityType.ADDRESS -> Feature.HAS_ADDRESS
    EntityType.DATE_TIME -> Feature.HAS_DATE
    EntityType.PAYMENT_CARD -> Feature.HAS_CARD
    EntityType.URL, EntityType.MONEY -> null
}

fun EntityType.asExtractedKind(): com.point.core.model.ObjectKind? = when (this) {
    EntityType.PHONE -> KIND_PHONE
    EntityType.EMAIL -> KIND_EMAIL
    EntityType.URL -> KIND_URL
    EntityType.ADDRESS -> KIND_ADDRESS
    EntityType.DATE_TIME -> KIND_DATE
    EntityType.PAYMENT_CARD, EntityType.MONEY -> null
}

const val META_ENTITY_PREFIX = "entity."

fun EntityType.asMetaKey(): String? = when (this) {
    EntityType.PHONE -> META_ENTITY_PREFIX + "phone"
    EntityType.EMAIL -> META_ENTITY_PREFIX + "email"
    EntityType.ADDRESS -> META_ENTITY_PREFIX + "address"
    EntityType.DATE_TIME -> META_ENTITY_PREFIX + "date"
    EntityType.PAYMENT_CARD -> META_ENTITY_PREFIX + "card"
    EntityType.URL -> META_ENTITY_PREFIX + "url"
    EntityType.MONEY -> null
}
