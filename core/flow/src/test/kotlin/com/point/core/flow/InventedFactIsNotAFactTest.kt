package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Придуманное не показывается наравне с прочитанным (#940, #942).
 *
 * На фотографии автомобиля стояло «✓ Штрихкод 13821702» — штрихкода там нет. Текстовый файл
 * `Накладная 88-К от 12.10.2026 · Сумма 3 400 грн` назывался «Посылка» — ни номера
 * отправления, ни отделения в нём нет. В обоих случаях догадка вставала галочкой рядом с
 * настоящим знанием, и человек не мог отличить одно от другого.
 */
class InventedFactIsNotAFactTest {

    @Test fun `узор с фотографии кодом товара не становится`() {
        assertFalse("13821702 с фотографии автомобиля прошло за штрихкод", productCodeChecks("13821702"))
    }

    @Test fun `настоящие коды проходят`() {
        // Контрольная цифра считается по самому коду: EAN-13, EAN-8, UPC-A.
        listOf("4820024700016", "96385074", "036000291452").forEach {
            assertTrue("настоящий код забракован: $it", productCodeChecks(it))
        }
    }

    @Test fun `не цифры и не та длина — не код`() {
        listOf("BH9249MT", "1382170", "", "138217021382170213").forEach {
            assertFalse("прошло за код: $it", productCodeChecks(it))
        }
    }

    @Test fun `счёт на перевозку посылкой не называется`() {
        val invoice = """
            Накладная 88-К от 12.10.2026
            Сумма 3 400 грн
            Водитель: Петренко І.М., 067 636 05 60
        """.trimIndent()

        assertNull(documentType(invoice))
    }

    @Test fun `одного обычного слова перевозки мало даже вдвоём`() {
        val invoice = "Накладная 88-К. Накладная выдана 12.10.2026, сумма 3 400 грн"

        assertNull(documentType(invoice))
    }

    @Test fun `настоящая наклейка посылкой называется`() {
        val label = """
            Нова Пошта, відділення №5
            Відправник: ТОВ «Епіцентр К»
            Отримувач: Петренко І.М.
            Зберігання до 20.10.2026
        """.trimIndent()

        assertEquals(TYPE_PARCEL, documentType(label))
    }
}
