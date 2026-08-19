package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Прицельный виток «Понять сильнее» (#1176, решение владельца): модель получает
 * накопленное знание и открытые вопросы — что уже есть, чего не нашли, что под
 * сомнением и что в споре, — и ищет недостающее, а не читает с чистого листа.
 */
class SpiralBriefTest {

    @Test fun `первый взгляд чист — брифу не из чего родиться`() {
        assertNull(spiralBrief(emptyMap()))
        assertNull(spiralBrief(mapOf("name" to "чек.jpg", "investigated.ocr" to "found")))
    }

    @Test fun `известное едет в бриф, ненайденное названо категориями`() {
        val brief = spiralBrief(
            mapOf(
                META_ENTITY_PREFIX + "phone" to "+380671234567",
                META_SEMANTIC_SUMMARY to "Визитка юриста",
            ),
        )!!

        assertTrue("известный телефон не назван: $brief", brief.contains("PHONE=+380671234567"))
        assertTrue("суть не названа: $brief", brief.contains("Визитка юриста"))
        assertTrue("ненайденная карта не спрошена: $brief", brief.contains("CARD"))
        assertFalse("найденный телефон попал в ненайденное: $brief", brief.contains("PHONE,") || brief.contains(", PHONE"))
    }

    @Test fun `спорные прочтения показаны оба — модель судит по объекту`() {
        val brief = spiralBrief(
            mapOf(
                META_ENTITY_PREFIX + "amount" to "1500",
                META_ENTITY_PREFIX + "amount" + META_ALT_SUFFIX to "7500",
            ),
        )!!

        assertTrue(brief.contains("1500"))
        assertTrue(brief.contains("7500"))
        assertTrue("спор не назван спором: $brief", brief.contains("спор"))
    }

    @Test fun `сомнение просит проверки, а человеческое слово — нет`() {
        val doubted = spiralBrief(
            mapOf(
                META_ENTITY_PREFIX + "meter" to "20842",
                META_ENTITY_PREFIX + "meter" + META_EVIDENCE_SUFFIX to "",
            ),
        )!!
        assertTrue("сомнение не спрошено: $doubted", doubted.contains("METER") && doubted.contains("сомнени"))

        val human = spiralBrief(
            mapOf(
                META_ENTITY_PREFIX + "meter" to "20842",
                META_ENTITY_PREFIX + "meter" + META_EVIDENCE_SUFFIX to "",
                META_ENTITY_PREFIX + "meter" + META_SOURCE_SUFFIX to com.point.core.model.Provenance.HUMAN.wire,
            ),
        )!!
        assertFalse("слово человека поставлено под сомнение: $human", human.contains("сомнени"))
    }

    @Test fun `бриф не ломает контракт — известное отдано теми же KEY`() {
        val brief = spiralBrief(mapOf(META_ENTITY_PREFIX + "card" to "5169 3351 0912 3456"))!!

        assertTrue(brief.contains("CARD=5169 3351 0912 3456"))
        assertEquals("бриф внезапно пуст", false, brief.isBlank())
    }
}
