package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * На чеке главное число — итог (#1006).
 *
 * Из чека прочитаны все пять сумм, ошибки распознавания нет вовсе, — а под галочкой стояла
 * первая по порядку, `SUBTOTAL 2.00`. Итог `2.18`, ради которого чек и снимают, лежал третьим
 * в «ещё» и на экран не выходил. Галочка при этом утверждает уверенность.
 */
class ReceiptShowsItsTotalTest {

    private val familyDollar = """
        FAMILY DOLLAR
        COCA COLA 1.25 LTR
        SUBTOTAL  ${'$'}2.00
        TAX1      ${'$'}0.18
        TOTAL     ${'$'}2.18
        CASH      ${'$'}2.25
        CHANGE    ${'$'}0.07
    """.trimIndent()

    @Test
    fun `под галочкой стоит итог, а не подытог`() {
        val facts = amountFacts(familyDollar)

        assertEquals("2.18", facts[META_ENTITY_AMOUNT])
    }

    @Test
    fun `прочие суммы никуда не деваются`() {
        val more = altLines(amountFacts(familyDollar)[META_ENTITY_AMOUNT + META_MORE_SUFFIX].orEmpty())

        assertEquals(listOf("2.00", "0.18", "2.18", "2.25", "0.07"), more)
    }

    /** «Подытог» итогом не становится: слово «total» внутри него ничего не решает. */
    @Test
    fun `один подытог итогом не назначается`() {
        val onlySubtotal = """
            Товар А  10.00 грн
            SUBTOTAL 10.00 грн
            SUBTOTAL 12.00 грн
        """.trimIndent()

        assertEquals("10.00", amountFacts(onlySubtotal)[META_ENTITY_AMOUNT])
    }

    /** Подписи итога нет — выбор наугад не делается, порядок остаётся прежним. */
    @Test
    fun `без подписи итога первым остаётся первое число`() {
        val plain = """
            Оплата 500 грн
            Комиссия 25 грн
        """.trimIndent()

        assertEquals("500", amountFacts(plain)[META_ENTITY_AMOUNT])
    }
}
