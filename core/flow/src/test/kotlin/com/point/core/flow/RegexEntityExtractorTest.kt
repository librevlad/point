package com.point.core.flow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сущности без ML Kit — то, чем живёт компьютер (#585).
 *
 * Судится на текстах, похожих на настоящие: визитка, счёт, письмо. Главное правило проверяется
 * отдельно и жёстко: **лучше не найти, чем найти неправду** — номер накладной не телефон, а
 * шестнадцать цифр подряд не карта, если не сходится проверка Луна.
 */
class RegexEntityExtractorTest {

    private val extractor = RegexEntityExtractor()

    private suspend fun of(text: String, type: EntityType): List<String> =
        extractor.extract(text).filter { it.type == type }.map { it.value }

    @Test fun `визитка отдаёт почту, телефон и сайт`() = runTest {
        val card = """
            Олена Ковальчук, дизайнер интерьеров
            +380 67 123 45 67
            olena@tihiy-dvor.example
            www.tihiy-dvor.example
        """.trimIndent()

        assertEquals(listOf("olena@tihiy-dvor.example"), of(card, EntityType.EMAIL))
        assertEquals(listOf("+380 67 123 45 67"), of(card, EntityType.PHONE))
        assertEquals(listOf("www.tihiy-dvor.example"), of(card, EntityType.URL))
    }

    @Test fun `счёт отдаёт сумму и дату`() = runTest {
        val bill = "Договор аренды 4512. Оплата до 15 сентября 2026, сумма 48500 руб."

        assertTrue(of(bill, EntityType.MONEY).any { it.contains("48500") })
        assertEquals(listOf("15 сентября 2026"), of(bill, EntityType.DATE_TIME))
    }

    @Test fun `голое число суммой не считается`() = runTest {
        // Иначе любая накладная превращалась бы в кошелёк: «позиция 4512» становилась суммой.
        assertEquals(emptyList<String>(), of("В коробке 4512 штук, позиция 17", EntityType.MONEY))
    }

    @Test fun `номер накладной не выдаётся за карту`() = runTest {
        // Шестнадцать цифр подряд — самая частая форма номера документа. Без проверки Луна
        // человек получил бы на накладной действие «скопировать карту».
        assertEquals(emptyList<String>(), of("Накладная 1234567890123456 от 05.08.2026", EntityType.PAYMENT_CARD))
    }

    @Test fun `настоящая карта проходит проверку Луна`() = runTest {
        assertEquals(listOf("4242 4242 4242 4242"), of("Карта 4242 4242 4242 4242", EntityType.PAYMENT_CARD))
    }

    @Test fun `сокращения не становятся ссылками`() = runTest {
        // «т.е.» и «и.о.» выглядят как домены ровно настолько, чтобы испортить экран.
        assertEquals(emptyList<String>(), of("Это т.е. сокращение, и.о. директора", EntityType.URL))
    }

    @Test fun `точка в конце предложения не уезжает в ссылку`() = runTest {
        assertEquals(listOf("https://point.leerio.app"), of("Смотрите https://point.leerio.app.", EntityType.URL))
    }

    @Test fun `пустой текст — пустой ответ, без работы`() = runTest {
        assertEquals(emptyList<Entity>(), extractor.extract("   "))
    }
}
