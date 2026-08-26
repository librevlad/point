package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealOcrTest {

    private fun ocr(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/ocr/$name.txt")) { "нет образца $name" }
            .bufferedReader().readText()

    private val parcels = listOf("parcel_1", "parcel_2", "parcel_3", "parcel_4")
    private val notParcels = listOf("neg_viber", "neg_whatsapp")

    private val numberInViber = "20451491549395"

    @Test
    fun `every real parcel screen is recognised as a parcel`() {
        parcels.forEach { name ->
            assertEquals("«$name» должен быть посылкой", TYPE_PARCEL, documentType(ocr(name)))
        }
    }

    @Test
    fun `a chat is not a parcel, even when a waybill number is in it`() {

        notParcels.forEach { name ->
            assertNull("«$name» не посылка", documentType(ocr(name)))
        }
    }

    @Test
    fun `the vocabulary matched nothing before folding away the letter OCR eats`() {

        val spelled = listOf("відділення", "місце доставки", "зберігання")

        parcels.forEach { name ->
            val raw = ocr(name).lowercase()
            assertTrue(
                "«$name»: если OCR вдруг стал писать «і», свёртку можно упрощать",
                spelled.none { it in raw },
            )
        }
    }

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

        assertTrue(waybillNumbers(ocr("parcel_3")).isEmpty())
        assertTrue(waybillNumbers(ocr("neg_whatsapp")).isEmpty())
    }

    @Test
    fun `a card number in a chat is not mistaken for a waybill`() {

        assertTrue(waybillNumbers(ocr("neg_viber")).none { it.filter(Char::isDigit).length != 14 })
    }

    @Test
    fun `слово-маркер на соседней строке — сосед номера`() {
        val text = ocr("neg_viber")
        val at = text.indexOf(numberInViber).let { it until it + numberInViber.length }

        assertTrue("«ТТН» стоит соседней строкой и обязано считаться соседом", markerNear(text, at))

        assertEquals(numberInViber, text.lineSequence().first { numberInViber in it }.trim())
    }

    @Test
    fun `ведомость владельца номеров отправлений не рождает`() {

        assertTrue(waybillNumbers(ocr("ledger_23")).isEmpty())
    }

    @Test
    fun `суммы переписки читаются дословным выводом устройства, а карта суммой не становится`() {
        val amounts = moneyAmounts(ocr("neg_viber"))

        assertEquals(listOf("320", "300"), amounts.map { it.value })
        assertEquals(listOf("грн", "грн"), amounts.map { it.currency })
    }

    @Test
    fun `экран посылки и ведомость денег не рождают`() {

        (parcels + "ledger_23").forEach { name ->
            assertTrue("«$name» не про деньги", amountFacts(ocr(name)).isEmpty())
        }
    }

    @Test
    fun `слово чека и слипшийся ноль суммой не становятся и на дословном чтении (#1059)`() {
        // Чек Family Dollar карточки #1059, снятый устройством 24.08.2026: строки слиплись,
        // числа оторвались от подписей — «TAX1 0», «CASH TOTAL 0». Правило страницы на такой
        // странице честно молчит: сумм, которые можно прочитать, здесь нет вовсе. Ни подпись,
        // ни ноль вместо суммы не встают — иначе человек увидел бы под галочкой «TAX1».
        val facts = amountFacts(ocr("receipt_family_dollar"))

        assertNull(facts[META_ENTITY_AMOUNT])
        assertTrue(moneyAmounts(ocr("receipt_family_dollar")).isEmpty())
    }

    @Test
    fun `сумма перевода читается там, где валюты на странице нет вовсе`() {
        val facts = amountFacts(ocr("chat_calc"))

        assertEquals("1048,64", facts[META_ENTITY_AMOUNT])
        assertEquals("arithmetic", facts[META_ENTITY_AMOUNT + META_EVIDENCE_SUFFIX])

        assertEquals(altValue(listOf("1048,64", "548,64")), facts[META_ENTITY_AMOUNT + META_MORE_SUFFIX])
    }

    @Test
    fun `порченые часы переписки суммами не становятся`() {

        val text = ocr("chat_calc")

        assertEquals(listOf("1048,64", "548,64"), arithmeticTotals(text))

        assertTrue(moneyAmounts(text).isEmpty())
    }

    @Test
    fun `на странице квитанции читается ровно один номер`() {

        val text = ocr("receipt_paper")

        assertEquals(listOf("AB12-CD34-EF56-GH78"), receiptNumbers(text))
        assertEquals("500.00", amountFacts(text)[META_ENTITY_AMOUNT])
        assertEquals("грн", amountFacts(text)[META_ENTITY_AMOUNT_CURRENCY])
    }

    @Test
    fun `квитанция посылкой не становится`() {

        val text = ocr("receipt_paper")

        assertNull(documentType(text))
        assertTrue(waybillNumbers(text).isEmpty())
    }

    @Test
    fun `номеров квитанций на этих кадрах нет ни одного`() {

        (parcels + notParcels + "ledger_23").forEach { name ->
            assertTrue("«$name» не квитанция", receiptFacts(ocr(name)).isEmpty())
        }
    }

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

        val note = "15:12 Встреча с Петром\nвторой этаж"

        assertEquals(note, stripStatusBar(note))
    }

    @Test
    fun `a screen whose OCR lost the clock entirely is left alone`() {

        assertEquals(ocr("parcel_3"), stripStatusBar(ocr("parcel_3")))
    }

    @Test
    fun `stripping never empties the text`() {
        (parcels + notParcels).forEach { name ->
            assertTrue("«$name» не должен опустеть", stripStatusBar(ocr(name)).isNotBlank())
        }
    }

    @Test
    fun `the owner's parcel screenshot yields a name, a number and no phone clock`() {
        val text = stripStatusBar(ocr("parcel_1"))

        assertEquals(TYPE_PARCEL, documentType(text))
        assertEquals(listOf("20451491549395"), waybillNumbers(text))
        assertNotNull(documentLabel(documentType(text)))
        assertTrue("часы 15:12 больше не входят в текст", !text.contains("15:12"))
    }

    @Test
    fun `страница-каша не судит числа ведомости`() {
        val page = AtomLayer(emptyList(), ocr("ledger_23"))
        val ledger = listOf(
            listOf("11004", "6,003", "0,522", "2,088", "0,173", "2,871", "0,261", "1,305"),
            listOf("11006", "0,883", "0,077", "0,307", "0,038", "0,422", "0,038", "0,192"),
            listOf("11008", "1,994", "0,173", "0,694", "0,038", "0,954", "0,087", "0,434"),
        ).map { row -> row.map { CellAnswer.Literal(it) } }

        val table = page.resolveCells(ledger)

        assertTrue("пометки нет ни на одном числе", table.rows.flatten().none { it.contains("⚠") })

        val alone = page.resolveCells(listOf(listOf(CellAnswer.Literal("6,003"))))
        assertEquals("6,003⚠", alone.rows[0][0])
    }
}
