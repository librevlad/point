package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Годность прочитанного судится двумя сигналами (#694). Первый — уверенность самого
 * движка, он отдаёт её по каждому слову. Второй — состав ответа, и он нужен там, где
 * уверенности нет вовсе: чтение снаружи её не возвращает.
 */
class PoorReadingTest {

    private fun layer(vararg words: Pair<String, Float>) = AtomLayer(
        words.mapIndexed { i, (t, c) -> Atom("w$i", t, Box(0f, i * 20f, 100f, i * 20f + 18f), c) },
    )

    private val confident = layer(
        "Накладна" to 0.93f, "59000123456789" to 0.91f, "від" to 0.88f, "12.05.2026" to 0.9f,
        "отримувач" to 0.87f, "Іваненко" to 0.92f, "Іван" to 0.9f, "Іванович" to 0.89f,
    )

    private val guessed = layer(
        "Накладна" to 0.33f, "59000123456789" to 0.21f, "від" to 0.28f, "12.05.2026" to 0.3f,
        "отримувач" to 0.27f, "Іваненко" to 0.32f, "Іван" to 0.3f, "Іванович" to 0.29f,
    )

    @Test
    fun `пусто — читать нечего`() {
        assertTrue(poorlyRead(""))
        assertTrue(poorlyRead("   \n\t "))
    }

    @Test
    fun `движок сам признался, что угадывал — объект не рождается`() {
        assertTrue("уверенность движка спрошена первой", poorlyRead(guessed.text, guessed))
    }

    @Test
    fun `уверенный движок и живая страница — чтение принято`() {
        assertFalse(poorlyRead(confident.text, confident))
    }

    @Test
    fun `уверенности нет — судим по составу ответа`() {
        assertTrue("короткий мусор снаружи", poorlyRead(". aa - 11 ВЕНЕ"))
        assertFalse("короткая сумма снаружи", poorlyRead("2500 грн"))
        assertFalse("дата снаружи", poorlyRead("12.05.2026"))
    }

    @Test
    fun `короткий мусор не спасается тем, что движок молчит про уверенность`() {
        val silent = AtomLayer(emptyList(), readerText = ". aa - 11 ВЕНЕ")

        assertTrue(poorlyRead(silent.text, silent))
    }

    /**
     * Из двух чтений одного кадра знанием становится то, где живого больше (#1041).
     *
     * Второй заход по выпрямленной копии бывает беднее первого: рамкой листа стал чек внутри
     * кадра, и вместо счёта пришли чистые десять слов из угла. «Не каша» — ещё не «лучше».
     */
    @Test
    fun `полнее то чтение, где живых слов больше`() {
        val corner = AtomLayer(emptyList(), readerText = "Дякуємо за покупку Каса 12")

        assertEquals(confident, betterReading(confident, corner))
        assertEquals(confident, betterReading(corner, confident))
    }

    /**
     * Уверенность решает, что вообще прочитано: слово-догадка дойдёт до человека мусором,
     * и считать его прочитанным нельзя — иначе полным окажется чтение, которого нет.
     */
    @Test
    fun `догадки движка полнотой чтения не считаются`() {
        val corner = AtomLayer(emptyList(), readerText = "Дякуємо за покупку Каса 12")

        assertEquals(corner, betterReading(guessed, corner))
    }

    /** Поровну — остаётся первое: оно с того кадра, которым поделился человек (#1013). */
    @Test
    fun `при равном чтении остаётся первое`() {
        assertEquals(confident, betterReading(confident, AtomLayer(confident.atoms)))
    }
}
