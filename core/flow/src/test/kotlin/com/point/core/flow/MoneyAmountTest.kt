package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сумма денег — факт, заведённый вместе со схемой «Перевести по реквизитам» (#262).
 *
 * Формы взяты с живых кадров корпуса, а не выдуманы: переписка с ценой и суммой к возврату,
 * экран банка «платіж надіслано», банковская квитанция с колонкой «Сума (грн)».
 */
class MoneyAmountTest {

    @Test
    fun `число рядом с валютой — сумма, и валюта живёт своим ключом`() {
        val facts = amountFacts("Один комплект стоит 320 грн")

        assertEquals("320", facts[META_ENTITY_AMOUNT])
        assertEquals("грн", facts[META_ENTITY_AMOUNT_CURRENCY])
    }

    @Test
    fun `валюта переносом строки не отрывается от числа`() {
        // Кадр 02: пузырь переписки переносит «грн» на следующую строку — вёрстка, а не смысл.
        assertEquals("320", amountFacts("Один комплект стоит 320\nгрн")[META_ENTITY_AMOUNT])
    }

    @Test
    fun `цена и сумма к переводу обе видны — вторая в «или»`() {
        // Названная граница правила: формой они не различаются вовсе. Значением становится
        // первая, вторая честно живёт в `.more` — человек видит обе и выбирает сам.
        val facts = amountFacts("Один комплект стоит 320 грн\nостаток скиньте на карту\n300 грн")

        assertEquals("320", facts[META_ENTITY_AMOUNT])
        assertEquals(altValue(listOf("320", "300")), facts[META_ENTITY_AMOUNT + META_MORE_SUFFIX])
    }

    @Test
    fun `валюта перед числом — подпись колонки квитанции`() {
        // Кадр 20: «Сума (грн)» слева, значение справа — колонка, которую OCR отдаёт одной строкой.
        val facts = amountFacts("Сума (грн)      500.00\nКомісія (грн)      0.00")

        assertEquals("500.00", facts[META_ENTITY_AMOUNT])
        assertEquals("грн", facts[META_ENTITY_AMOUNT_CURRENCY])
        assertEquals(altValue(listOf("500.00", "0.00")), facts[META_ENTITY_AMOUNT + META_MORE_SUFFIX])
    }

    @Test
    fun `разрядный пробел — часть числа, а не граница`() {
        // Иначе правило написало бы «020.10» и пометило бы его src=ocr — то есть выдало бы
        // выдуманное значение за прочитанное дословно (тот же урок, что у показания счётчика).
        assertEquals("4 020.10", amountFacts("Платіж надіслано 4 020.10 ₴")[META_ENTITY_AMOUNT])
    }

    @Test
    fun `перенос строки разрядным пробелом не бывает — время сверху в сумму не приклеивается`() {
        // Живая вёрстка переписки: таймстемп стоит своей строкой, сумма — следующей. Пока
        // разрядным пробелом считался любой `\s`, правило склеивало их в одно число «03⏎300»
        // и помечало его src=ocr — то есть выдавало за прочитанное дословно то, чего на
        // странице нет вовсе. Цена ошибки здесь — сумма перевода.
        assertEquals("300", amountFacts("10:03\n300 грн")[META_ENTITY_AMOUNT])
        assertEquals(listOf("548,64"), moneyAmounts("18:54\n548,64 грн").map { it.value })
        assertEquals(listOf("300"), moneyAmounts("Всього 12\n300 грн").map { it.value })
        // И ни одно значение больше не носит в себе перенос строки.
        assertTrue(moneyAmounts("10:03\n300 грн").none { '\n' in it.value })
    }

    @Test
    fun `сумма первой строкой не теряется из-за цифры в конце предыдущей`() {
        // Обратная сторона того же переноса: граница «слева не стоит цифра с пробелом» бережёт
        // от обрезка карты, но с `\n` она молча отменяла настоящую сумму всякий раз, когда
        // предыдущая строка кончалась цифрой, — то есть «не нашли» вместо «нашли».
        assertEquals("500", amountFacts("Код банку 322001\n500 грн")[META_ENTITY_AMOUNT])
        assertEquals(listOf("300"), moneyAmounts("5169 3351 0965 2632\n300 грн").map { it.value })
        assertEquals(listOf("500.00"), moneyAmounts("Комісія 0.00\n500.00 грн").map { it.value })
    }

    @Test
    fun `дата и время под подписью колонки суммой не становятся`() {
        // Подпись «Сума (грн)» стоит отдельной строкой, а под ней не всегда число: правило
        // читало дату «26.04.2026» как «26.04 грн», а таймстемп «10:03» — как «10 грн», и на
        // карточке «Перевести по реквизитам» это выглядело бы настоящей суммой со страницы.
        assertTrue(moneyAmounts("Сума (грн)\n26.04.2026").isEmpty())
        assertTrue(moneyAmounts("Всього, грн\n20.01.1994 р.").isEmpty())
        assertTrue(moneyAmounts("Ціна, грн\n10:03").isEmpty())
        // А настоящая колонка квитанции читается по-прежнему.
        assertEquals("500.00", amountFacts("Сума (грн)\n500.00")[META_ENTITY_AMOUNT])
    }

    @Test
    fun `сумма прописью нулём не становится`() {
        // «п'ятсот гривень 00 копійок» с квитанции: слова «гривень» в словаре валют нет
        // намеренно — иначе правило прочло бы «00» и объявило платёж нулевым.
        assertTrue(amountFacts("Сума літерами п'ятсот гривень 00 копійок").isEmpty())
    }

    @Test
    fun `номер карты обрезком суммы не становится`() {
        // Слева от суммы не стоит цифра с пробелом: «4111 1111 1111 1111» — четвёрки карты,
        // а не разрядные группы, и «2632 грн» было бы куском чужого числа.
        assertTrue(moneyAmounts("Сплата в грн\n4111 1111 1111 1111").isEmpty())
        assertTrue(moneyAmounts("4111 1111 1111 1111 грн").isEmpty())
    }

    @Test
    fun `слишком длинное число суммой не считается`() {
        // Граница отсекает не «слишком дорого», а чужое число, к которому прилипла валюта.
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
        // Кадр 03 корпуса: человек считает вслух в переписке, и знака гривны нет ни у одного
        // числа. Прежде правило здесь молчало и объявляло сумму «работой модели» — то есть
        // объявляло непрочитанным число, напечатанное на странице дословно.
        val facts = amountFacts("127*4.32=548,64\n500+548,64=1048,64")

        assertEquals("1048,64", facts[META_ENTITY_AMOUNT])
        assertEquals(Provenance.OCR.wire, facts[META_ENTITY_AMOUNT + META_SOURCE_SUFFIX])
    }

    @Test
    fun `промежуточный итог сумму перевода не подменяет`() {
        // «548,64» ниже само становится слагаемым — значит это шаг расчёта, а не ответ.
        // Встань оно значением, метрика #262 позеленела бы, а человек правил бы сумму перевода.
        assertEquals(listOf("1048,64", "548,64"), arithmeticTotals("127*4.32=548,64\n500+548,64=1048,64"))
        assertEquals(
            altValue(listOf("1048,64", "548,64")),
            amountFacts("127*4.32=548,64\n500+548,64=1048,64")[META_ENTITY_AMOUNT + META_MORE_SUFFIX],
        )
    }

    @Test
    fun `улика итога — арифметика, и она ровно одна`() {
        // Расчёт доказывает, что число — итог, и молчит о том, что итог этот в деньгах. Второй
        // улики поэтому нет: валютное чтение стоит одной, расчётное не имеет права быть увереннее.
        val facts = amountFacts("500+548,64=1048,64")

        assertEquals("arithmetic", facts[META_ENTITY_AMOUNT + META_EVIDENCE_SUFFIX])
        assertTrue("человек обязан видеть «возможно»", isAssumption(facts, META_ENTITY_AMOUNT))
        // Валюты на странице не было — и выдумывать её правило не имеет права.
        assertTrue(META_ENTITY_AMOUNT_CURRENCY !in facts)
    }

    @Test
    fun `расчёт, который не сходится, итога не даёт`() {
        // Строка, где левая часть не равна правой, — это либо не расчёт, либо строка, прочитанная
        // движком неверно. И то и другое, выданное за сумму с пометкой src=ocr, было бы ложью.
        assertTrue(arithmeticTotals("127*4.32=500,00").isEmpty())
        assertTrue(arithmeticTotals("500+548,64=1048,00").isEmpty())
        assertTrue(amountFacts("2+2=5").isEmpty())
    }

    @Test
    fun `лишние знаки расчёта итогу не мешают — это одно и то же число`() {
        // «127*4.32» даёт 548,6400; страница пишет «548,64». Разной записью число с собой не
        // расходится, и округлять ради этого случая не нужно вовсе — сравниваются значения.
        assertEquals(listOf("548,64"), arithmeticTotals("127*4.32=548,64"))
        assertEquals(listOf("274,32"), arithmeticTotals("548,64/2=274,32"))
    }

    @Test
    fun `округление доведено до копеек и дальше не идёт`() {
        // Ревью #262. Округление по напечатанным знакам принимало за сошедшийся расчёт неверное
        // равенство: «1/2» — это половина, а не единица, и правило объявляло бы её итогом,
        // помеченным src=ocr, с уликой «числа согласованы между собой». Расхождение вдвое
        // такой уликой не подписывают: правило обязано молчать.
        assertTrue(arithmeticTotals("1/2=1").isEmpty())
        assertTrue(arithmeticTotals("5:2=3").isEmpty())
        assertTrue(arithmeticTotals("99/100=1").isEmpty())
        assertTrue(amountFacts("2/3=1").isEmpty())
        // Копейки — та точность, которой пишут деньги, и до них поблажка доходит: точного
        // ответа у «1/3» не существует, а «0,33» на странице — не ошибка, а запись.
        assertEquals(listOf("0,33"), arithmeticTotals("1/3=0,33"))
        assertEquals(listOf("69,97"), arithmeticTotals("12,34*5,67=69,97"))
    }

    @Test
    fun `итог, у которого цифр как у счёта, суммой не становится`() {
        // Ревью #262. Разрядными тройками печатают не только деньги: под форму числа подходит и
        // счёт, и IBAN, а граница «столько цифр бывает у суммы» живёт в одном месте — там же,
        // где ею судят валютное чтение и кандидата модели.
        val huge = "5 169 335 109 652 632"

        assertEquals(false, amountDigitsFit(huge))
        assertTrue(arithmeticTotals("$huge+0=$huge").isEmpty())
        assertTrue(amountFacts("$huge+0=$huge").isEmpty())
        // Разрядные тройки при этом законны сами по себе: молчит граница цифр, а не они.
        assertEquals(listOf("1 048,64"), arithmeticTotals("500+548,64=1 048,64"))
    }

    @Test
    fun `знак действия читается так, как его пишут люди`() {
        // Раскладка у человека та, что включена, а не та, что правильнее: «х» вместо «×» и «*».
        assertEquals(listOf("400"), arithmeticTotals("500-100=400"))
        assertEquals(listOf("6"), arithmeticTotals("2х3=6"))
        assertEquals(listOf("6"), arithmeticTotals("2×3=6"))
    }

    @Test
    fun `таймстемп за итогом расчёта его не отменяет`() {
        // Дословный вывод движка на кадре 03: время сообщения «18:54» пришло как «4...» сразу
        // за итогом. Запрет «цифра через пробел», который бережёт сумму от обрезка карты, здесь
        // молчал бы ровно на живом кадре — а слева итог держит знак равенства.
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
        // «2*3=6» в заметке о раскладке плитки станет суммой. Цена принята сознательно: сумма
        // не якорь ни в одной схеме — «Перевести по реквизитам» зовёт карта, — поэтому лишний
        // итог никого никуда не зовёт, а рядом с ним стоит «возможно».
        assertEquals("6", amountFacts("Плитка 2*3=6 рядов")[META_ENTITY_AMOUNT])
        // Ровно это и держит цену малой: лишний итог карточку «Перевести» не зовёт.
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
        // Два счётчика формы разъехались бы на первой правке, и «модель прочитала то, чего
        // правило не приняло бы» стало бы невидимым.
        assertEquals(true, semanticFits(META_ENTITY_AMOUNT, "300"))
        assertEquals(false, semanticFits(META_ENTITY_AMOUNT, "4111111111111111"))
    }
}
