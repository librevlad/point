package com.point.core.flow

import com.point.core.model.Feature

data class Entity(val type: EntityType, val value: String)

enum class EntityType { PHONE, EMAIL, URL, ADDRESS, DATE_TIME, PAYMENT_CARD, MONEY }

interface EntityExtractor {
    suspend fun extract(text: String): List<Entity>
}

fun plausibleEntities(entities: List<Entity>, sourceText: String = ""): List<Entity> {
    val numbers = numberRuns(sourceText)
    return entities.filter { it.isPlausible() && !it.isFragmentOf(numbers) }
}

private fun numberRuns(text: String): List<String> =
    NUMBER_TOKEN.findAll(text).map { it.value.filter(Char::isDigit) }.toList()

private val NUMBER_TOKEN = Regex("""\d[\d \-]*\d|\d""")

internal val BARE_CLOCK = Regex("""\d{1,2}:\d{2}(\s*[AaPp][Mm])?""")

fun Entity.isBareClock(): Boolean =
    type == EntityType.DATE_TIME && BARE_CLOCK.matches(value.trim())

private fun Entity.isFragmentOf(numbers: List<String>): Boolean {
    if (type != EntityType.PHONE) return false
    val digits = value.filter(Char::isDigit)
    if (digits.isEmpty()) return false
    return numbers.any { it.length > digits.length && it.contains(digits) }
}

fun Entity.isPlausible(): Boolean = when (type) {

    EntityType.PHONE -> value.count(Char::isDigit) in 10..13

    EntityType.ADDRESS -> value.trim().length >= 5 && Regex("""\p{L}{3,}""").containsMatchIn(value)

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
