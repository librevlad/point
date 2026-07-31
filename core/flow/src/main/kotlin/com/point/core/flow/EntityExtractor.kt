package com.point.core.flow

import com.point.core.model.Feature

/** An actionable entity found in text. [value] is the raw form to act on (a phone number, email, …). */
data class Entity(val type: EntityType, val value: String)

/** The kinds of entity Point can turn into an action. Extend as new entity actions are added. */
enum class EntityType { PHONE, EMAIL, URL, ADDRESS, DATE_TIME, PAYMENT_CARD, MONEY }

/**
 * Finds actionable entities in text, **on-device** (no cloud — fits Point's no-surveillance stance).
 * Behind an interface so the ML Kit implementation is swappable and unit tests use a fake. Enrichers
 * use it to flag features (phone/email/…); realizers use it to extract the value to act on.
 */
interface EntityExtractor {
    suspend fun extract(text: String): List<Entity>
}

/**
 * Drop entities ML Kit over-eagerly flags on OCR'd documents (real-device feedback):
 * a waybill/account digit-run masquerading as a PHONE, a bare «г.» as an ADDRESS. Other
 * kinds pass through untouched. Applied at the extractor choke point so both the feature
 * flags and the realizers (which re-extract) see only plausible hits.
 */
fun plausibleEntities(entities: List<Entity>, sourceText: String = ""): List<Entity> {
    val numbers = numberRuns(sourceText)
    return entities.filter { it.isPlausible() && !it.isFragmentOf(numbers) }
}

/**
 * Числовые последовательности страницы: цифры одного числа, слитно, без пробелов и дефисов.
 *
 * Нужны, чтобы отличить телефон от **обрезка** более длинного числа. По форме их не различить:
 * первые двенадцать цифр банковской карты — законная длина телефона, а первые десять цифр
 * накладной тем более. Отличает только страница, на которой те же цифры продолжаются дальше.
 */
private fun numberRuns(text: String): List<String> =
    NUMBER_TOKEN.findAll(text).map { it.value.filter(Char::isDigit) }.toList()

/** Число как оно написано человеком: цифры, между ними могут стоять пробелы и дефисы. */
private val NUMBER_TOKEN = Regex("""\d[\d \-]*\d|\d""")

/**
 * Время суток и **ничего больше**: `11:41`, `9:05`, `7:30 PM`.
 *
 * Живой случай (#244): экран посылки показывал датой `11:41` — время статуса «Прибула до
 * пункту… Сьогодні, 11:41»; скриншот переписки показывал `18:24`, время «в сети» из шапки чата.
 *
 * Экран переписки состоит из времён почти целиком, и любое из них занимает единственное место
 * «Дата», вытесняя настоящую дату, которая на том же кадре есть: `30.03`, `01.04`.
 *
 * Правило **ранжирует, а не отсеивает**. Голое время остаётся сущностью, признаком
 * [Feature.HAS_DATE] и объектом графа: заметка «15:12 Встреча с Петром» не должна терять
 * «Создать событие» — случай, который #233 сохраняет намеренно. Время лишь встаёт последним
 * в очереди на роль «Дата документа», где календарная часть побеждает. Отсев здесь
 * уничтожал бы улику — ровно то, что запрещает решение по #232 (`docs/DECISIONS.md`):
 * правила не отсеивают, роль присуждает слой поверх них.
 *
 * Разделитель — **только двоеточие**. Первый вариант правила допускал и точку, и тест на нём
 * упал: `30.03` — это дата с кадра посылки, а не «тридцать часов три минуты». Здешние экраны
 * пишут время через двоеточие, дату — через точку, и путать их дороже, чем упустить редкое
 * `9.05`.
 */
internal val BARE_CLOCK = Regex("""\d{1,2}:\d{2}(\s*[AaPp][Mm])?""")

/** Отметка времени, а не дата документа: см. [BARE_CLOCK]. Для прочих типов всегда `false`. */
fun Entity.isBareClock(): Boolean =
    type == EntityType.DATE_TIME && BARE_CLOCK.matches(value.trim())

/**
 * Телефон ли это, или кусок чего-то большего, что просто совпало по длине.
 *
 * Живой случай (#240): на скриншоте переписки первые двенадцать цифр номера карты уехали в
 * «Телефон» с иконкой звонка — рядом с той же картой, правильно замаскированной до «•• 2632».
 * Маскировка работала; номер утёк мимо неё обрезком.
 *
 * Проверка строгая: последовательность должна быть **длиннее**, иначе число всегда содержало бы
 * само себя. Смотрим только телефоны — почта и ссылка обрезками не бывают.
 */
private fun Entity.isFragmentOf(numbers: List<String>): Boolean {
    if (type != EntityType.PHONE) return false
    val digits = value.filter(Char::isDigit)
    if (digits.isEmpty()) return false
    return numbers.any { it.length > digits.length && it.contains(digits) }
}

fun Entity.isPlausible(): Boolean = when (type) {
    // A real phone is 10–13 significant digits — shorter is a fragment, 14+ is a
    // waybill/account number, not something you dial.
    EntityType.PHONE -> value.count(Char::isDigit) in 10..13
    // A real address carries a name or number, not just an abbreviation like «г.»/«ул.».
    EntityType.ADDRESS -> value.trim().length >= 5 && Regex("""\p{L}{3,}""").containsMatchIn(value)
    // Даты здесь не судятся: голое время — тоже улика, оно лишь проигрывает роль «Дата
    // документа» календарной части. См. [isBareClock] и порядок фактов в `entityDelta`.
    else -> true
}

/** The one entity→feature mapping, shared by every enricher that flags entities.
 *  URL is deliberately absent (flagged by the head-peek/regex path); MONEY has no action yet. */
fun EntityType.asFeature(): Feature? = when (this) {
    EntityType.PHONE -> Feature.HAS_PHONE
    EntityType.EMAIL -> Feature.HAS_EMAIL
    EntityType.ADDRESS -> Feature.HAS_ADDRESS
    EntityType.DATE_TIME -> Feature.HAS_DATE
    EntityType.PAYMENT_CARD -> Feature.HAS_CARD
    EntityType.URL, EntityType.MONEY -> null
}

/**
 * The extracted kind this entity becomes as a graph object (#222), or null when it never does.
 *
 * The inverse of what the entity enricher builds, and the reason an action on a found object does
 * not have to re-run a model: an `Address` object already IS the address. `PAYMENT_CARD` has no
 * kind on purpose — a card number is masked on screen and never promoted to an object.
 */
fun EntityType.asExtractedKind(): com.point.core.model.ObjectKind? = when (this) {
    EntityType.PHONE -> KIND_PHONE
    EntityType.EMAIL -> KIND_EMAIL
    EntityType.URL -> KIND_URL
    EntityType.ADDRESS -> KIND_ADDRESS
    EntityType.DATE_TIME -> KIND_DATE
    EntityType.PAYMENT_CARD, EntityType.MONEY -> null
}

/** Metadata key prefix for *understood facts* — the first value found per entity kind
 *  (`entity.phone` → «+380…»). The «Point понял» checklist (#114) renders these, so the
 *  screen can say "Нашёл телефон +380…", not just "PHONE". */
const val META_ENTITY_PREFIX = "entity."

/** The metadata key an entity's first value is kept under; null = not a shown fact. */
fun EntityType.asMetaKey(): String? = when (this) {
    EntityType.PHONE -> META_ENTITY_PREFIX + "phone"
    EntityType.EMAIL -> META_ENTITY_PREFIX + "email"
    EntityType.ADDRESS -> META_ENTITY_PREFIX + "address"
    EntityType.DATE_TIME -> META_ENTITY_PREFIX + "date"
    EntityType.PAYMENT_CARD -> META_ENTITY_PREFIX + "card"
    EntityType.URL -> META_ENTITY_PREFIX + "url"
    EntityType.MONEY -> null
}
