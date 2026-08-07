package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationTest {

    private fun layer(vararg words: Pair<String, Float>) = AtomLayer(
        words.mapIndexed { i, (text, conf) ->
            Atom("w$i", text, Box(0f, i * 20f, 100f, i * 20f + 18f), confidence = conf)
        },
    )

    private val goodPage = layer(
        "Трек-номер" to 0.95f, "20" to 0.96f, "4514" to 0.95f, "9154" to 0.96f, "9395" to 0.95f,
        "Відправник" to 0.93f, "Іваненко" to 0.94f, "Іван" to 0.92f,
    )

    private val sidewaysGarbage = layer(
        "|" to 0.2f, "l~" to 0.15f, "//" to 0.1f, "т" to 0.3f, "|_" to 0.12f,
    )

    @Test
    fun `мусор перевёрнутой страницы счёта не набирает`() {
        assertTrue(readingScore(goodPage) > readingScore(sidewaysGarbage) * 5)
    }

    @Test
    fun `заметно лучший поворот побеждает`() {
        assertEquals(90, bestOrientation(sidewaysGarbage, mapOf(90 to goodPage, 180 to sidewaysGarbage)))
    }

    @Test
    fun `слабый выигрыш — шум, исходный кадр остаётся`() {

        val almostSame = layer(
            "Трек-номер" to 0.95f, "20" to 0.96f, "4514" to 0.95f, "9154" to 0.96f, "9395" to 0.95f,
            "Відправник" to 0.93f, "Іваненко" to 0.94f, "Іван" to 0.95f,
        )

        assertEquals(0, bestOrientation(goodPage, mapOf(90 to almostSame)))
    }

    @Test
    fun `хорошо прочитанную страницу не крутим`() {
        assertFalse(looksMisoriented(goodPage))
        assertTrue(looksMisoriented(sidewaysGarbage))
        assertTrue(looksMisoriented(AtomLayer(emptyList())))
    }

    @Test
    fun `пустое исходное чтение — любой читаемый поворот выигрывает`() {
        assertEquals(180, bestOrientation(AtomLayer(emptyList()), mapOf(180 to goodPage)))
    }

    @Test
    fun `поворотов нет — угол ноль, а не исключение`() {
        assertEquals(0, bestOrientation(goodPage, emptyMap()))
    }
}
