package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило размечает токен, не решая его роль (#258, design v3 §4). Дословный случай — трек
 * `20 4514 9154 9395` тремя атомами: судить форму можно только по склейке строки, а пометку
 * несёт каждый атом пробега.
 */
class RuleEvidenceTest {

    private fun atom(id: String, text: String, l: Float, t: Float, r: Float, b: Float) =
        Atom(id, text, Box(l, t, r, b))

    @Test
    fun `трек тремя атомами — помечены все три, соседи по странице не тронуты`() {
        val layer = AtomLayer(
            listOf(
                atom("a1", "20", 10f, 100f, 40f, 120f),
                atom("a2", "4514 9154", 45f, 100f, 140f, 120f),
                atom("a3", "9395", 145f, 100f, 190f, 120f),
                atom("far", "Отправитель", 10f, 900f, 150f, 930f),
            ),
        )

        val e = layer.ruleEvidence()

        assertEquals(listOf("track-shaped"), e["a1"])
        assertEquals(listOf("track-shaped"), e["a2"])
        assertEquals(listOf("track-shaped"), e["a3"])
        assertEquals(null, e["far"])
    }

    @Test
    fun `десять цифр — не трек, счётчик цифр часть формы`() {
        val layer = AtomLayer(listOf(atom("p", "0501234567", 10f, 10f, 100f, 30f)))

        assertTrue(layer.ruleEvidence().isEmpty())
    }

    /** Живой случай #244: время статуса — не дата документа; пометка даёт модели это увидеть. */
    @Test
    fun `голое время помечено, календарная дата — нет`() {
        val layer = AtomLayer(
            listOf(
                atom("t", "14:32", 10f, 10f, 60f, 30f),
                atom("d", "30.03", 70f, 10f, 120f, 30f),
            ),
        )

        val e = layer.ruleEvidence()

        assertEquals(listOf("clock-shaped"), e["t"])
        assertEquals(null, e["d"])
    }

    /** Пробег судится внутри строки: склеить разорванный переносом номер — решить за модель. */
    @Test
    fun `номер, разорванный на две строки страницы, не помечается`() {
        val layer = AtomLayer(
            listOf(
                atom("u1", "20 4514", 10f, 100f, 100f, 120f),
                atom("u2", "9154 9395", 10f, 200f, 100f, 220f),
            ),
        )

        assertTrue(layer.ruleEvidence().isEmpty())
    }

    @Test
    fun `страница без цифр не рождает улик`() {
        val layer = AtomLayer(listOf(atom("w", "Отправитель", 10f, 10f, 100f, 30f)))

        assertTrue(layer.ruleEvidence().isEmpty())
    }
}
