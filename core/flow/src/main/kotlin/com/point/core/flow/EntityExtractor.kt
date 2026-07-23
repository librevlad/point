package com.point.core.flow

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
