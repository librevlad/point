package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the document engine, run against **what the device actually produces**.
 *
 * Every other test in this engine feeds it text a human typed. That is how three of the four
 * things шаг 3–5 promised turned out not to happen on a real parcel screenshot: the vocabulary
 * was written the way Ukrainian is spelled, and Tesseract does not spell it that way.
 *
 * The fixtures in `src/test/resources/ocr` are verbatim OCR output pulled off a Samsung A34 on
 * 2026-07-30 — four Nova Poshta screens and two chats. Nothing in them is cleaned up; the
 * mangling (`Мсця`, `Вдчинено`, `Micue`) is the point.
 */
class RealOcrTest {

    private fun ocr(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/ocr/$name.txt")) { "нет образца $name" }
            .bufferedReader().readText()

    private val parcels = listOf("parcel_1", "parcel_2", "parcel_3", "parcel_4")
    private val notParcels = listOf("neg_viber", "neg_whatsapp")

    // --- Тип документа (#222, шаг 5) ---

    @Test
    fun `every real parcel screen is recognised as a parcel`() {
        parcels.forEach { name ->
            assertEquals("«$name» должен быть посылкой", TYPE_PARCEL, documentType(ocr(name)))
        }
    }

    @Test
    fun `a chat is not a parcel, even when a waybill number is in it`() {
        // neg_viber is a conversation about a shipment: the ТТН is genuinely there and must be
        // extracted, but calling the chat itself «Посылка» would be exactly the confident lie
        // the rule exists to avoid.
        notParcels.forEach { name ->
            assertNull("«$name» не посылка", documentType(ocr(name)))
        }
    }

    @Test
    fun `the vocabulary matched nothing before folding away the letter OCR eats`() {
        // The regression that started all this: `відділення` comes back as `вддлення`, `місце`
        // as `мсце`. Spelled-correctly markers scored zero on all four real screens.
        val spelled = listOf("відділення", "місце доставки", "зберігання")

        parcels.forEach { name ->
            val raw = ocr(name).lowercase()
            assertTrue(
                "«$name»: если OCR вдруг стал писать «і», свёртку можно упрощать",
                spelled.none { it in raw },
            )
        }
    }

    // --- Номер накладной (#222, шаг 3) ---

    @Test
    fun `the waybill is found on the screens that carry one`() {
        assertEquals(listOf("20451491549395"), waybillNumbers(ocr("parcel_1")))
        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers(ocr("parcel_2")))
        assertEquals(listOf("20 4514 0308 6865"), waybillNumbers(ocr("parcel_4")))
    }

    @Test
    fun `a waybill quoted in a chat is still a waybill`() {
        assertEquals(listOf("20451491549395"), waybillNumbers(ocr("neg_viber")))
    }

    @Test
    fun `a screen without a number yields none`() {
        // parcel_3 is the map view — no number on it, and the OCR of it is barely readable.
        assertTrue(waybillNumbers(ocr("parcel_3")).isEmpty())
        assertTrue(waybillNumbers(ocr("neg_whatsapp")).isEmpty())
    }

    @Test
    fun `a card number in a chat is not mistaken for a waybill`() {
        // neg_viber also contains «5169 3351 0965 2632» — sixteen digits, a card.
        assertTrue(waybillNumbers(ocr("neg_viber")).none { it.filter(Char::isDigit).length != 14 })
    }

    // --- Часы статус-бара (#233) ---

    @Test
    fun `the status bar is dropped from every screenshot that has one`() {
        listOf("parcel_1", "parcel_2", "parcel_4", "neg_viber", "neg_whatsapp").forEach { name ->
            val raw = ocr(name)
            val clean = stripStatusBar(raw)

            assertTrue("«$name»: строка статус-бара должна исчезнуть", clean.length < raw.length)
            assertTrue(
                "«$name»: время из статус-бара не должно остаться первой строкой",
                !Regex("""^\s*\d{1,2}[:.]\d{2}""").containsMatchIn(clean.lineSequence().first()),
            )
        }
    }

    @Test
    fun `text that merely starts with a time keeps it`() {
        // The rule must not eat a real line. «15:12 Встреча с Петром» is content.
        val note = "15:12 Встреча с Петром\nвторой этаж"

        assertEquals(note, stripStatusBar(note))
    }

    @Test
    fun `a screen whose OCR lost the clock entirely is left alone`() {
        // parcel_3's first line is unreadable noise — nothing to strip, and nothing to guess at.
        assertEquals(ocr("parcel_3"), stripStatusBar(ocr("parcel_3")))
    }

    @Test
    fun `stripping never empties the text`() {
        (parcels + notParcels).forEach { name ->
            assertTrue("«$name» не должен опустеть", stripStatusBar(ocr(name)).isNotBlank())
        }
    }

    // --- Что теперь увидит владелец на своём скриншоте ---

    @Test
    fun `the owner's parcel screenshot yields a name, a number and no phone clock`() {
        val text = stripStatusBar(ocr("parcel_1"))

        assertEquals(TYPE_PARCEL, documentType(text))
        assertEquals(listOf("20451491549395"), waybillNumbers(text))
        assertNotNull(documentLabel(documentType(text)))
        assertTrue("часы 15:12 больше не входят в текст", !text.contains("15:12"))
    }
}
