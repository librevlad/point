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
