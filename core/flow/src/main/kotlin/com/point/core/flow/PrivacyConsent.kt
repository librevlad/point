package com.point.core.flow

/**
 * Consent to send the user's object to a cloud service. Cloud actions (AI, Перевести,
 * В Excel — anything with [CapabilityMeta.network]) must not leave the device before
 * the user has agreed once. This is a store-policy requirement and a trust matter,
 * not a nicety: without consent, nothing is uploaded.
 */
interface PrivacyConsent {
    suspend fun cloudAllowed(): Boolean
    suspend fun allowCloud()
}
