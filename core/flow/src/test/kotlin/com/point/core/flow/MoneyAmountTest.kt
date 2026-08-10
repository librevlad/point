package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyAmountTest {

    @Test
    fun `число рядом с валютой — сумма, и валюта живёт своим ключом`() {
        val facts = amountFacts("Один комплект стоит 320 грн")

        assertEquals("320", facts[META_ENTITY_AMOUNT])
        assertEquals("грн", facts[META_ENTITY_AMOUNT_CURRENCY])
    }

    @Test
    fun `валюта переносом строки не отрывается от числа`() {

        assertEquals("320", amountFacts("Один комплект стоит 320\nгрн")[META_ENTITY_AMOUNT])
    }

    @Test
    fun `цена и сумма к переводу обе видны — вторая в «или»`() {

        val facts = amountFacts("Один комплект стоит 320 грн\nостаток скиньте на карту\n300 грн")

        assertEquals("320", facts[META_ENTITY_AMOUNT])
        assertEquals(altValue(listOf("320", "300")), facts[META_ENTITY_AMOUNT + META_MORE_SUFFIX])
    }

    @Test
    fun `валюта перед числом — подпись колонки квитанции`() {

        val facts = amountFacts("Сума (грн)      500.00\nКомісія (грн)      0.00")

        assertEquals("500.00", facts[META_ENTITY_AMOUNT])
        assertEquals("грн", facts[META_ENTITY_AMOUNT_CURRENCY])
    }

    @Test
    fun `нулевая комиссия не становится суммой документа (#662)`() {
        // Прогон 2026-08-09, кадр 20: «сумма — ещё: 0.00» — комиссия-ноль стояла рядом
        // с настоящим платежом. Ноль ничего не говорит о деньгах, ради которых открыли объект.
        val facts = amountFacts("Сума (грн)      500.00\nКомісія (грн)      0.00")

        assertEquals(null, facts[META_ENTITY_AMOUNT + META_MORE_SUFFIX])
        assertEquals(listOf("500.00"), moneyAmounts("Сума 500.00 грн\nКомісія 0.00 грн").map { it.value })
    }

    @Test
    fun `ноль не становится суммой и в одиночестве (#662)`() {
        assertTrue(moneyAmounts("Комісія 0.00 грн").isEmpty())
        assertTrue(moneyAmounts("0 грн").isEmpty())
    }

    @Test
    fun `разрядный пробел — часть числа, а не граница`() {

        assertEquals("4 020.10", amountFacts("Платіж надіслано 4 020.10 ₴")[META_ENTITY_AMOUNT])
    }

    @Test
    fun `перенос строки разрядным пробелом не бывает — время сверху в сумму не приклеивается`() {

        assertEquals("300", amountFacts("10:03\n300 грн")[META_ENTITY_AMOUNT])
        assertEquals(listOf("548,64"), moneyAmounts("18:54\n548,64 грн").map { it.value })
        assertEquals(listOf("300"), moneyAmounts("Всього 12\n300 грн").map { it.value })

        assertTrue(moneyAmounts("10:03\n300 грн").none { '\n' in it.value })
    }

    @Test
    fun `сумма первой строкой не теряется из-за цифры в конце предыдущей`() {

        assertEquals("500", amountFacts("Код банку 322001\n500 грн")[META_ENTITY_AMOUNT])
        assertEquals(listOf("300"), moneyAmounts("5169 3351 0965 2632\n300 грн").map { it.value })
        assertEquals(listOf("500.00"), moneyAmounts("Комісія 0.00\n500.00 грн").map { it.value })
    }

    @Test
    fun `дата и время под подписью колонки суммой не становятся`() {

        assertTrue(moneyAmounts("Сума (грн)\n26.04.2026").isEmpty())
        assertTrue(moneyAmounts("Всього, грн\n20.01.1994 р.").isEmpty())
        assertTrue(moneyAmounts("Ціна, грн\n10:03").isEmpty())

        assertEquals("500.00", amountFacts("Сума (грн)\n500.00")[META_ENTITY_AMOUNT])
    }

    @Test
    fun `сумма прописью нулём не становится`() {

        assertTrue(amountFacts("Сума літерами п'ятсот гривень 00 копійок").isEmpty())
    }

    @Test
    fun `номер карты обрезком суммы не становится`() {

        assertTrue(moneyAmounts("Сплата в грн\n4111 1111 1111 1111").isEmpty())
        assertTrue(moneyAmounts("4111 1111 1111 1111 грн").isEmpty())
    }

    @Test
    fun `слишком длинное число суммой не считается`() {

        assertTrue(moneyAmounts("4111111111111111 грн").isEmpty())
        assertTrue(amountDigitsFit("999999999999"))
    }

    @Test
    fun `улика ровно одна, происхождение — прочитано`() {
        val facts = amountFacts("До сплати 1048,64 грн")

        assertEquals(Provenance.OCR.wire, facts[META_ENTITY_AMOUNT + META_SOURCE_SUFFIX])
        assertEquals("semantic", facts[META_ENTITY_AMOUNT + META_EVIDENCE_SUFFIX])
    }

    @Test
    fun `итог расчёта читается и без валюты — на кадре её нет вовсе`() {

        val facts = amountFacts("127*4.32=548,64\n500+548,64=1048,64")

        assertEquals("1048,64", facts[META_ENTITY_AMOUNT])
        assertEquals(Provenance.OCR.wire, facts[META_ENTITY_AMOUNT + META_SOURCE_SUFFIX])
    }

    @Test
    fun `промежуточный итог сумму перевода не подменяет`() {

        assertEquals(listOf("1048,64", "548,64"), arithmeticTotals("127*4.32=548,64\n500+548,64=1048,64"))
        assertEquals(
            altValue(listOf("1048,64", "548,64")),
            amountFacts("127*4.32=548,64\n500+548,64=1048,64")[META_ENTITY_AMOUNT + META_MORE_SUFFIX],
        )
    }

    @Test
    fun `улика итога — арифметика, и она ровно одна`() {

        val facts = amountFacts("500+548,64=1048,64")

        assertEquals("arithmetic", facts[META_ENTITY_AMOUNT + META_EVIDENCE_SUFFIX])
        assertTrue("человек обязан видеть «возможно»", isAssumption(facts, META_ENTITY_AMOUNT))

        assertTrue(META_ENTITY_AMOUNT_CURRENCY !in facts)
    }

    @Test
    fun `расчёт, который не сходится, итога не даёт`() {

        assertTrue(arithmeticTotals("127*4.32=500,00").isEmpty())
        assertTrue(arithmeticTotals("500+548,64=1048,00").isEmpty())
        assertTrue(amountFacts("2+2=5").isEmpty())
    }

    @Test
    fun `лишние знаки расчёта итогу не мешают — это одно и то же число`() {

        assertEquals(listOf("548,64"), arithmeticTotals("127*4.32=548,64"))
        assertEquals(listOf("274,32"), arithmeticTotals("548,64/2=274,32"))
    }

    @Test
    fun `округление доведено до копеек и дальше не идёт`() {

        assertTrue(arithmeticTotals("1/2=1").isEmpty())
        assertTrue(arithmeticTotals("5:2=3").isEmpty())
        assertTrue(arithmeticTotals("99/100=1").isEmpty())
        assertTrue(amountFacts("2/3=1").isEmpty())

        assertEquals(listOf("0,33"), arithmeticTotals("1/3=0,33"))
        assertEquals(listOf("69,97"), arithmeticTotals("12,34*5,67=69,97"))
    }

    @Test
    fun `итог, у которого цифр как у счёта, суммой не становится`() {

        val huge = "5 169 335 109 652 632"

        assertEquals(false, amountDigitsFit(huge))
        assertTrue(arithmeticTotals("$huge+0=$huge").isEmpty())
        assertTrue(amountFacts("$huge+0=$huge").isEmpty())

        assertEquals(listOf("1 048,64"), arithmeticTotals("500+548,64=1 048,64"))
    }

    @Test
    fun `знак действия читается так, как его пишут люди`() {

        assertEquals(listOf("400"), arithmeticTotals("500-100=400"))
        assertEquals(listOf("6"), arithmeticTotals("2х3=6"))
        assertEquals(listOf("6"), arithmeticTotals("2×3=6"))
    }

    @Test
    fun `таймстемп за итогом расчёта его не отменяет`() {

        assertEquals("1048,64", amountFacts("500+548,64=1048,64 4...")[META_ENTITY_AMOUNT])
    }

    @Test
    fun `валюта сильнее расчёта — страница, назвавшая свои деньги, судится ею`() {
        val facts = amountFacts("Один комплект стоит 320 грн\n2*3=6")

        assertEquals("320", facts[META_ENTITY_AMOUNT])
        assertEquals("semantic", facts[META_ENTITY_AMOUNT + META_EVIDENCE_SUFFIX])
    }

    @Test
    fun `названная цена — правило не знает, что расчёт про деньги`() {

        assertEquals("6", amountFacts("Плитка 2*3=6 рядов")[META_ENTITY_AMOUNT])

        val amount = ACTION_SCHEMAS.single { it.id == "pay-by-requisites" }.fields
            .single { it.key == META_ENTITY_AMOUNT }
        assertEquals(false, amount.anchor)
    }

    @Test
    fun `чужие числа страницы деньгами не становятся`() {
        assertTrue(amountFacts("ТТН 20 4514 9154 9395 прибула").isEmpty())
        assertTrue(amountFacts("Показання 20842 кВт·ч").isEmpty())
        assertTrue(amountFacts("Позвони на +380671234567").isEmpty())
        assertTrue(amountFacts("").isEmpty())
    }

    @Test
    fun `одно и то же число с той же валютой — одна сумма`() {
        val facts = amountFacts("Сума 500 грн\nВсього 500 грн")

        assertEquals("500", facts[META_ENTITY_AMOUNT])
        assertTrue(META_ENTITY_AMOUNT + META_MORE_SUFFIX !in facts)
    }

    @Test
    fun `форму суммы судит одна функция — и правило, и чтение модели`() {

        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "300"))
        assertEquals(false, semanticFits(META_ENTITY_AMOUNT, "4111111111111111"))
    }
}
