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
fun plausibleEntities(entities: List<Entity>): List<Entity> = entities.filter { it.isPlausible() }

fun Entity.isPlausible(): Boolean = when (type) {
    // A real phone is 10–13 significant digits — shorter is a fragment, 14+ is a
    // waybill/account number, not something you dial.
    EntityType.PHONE -> value.count(Char::isDigit) in 10..13
    // A real address carries a name or number, not just an abbreviation like «г.»/«ул.».
    EntityType.ADDRESS -> value.trim().length >= 5 && Regex("""\p{L}{3,}""").containsMatchIn(value)
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
