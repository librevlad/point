package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сводка приватности понимается с первого чтения (#1003).
 *
 * Тумблер «Отправка в облако» и уровень «Куда можно отправлять» — два разных параметра, и
 * слитые в одну фразу они читались противоречием: «Облако разрешено · Только на этом
 * устройстве». Если только на этом устройстве — в каком смысле разрешено?
 */
class PrivacySummaryTest {

    private val allowed = "Облако разрешено"

    @Test
    fun `строгий уровень не обещает облака`() {
        val line = privacySummary(allowed, cloudAllowed = true, level = PrivacyLevel.DEVICE_ONLY)

        assertFalse("сводка обещает облако там, где наружу не уходит ничего: $line", line.contains("разрешено"))
        assertEquals(PrivacyLevel.DEVICE_ONLY.title, line)
    }

    @Test
    fun `разрешённое облако видно вместе с уровнем`() {
        val line = privacySummary(allowed, cloudAllowed = true, level = PrivacyLevel.NO_TRAINING)

        assertTrue(line.contains(allowed))
        assertTrue(line.contains(PrivacyLevel.NO_TRAINING.title))
    }

    /** Выключенное облако противоречия не создаёт: слова платформы остаются как были. */
    @Test
    fun `выключенное облако называется своими словами`() {
        val off = "Облако выключено"
        val line = privacySummary(off, cloudAllowed = false, level = PrivacyLevel.DEVICE_ONLY)

        assertTrue(line.contains(off))
        assertTrue(line.contains(PrivacyLevel.DEVICE_ONLY.title))
    }
}
