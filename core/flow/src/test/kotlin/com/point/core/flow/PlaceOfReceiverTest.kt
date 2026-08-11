package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Маршрут ведёт в отделение получателя (#772).
 *
 * Владелец, разбирая наклейку: «Построить маршрут» ведёт в «ОДЕСА ПОСИЛКОВИЙ», а посылка
 * едет в «с.Бритівка (Одеська обл.), Відділення №1» — туда маршрута нет. Место бралось
 * первым похожим на топоним, а им оказалась шапка склада отправления.
 */
class PlaceOfReceiverTest {

    /** Настоящая наклейка: 81 слово с телефона, оба отделения на странице. */
    private val real = AtomCodec.decode(
        checkNotNull(javaClass.getResourceAsStream("/ocr/np_label.atoms.tsv")) {
            "нет фикстуры наклейки"
        }.bufferedReader().readText(),
    )

    private fun place(layer: AtomLayer, text: String): FieldCandidate =
        FieldCandidate(text, layer.findOnPage(text).first().ids)

    /** «Вддиення Net» — то, что движок сделал из «Відділення №1» получателя. */
    private val receiverBranch get() = place(real, "Вддиення Net")

    private val senderBranch get() = place(real, "Вддтення №14")

    @Test
    fun `из двух отделений выбирается то, что стоит при получателе`() {
        val chosen = real.placeOfReceiver(listOf(senderBranch, receiverBranch), "Лумброван")

        assertEquals(receiverBranch.text, chosen?.text)
    }

    @Test
    fun `порядок кандидатов ничего не решает`() {
        val chosen = real.placeOfReceiver(listOf(receiverBranch, senderBranch), "Лумброван")

        assertEquals(receiverBranch.text, chosen?.text)
    }

    @Test
    fun `получателя на странице не видно — выбор не делается`() {
        assertNull(real.placeOfReceiver(listOf(senderBranch, receiverBranch), "Ковальчук Петро"))
    }

    @Test
    fun `роль не названа — выбор не делается`() {
        assertNull(real.placeOfReceiver(listOf(senderBranch, receiverBranch), null))
    }

    @Test
    fun `единственное место остаётся как есть`() {
        assertNull(real.placeOfReceiver(listOf(senderBranch), "Лумброван"))
    }

    @Test
    fun `в блоке получателя два места — выбор не делается`() {
        // «Брипвка» стоит на странице дважды: в шапке и у получателя. Здесь нужна вторая —
        // село назначения рядом с отделением, то есть два места в одном блоке.
        val settlement = FieldCandidate("Брипвка", real.findOnPage("Брипвка").last().ids)

        assertNull(real.placeOfReceiver(listOf(receiverBranch, settlement), "Лумброван"))
    }

    @Test
    fun `место без опоры в словах страницы в выбор не идёт`() {
        val guessed = FieldCandidate("Відділення №1")

        assertNull(real.placeOfReceiver(listOf(senderBranch, guessed), "Лумброван"))
    }
}
