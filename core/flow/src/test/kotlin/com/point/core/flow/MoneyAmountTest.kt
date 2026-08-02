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
    fun `расчёт без валюты правилом не читается — это работа модели`() {
        // Кадр 03: «127*4.32=548,64» и «500+548,64=1048,64» — сумма к оплате есть, валюты рядом
        // нет ни у одной. Правило молчит честно; путь к факту — контрактный ключ AMOUNT.
        assertTrue(amountFacts("127*4.32=548,64\n500+548,64=1048,64").isEmpty())
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
