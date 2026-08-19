package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Виток говорит, что прибавилось (#1176, дословно владелец: «не забудь это выводить в ui
 * чтоб знание не терялось в графе»): знание, осевшее только в графе, для человека
 * не случилось.
 */
class SpiralDeltaTest {

    private val phone = META_ENTITY_PREFIX + "phone"
    private val card = META_ENTITY_PREFIX + "card"
    private val meter = META_ENTITY_PREFIX + "meter"

    @Test fun `новое названо по-человечески`() {
        val delta = spiralDelta(emptyMap(), mapOf(phone to "+380671234567", card to "5169 3351"))!!

        assertTrue(delta.contains("Телефон") || delta.contains("телефон"))
        assertTrue(delta.contains("Карта") || delta.contains("карта"))
    }

    @Test fun `уточнённое отличено от нового`() {
        val delta = spiralDelta(
            mapOf(phone to "+38067123456"),
            mapOf(phone to "+380671234567"),
        )!!

        assertTrue("уточнение выдано за находку: $delta", delta.contains("точнено"))
    }

    @Test fun `подтверждение согласием видно человеку`() {
        val delta = spiralDelta(
            mapOf(meter to "20842", meter + META_EVIDENCE_SUFFIX to ""),
            mapOf(meter to "20842", meter + META_EVIDENCE_SUFFIX to AGREE_MARK + "gemini," + AGREE_MARK + "groq"),
        )!!

        assertTrue(delta.contains("одтверждено"))
    }

    @Test fun `новый спор назван спором`() {
        val delta = spiralDelta(
            mapOf(meter to "20842"),
            mapOf(meter to "20842", meter + META_ALT_SUFFIX to "20843"),
        )!!

        assertTrue(delta.contains("спорят"))
    }

    @Test fun `без прироста дельте сказать нечего`() {
        assertNull(spiralDelta(mapOf(phone to "+380671234567"), mapOf(phone to "+380671234567")))
        assertNull(spiralDelta(emptyMap(), mapOf("investigated.understand" to "found")))
    }

    @Test fun `безымянное знание строкой не выходит`() {
        assertNull(spiralDelta(emptyMap(), mapOf("some.internal" to "x")))
        assertEquals(
            null,
            spiralDelta(emptyMap(), mapOf(META_SEMANTIC_SUMMARY + META_ACTOR_SUFFIX to "gemini")),
        )
    }
}
