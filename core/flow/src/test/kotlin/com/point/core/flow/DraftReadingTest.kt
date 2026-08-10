package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кому верить о том, что написано на снимке (#770).
 *
 * Живая охота 11.08.2026: на почтовой наклейке разбор по странице менял местами
 * отправителя с получателем, выдавал телефон за номер накладной и сочинял «г. Лумброван».
 * Слова были догадками движка, но знание получало провенанс «прочитано» и выглядело
 * твёрдым. Порог проверяется на дословных выводах устройства, а не на сочинённых цифрах.
 */
class DraftReadingTest {

    private fun layerOf(name: String): AtomLayer = AtomCodec.decode(
        checkNotNull(javaClass.getResourceAsStream("/ocr/$name.atoms.tsv")) { "нет фикстуры $name" }
            .bufferedReader().readText(),
    )

    @Test
    fun `почтовая наклейка — черновик, её читают глазами`() {
        assertTrue(draftReading(layerOf("np_label")))
    }

    @Test
    fun `уверенно прочитанная страница остаётся за страницей`() {
        assertFalse(draftReading(layerOf("table_04_dates")))
    }

    @Test
    fun `судить не по чему — страница главная`() {
        assertFalse("слоя нет", draftReading(null))
        assertFalse("слов нет", draftReading(AtomLayer(emptyList())))
        assertFalse(
            "чтение снаружи уверенности не возвращает",
            draftReading(AtomLayer(emptyList(), readerText = "ОДЕСА ПОСИЛКОВИЙ")),
        )
    }

    @Test
    fun `пары слов мало, чтобы объявить чтение черновиком`() {
        val few = AtomLayer(
            List(4) { i -> Atom("a$i", "слово", Box(0f, i * 20f, 100f, i * 20f + 18f), confidence = 0.1f) },
        )

        assertFalse(draftReading(few))
    }
}
