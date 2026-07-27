package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-device feedback (2026-07-27): on OCR'd documents ML Kit mis-flags a chunk of a
 * waybill (ТТН) number as a PHONE and a bare «г.» as an ADDRESS. A plausibility filter
 * keeps the useful hits and drops the noise so «Позвонить»/«Открыть на карте» don't
 * appear on a food-ration slip.
 */
class EntityPlausibilityTest {

    private fun phone(v: String) = Entity(EntityType.PHONE, v)
    private fun address(v: String) = Entity(EntityType.ADDRESS, v)

    @Test
    fun `real phones pass, waybill fragments and over-long digit runs are rejected`() {
        assertTrue(phone("+380 67 123 45 67").isPlausible())   // 12 digits
        assertTrue(phone("0671234567").isPlausible())          // 10 digits, local
        assertTrue(phone("+7 999 123-45-67").isPlausible())    // 11 digits
        assertFalse(phone("4507 1234").isPlausible())          // 8 digits — a ТТН chunk
        assertFalse(phone("20450712345678").isPlausible())     // 14 digits — a full waybill
    }

    @Test
    fun `real addresses pass, bare abbreviations are rejected`() {
        assertTrue(address("г. Киев, ул. Крещатик 12").isPlausible())
        assertTrue(address("Москва, Тверская 7").isPlausible())
        assertFalse(address("г.").isPlausible())
        assertFalse(address("ул.").isPlausible())
    }

    @Test
    fun `other entity types are never filtered`() {
        assertTrue(Entity(EntityType.EMAIL, "a@b.c").isPlausible())
        assertTrue(Entity(EntityType.DATE_TIME, "в пятницу").isPlausible())
        assertTrue(Entity(EntityType.PAYMENT_CARD, "4111111111111111").isPlausible())
    }

    @Test
    fun `plausibleEntities keeps the good and drops the noise`() {
        val filtered = plausibleEntities(
            listOf(phone("+380671234567"), phone("4507 1234"), address("г."), address("Львов, площадь Рынок 1")),
        )
        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.type == EntityType.PHONE })
        assertTrue(filtered.any { it.type == EntityType.ADDRESS })
    }
}
