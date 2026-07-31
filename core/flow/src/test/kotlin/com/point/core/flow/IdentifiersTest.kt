package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that stops a waybill number from falling through the floor (#222). ML Kit reads it as
 * a phone, [isPlausible] correctly drops it as undialable, and until now nobody picked it up.
 */
class IdentifiersTest {

    @Test
    fun `finds the waybill from a real Nova Poshta screenshot, spaces and all`() {
        // The number off the parcel screen that started this whole change.
        val text = "Прибула в пункт 1, Олексіївка\n20 4514 9154 9395\nОстанній день зберігання – 29.07"

        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers(text))
    }

    @Test
    fun `finds an ungrouped waybill too`() {
        assertEquals(listOf("20451491549395"), waybillNumbers("ТТН 20451491549395 прибула"))
    }

    @Test
    fun `a phone is not a waybill`() {
        // 12 digits. The two rules must not fight over the same string.
        assertTrue(waybillNumbers("тел. +380 67 123 45 67").isEmpty())
    }

    @Test
    fun `a card number is not a waybill`() {
        // 16 digits — PAYMENT_CARD has its own path and its own masking.
        assertTrue(waybillNumbers("4149 6293 1234 5678").isEmpty())
    }

    @Test
    fun `dates and small numbers are left alone`() {
        assertTrue(waybillNumbers("Invoice № 146 від 08.06.2026, 8 970.00 грн").isEmpty())
    }

    @Test
    fun `a longer digit run is not silently truncated to 14`() {
        // An account number must not be mistaken for a waybill just because it contains one.
        assertTrue(waybillNumbers("рахунок 202045149154939512").isEmpty())
    }

    @Test
    fun `the same number twice is reported once`() {
        val text = "20 4514 9154 9395 ... повторно 20 4514 9154 9395"

        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers(text))
    }

    @Test
    fun `does not run across a line break into a neighbouring number`() {
        // Newlines are not grouping — «1234567\n8901234567» is two numbers, not one waybill.
        assertTrue(waybillNumbers("1234567\n8901234567").isEmpty())
    }

    @Test
    fun `blank text yields nothing`() {
        assertTrue(waybillNumbers("").isEmpty())
        assertTrue(waybillNumbers("   \n  ").isEmpty())
    }

    @Test
    fun `the reading is marked structural, not verified`() {
        // No published check-digit algorithm went into the rule, and the pipeline must know:
        // consensus or a later validator is what raises this.
        assertTrue("structural match must stay below certainty", WAYBILL_CONFIDENCE < 1f)
    }

    // --- Трек как факт (#260): схема «Отследить отправление» читает entity.track ---

    @Test
    fun `трек становится фактом объекта с происхождением «прочитано»`() {
        assertEquals(
            mapOf(
                META_ENTITY_TRACK to "20 4514 9154 9395",
                META_ENTITY_TRACK + META_SOURCE_SUFFIX to SOURCE_OCR,
            ),
            trackFacts("ТТН 20 4514 9154 9395 прибула"),
        )
    }

    @Test
    fun `второй настоящий номер не прячется — все номера в more`() {
        // design v3 §8: «трек найден, но есть второй похожий» вместо ложной однозначности.
        // Именно .more, не .alt: это второй номер на странице, а не спор о чтении первого,
        // и подтверждение первого моделью его не стирает (ревью #260).
        val facts = trackFacts("20 4514 9154 9395 та 20451491549396")

        assertEquals("20 4514 9154 9395", facts[META_ENTITY_TRACK])
        assertEquals(
            listOf("20 4514 9154 9395", "20451491549396"),
            moreOf(facts, META_ENTITY_TRACK),
        )
        assertTrue(alternativesOf(facts, META_ENTITY_TRACK).isEmpty())
    }

    @Test
    fun `один номер в двух написаниях — один трек, а не «второй похожий»`() {
        // Шапка накладной и цифры под штрихкодом: строка разная, цифры те же (ревью #260 —
        // граф склеивал их в один узел, а карточка готовности показывала ложный спор).
        val facts = trackFacts("20 4514 9154 9395\nпід штрихкодом: 20451491549395")

        assertEquals("20 4514 9154 9395", facts[META_ENTITY_TRACK])
        assertTrue(moreOf(facts, META_ENTITY_TRACK).isEmpty())
        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers("20 4514 9154 9395 і 20451491549395"))
    }

    @Test
    fun `нет трека — нет ключей, а не ключ с пустотой`() {
        assertTrue(trackFacts("Позвони на +380671234567").isEmpty())
    }
}
