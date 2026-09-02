package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrQualityTest {

    private fun layer(vararg words: Pair<String, Float>) = AtomLayer(
        words.mapIndexed { i, (t, c) -> Atom("w$i", t, Box(0f, i * 20f, 100f, i * 20f + 18f), c) },
    )

    private val contractsTable = layer(
        "Оберіть" to 0.83f, "додайте" to 0.93f, "договора" to 0.93f, "постачання" to 0.92f,
        "Номер" to 0.86f, "Дата" to 0.88f, "Постачальник" to 0.67f, "Примітки" to 0.55f,
        "2018" to 0.81f, "286/2/18/138" to 0.79f, "18.10.18" to 0.59f, "78.00" to 0.89f,
        "24.10.18" to 0.75f, "2018" to 0.80f, "286/2/18/169" to 0.77f, "17.12.18" to 0.81f,
    )

    private val soup = layer(
        "Оберіть" to 0.35f, "додайте" to 0.31f, "договора" to 0.42f, "постачання" to 0.29f,
        "Номер" to 0.38f, "Дата" to 0.44f, "Постачальник" to 0.27f, "Примітки" to 0.35f,
        "2018" to 0.41f, "286/2/18/138" to 0.19f, "18.10.18" to 0.33f, "78.00" to 0.48f,
        "24.10.18" to 0.25f, "2018" to 0.30f, "286/2/18/169" to 0.37f, "17.12.18" to 0.34f,
    )

    /**
     * Выделенный человеком кусок — тоже чтение (#1391).
     *
     * Дословный вывод устройства 02.09.2026 по области, обведённой на накладной. Правило «не
     * меньше трёх слов от четырёх букв» написано для целой страницы, а кусок мал по своей
     * природе: по этой мерке честное чтение выбрасывалось. У объекта не появлялось текста, и
     * рядом предлагалось прочитать его заново — при том что движок был уверен в каждом слове,
     * а разметка слов уже лежала на устройстве.
     */
    private val selectedPiece = layer(
        "КЛАДНАЯ 1187" to 0.9969451f,
        ": 16.04.2026" to 0.9942233f,
        "ма: 4350 грн" to 0.9939203f,
        "Х КАДРА" to 0.9447711f,
    )

    @Test
    fun `выделенный кусок с датой и суммой прочитан, а не испорчен`() {
        assertFalse("движок уверен в каждом слове", weaklyRead(selectedPiece))
        assertFalse("дата и сумма на месте", looksLikeOcrGarbage(selectedPiece.text))
        assertFalse("значит это текст объекта", poorlyRead(selectedPiece.text, selectedPiece))
    }

    @Test
    fun `документ из чисел прочитан, а не испорчен — цифра такое же чтение, как буква`() {
        assertFalse("таблица договоров прочитана", weaklyRead(contractsTable))
        assertFalse("цифры считаются прочитанным", looksLikeOcrGarbage(contractsTable.text))
    }

    @Test
    fun `та же страница с низкой уверенностью — не прочитана`() {
        assertTrue("движок сам говорит, что угадывал", weaklyRead(soup))
    }

    @Test
    fun `пустой слой — читать нечего`() {
        assertTrue(weaklyRead(AtomLayer(emptyList())))
    }

    @Test
    fun `читатель без геометрии судится текстом, а не объявляется молчащим`() {
        val onlyText = AtomLayer(emptyList(), readerText = contractsTable.text)
        val onlySoup = AtomLayer(emptyList(), readerText = "3}3/9|=|=|=|=|-(8}-|8) }{ | ~~ ][ (( ))")

        assertFalse(weaklyRead(onlyText))
        assertTrue(weaklyRead(onlySoup))
    }

    @Test
    fun `короткой подписи судить не по чему — и это не приговор`() {
        assertFalse("ярлык с номером не рукопись", weaklyRead(layer("№" to 0.2f, "17" to 0.3f)))
    }

    @Test
    fun `уверенно прочитанные огрызки страницей не становятся`() {
        val fragments = layer(
            *(1..12).map { "}${'$'}it{" to 0.95f }.toTypedArray(),
        )

        assertTrue(weaklyRead(fragments))
    }
}
