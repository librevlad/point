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

    @Test
    fun `сумма — число, слово чека и строка документа суммой не годятся (#1059)`() {
        // Живые факты чека Family Dollar: главной суммой встало слово «TAX1», а прежде —
        // «0» из слипшейся строки «TAX1 0». Строка документа с числом внутри — тоже не сумма.
        assertEquals(false, semanticFits(META_ENTITY_AMOUNT, "TAX1"))
        assertEquals(
            false,
            semanticFits(META_ENTITY_AMOUNT, "Line 001 order OR-01001 sum 101.01"),
        )
        assertEquals(false, semanticFits(META_ENTITY_AMOUNT, "0"))
        assertEquals(false, semanticFits(META_ENTITY_AMOUNT, "0.00"))
        assertEquals(false, semanticFits(META_ENTITY_AMOUNT, "CASH TOTAL"))

        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "2.18"))
        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "101.01"))
        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "\$2.18"))
        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "1 048,64 грн"))
        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "1,048.64"))
        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "12 500"))
        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "-2.00"))
    }

    @Test
    fun `арифметика — не сумма, одно число — сумма (#662)`() {
        // Прежде это судило отдельное правило выкладки; теперь — тот же судья формы.
        assertEquals(false, amountFits("127*4.32=548,64"))
        assertEquals(false, amountFits("500+548,64=1048,64"))
        assertEquals(false, amountFits("2500 320"))

        assertTrue(amountFits("2500"))
        assertTrue(amountFits("1 200,50"))
    }

    @Test
    fun `величина суммы — число с копейками после последнего разделителя`() {
        assertEquals(0, amountValue("1 048,64")!!.compareTo("1048.64".toBigDecimal()))
        assertEquals(0, amountValue("1,048.64")!!.compareTo("1048.64".toBigDecimal()))
        assertEquals(0, amountValue("1.048,64")!!.compareTo("1048.64".toBigDecimal()))
        assertEquals(0, amountValue("1,048")!!.compareTo("1048".toBigDecimal()))
        assertEquals(0, amountValue("2.18")!!.compareTo("2.18".toBigDecimal()))
        assertEquals(0, amountValue("\$2.18")!!.compareTo("2.18".toBigDecimal()))
        assertEquals(0, amountValue("-2.00")!!.compareTo("-2".toBigDecimal()))
        assertEquals(null, amountValue("TAX1"))
    }

    @Test
    fun `главная сумма чека — подписанный итог, а не наибольшее число (#1059)`() {
        // Наибольшее на этом чеке — «CASH 2.25», деньги, которые протянули кассиру;
        // заплачено 2.18, и это говорит подпись «CASH TOTAL», а не величина.
        val facts = amountFacts(familyDollar)

        assertEquals("2.18", facts[META_ENTITY_AMOUNT])
        assertEquals(
            altValue(listOf("2.00", "0.18", "2.18", "2.25", "0.07")),
            facts[META_ENTITY_AMOUNT + META_MORE_SUFFIX],
        )
    }

    @Test
    fun `цифра в подписи чужую валюту не забирает — в «TAX1» доллара нет`() {
        // Иначе «1» из «TAX1» уносила доллар следующего числа, и 0.18 пропадали вовсе.
        assertEquals(
            listOf("2.00", "0.18", "2.18", "2.25", "0.07"),
            moneyAmounts(familyDollar).map { it.value },
        )
    }

    @Test
    fun `без подписи итог — наибольшее из чисел (#1059)`() {

        assertEquals("2.18", amountFacts("Оплата \$2.00\nДоплата \$2.18")[META_ENTITY_AMOUNT])
    }

    @Test
    fun `подпись итога стоит и строкой выше числа — колонкой квитанции`() {
        val facts = amountFacts("Всього, грн\n500,00\nОтримано, грн\n1000,00")

        assertEquals("500,00", facts[META_ENTITY_AMOUNT])
    }

    @Test
    fun `валюта закрытым списком не заведует — «¥1200» такое же число (#1059)`() {
        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "¥1200"))
        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "250 ₹"))

        assertEquals("1200", amountFacts("Итого ¥1200")[META_ENTITY_AMOUNT])
    }

    @Test
    fun `минус перед валютой — тот же минус (#1059)`() {
        assertTrue(amountFits("-\$2.18"))
        assertEquals(0, amountValue("-\$2.18")!!.compareTo("-2.18".toBigDecimal()))
        assertEquals(0, amountValue("\$-2.18")!!.compareTo("-2.18".toBigDecimal()))
    }

    @Test
    fun `числа разных валют несравнимы — итога среди них нет (#1059)`() {
        // 5 € и 5 $ — не одно и то же, и большего среди них нет: остаётся первое названное.
        assertEquals("\$2.00", mainAmount(listOf("\$2.00", "€9.00")))

        assertEquals("€9.00", mainAmount(listOf("€2.00", "€9.00")))

        // Правило страницы спрашивает то же самое, хотя валюту держит отдельным ключом.
        val facts = amountFacts("Оплата \$5.00\nЗбір €9.00")

        assertEquals("5.00", facts[META_ENTITY_AMOUNT])
        assertEquals("\$", facts[META_ENTITY_AMOUNT_CURRENCY])
    }

    @Test
    fun `подпись ищут у самой суммы — число внутри числа это не она (#1059)`() {
        // «2.18» стоит и внутри «12.18» в строке товара. Иначе подпись итога искали бы там,
        // не нашли — и главной вставала бы цена товара, наибольшее число чека.
        val facts = amountFacts("ITEM \$12.18\nCASH TOTAL \$2.18\nCASH \$2.25")

        assertEquals("2.18", facts[META_ENTITY_AMOUNT])
    }

    @Test
    fun `скобки снимает воронка значения, а не судья формы (#1064)`() {
        // Судья формы говорит «нет» записи со скобками — и потому слово страницы «(2.18)»
        // не встаёт значением вместо чистого числа модели. Само число при этом не теряется:
        // обёртку снимает воронка, через которую кандидат и становится знанием.
        assertEquals(false, amountFits("(2.18)"))
        assertEquals("2.18", factCandidate(META_ENTITY_AMOUNT, "(2.18)"))
    }

    @Test
    fun `подпись итога ищется во всех строках с этим числом, а не в первой (#1059)`() {
        // Одно и то же число стоит на чеке несколько раз: цена единственного товара, подытог
        // и итог совпадают, а налог уже включён в цену. Осматривалась только первая такая
        // строка — строку «TOTAL» правило не читало вовсе, подписанных сумм не находило,
        // и главной суммой вставали наличные, которые протянули кассиру.
        assertEquals(
            "2,50",
            amountFacts("CAFE ROMA\nESPRESSO 2,50 EUR\nTOTAL 2,50 EUR\nBAR 5,00 EUR")[META_ENTITY_AMOUNT],
        )
        assertEquals(
            "2.18",
            amountFacts("DRINK \$2.18\nCASH TOTAL \$2.18\nCASH \$5.00\nCHANGE \$2.82")[META_ENTITY_AMOUNT],
        )
        assertEquals(
            "2.18",
            amountFacts("ITEM A \$2.18\nSUBTOTAL \$2.18\nTOTAL \$2.18\nCASH \$2.25")[META_ENTITY_AMOUNT],
        )
    }

    @Test
    fun `подпись говорит, чего именно итог — сэкономленное итогом не становится (#1059)`() {
        // «TOTAL SAVINGS» — сколько человек сэкономил, а не сколько заплатил. Слово «итог»
        // ловилось где угодно в строке, скидка получала подпись итога и как бо́льшая вставала
        // главной: под галочкой стояло число, которого человек не платил никому.
        val savings = """
            FAMILY DOLLAR
            SUBTOTAL ${'$'}7.18
            TOTAL SAVINGS ${'$'}5.00
            CASH TOTAL ${'$'}2.18
            CASH ${'$'}2.25
            CHANGE ${'$'}0.07
        """.trimIndent()

        assertEquals("2.18", amountFacts(savings)[META_ENTITY_AMOUNT])
        assertEquals("12.00", amountFacts("TOTAL TAX \$1.00\nTOTAL \$12.00\nCASH \$20.00")[META_ENTITY_AMOUNT])
        assertEquals(
            "12.00",
            amountFacts("TOTAL ITEMS 3\nTOTAL DISCOUNT \$5.00\nCASH TOTAL \$12.00")[META_ENTITY_AMOUNT],
        )

        // Подпись при слове, которое другой величины не называет, итог называет по-прежнему:
        // «TOTAL DUE» — тот же итог, и другой подписи на этом чеке нет.
        assertEquals("12.00", amountFacts("TOTAL DUE \$12.00\nCASH \$20.00")[META_ENTITY_AMOUNT])
    }

    @Test
    fun `одна валюта, записанная двумя способами, — та же валюта (#1059)`() {
        // Сравнивалось написание пометки: «₴» и «грн» считались разными валютами, правило
        // величины молча выключалось и возвращало «первое названное» — на квитанции со знаком
        // в шапке и словом в строках главной вставала не та сумма.
        assertEquals("1000 ₴", mainAmount(listOf("500 грн", "1000 ₴")))
        assertEquals("900,00", amountFacts("Аванс ₴100,00\nРешта 900,00 грн")[META_ENTITY_AMOUNT])
        assertEquals("9.00 USD", mainAmount(listOf("\$5.00", "9.00 USD")))
    }

    @Test
    fun `валюту знает ISO, а не список в этом файле (#1059)`() {
        // Валют в мире больше, чем записей в любом рукописном списке: «1200 JPY» и «1200 CHF»
        // суммой не считались вовсе, а после гейта разбора ответа переставали быть знанием.
        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "1200 JPY"))
        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "1200 CHF"))
        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "CHF 1200"))
        assertEquals("1200", amountFacts("Итого 1200 CHF")[META_ENTITY_AMOUNT])

        // Буквы при числе валютой сами по себе не становятся — иначе «TAX1» снова сумма.
        assertEquals(false, semanticFits(META_ENTITY_AMOUNT, "TAX1"))
        assertEquals(false, semanticFits(META_ENTITY_AMOUNT, "sum 101.01"))
        assertEquals(false, semanticFits(META_ENTITY_AMOUNT, "BAR 5,00"))
    }

    /**
     * Чек Family Dollar с карточки #1059 — восстановленный, а не дословный.
     *
     * Дословны здесь пять сумм: они выписаны из ответа модели в живом логе 17.08.2026
     * («AMOUNT=2.00 … AMOUNT=0.07»). Подписи строк названы в карточке цитатой чтения
     * («SUBTOTAL / TAX1 0 / CASH TOTAL 0 / CHANGE»), а вот кто из чисел под какой подписью
     * стоял и где на строке стоял доллар — восстановлено. Самого текста чтения в репозитории
     * нет: `entity.ocr.text.ref` в графе прогона указывает на файл в памяти телефона.
     */
    private val familyDollar = """
        FAMILY DOLLAR
        SUBTOTAL ${'$'}2.00
        TAX1 ${'$'}0.18
        CASH TOTAL ${'$'}2.18
        CASH ${'$'}2.25
        CHANGE ${'$'}0.07
    """.trimIndent()
}
