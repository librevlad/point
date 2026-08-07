package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun `независимые ячейки числовой строки не сливаются в ложный трек`() {
        val layer = AtomLayer(
            listOf(
                atom("c1", "2500", 10f, 100f, 60f, 120f),
                atom("c2", "4000", 100f, 100f, 150f, 120f),
                atom("c3", "100", 190f, 100f, 230f, 120f),
                atom("c4", "500", 270f, 100f, 310f, 120f),
            ),
        )

        assertTrue(layer.ruleEvidence().isEmpty())
    }

    @Test
    fun `номер строки рядом не топит улику настоящего трека`() {
        val layer = AtomLayer(
            listOf(
                atom("n", "1", 10f, 100f, 20f, 120f),
                atom("a1", "20", 25f, 100f, 50f, 120f),
                atom("a2", "4514 9154", 55f, 100f, 150f, 120f),
                atom("a3", "9395", 155f, 100f, 200f, 120f),
            ),
        )

        val e = layer.ruleEvidence()

        assertEquals(null, e["n"])
        assertEquals(listOf("track-shaped"), e["a1"])
        assertEquals(listOf("track-shaped"), e["a2"])
        assertEquals(listOf("track-shaped"), e["a3"])
    }

    @Test
    fun `дата с точками не въезжает в пробег трека`() {
        val layer = AtomLayer(
            listOf(
                atom("d", "15.06.2025", 10f, 100f, 110f, 120f),
                atom("x1", "1200", 115f, 100f, 160f, 120f),
                atom("x2", "3400", 165f, 100f, 210f, 120f),
                atom("x3", "56", 215f, 100f, 240f, 120f),
            ),
        )

        assertTrue(layer.ruleEvidence().isEmpty())
    }

    @Test
    fun `хвостовой сосед после трека не помечается — окно не пересекается`() {
        val layer = AtomLayer(
            listOf(
                atom("a1", "20", 10f, 100f, 40f, 120f),
                atom("a2", "4514 9154", 45f, 100f, 140f, 120f),
                atom("a3", "9395", 145f, 100f, 190f, 120f),
                atom("tail", "07", 195f, 100f, 220f, 120f),
            ),
        )

        val e = layer.ruleEvidence()

        assertEquals(listOf("track-shaped"), e["a3"])
        assertEquals(null, e["tail"])
    }

    @Test
    fun `два трека в одной ячейке — помечены оба`() {
        val layer = AtomLayer(
            listOf(
                atom("a1", "20", 10f, 100f, 40f, 120f),
                atom("a2", "4514 9154", 45f, 100f, 140f, 120f),
                atom("a3", "9395", 145f, 100f, 190f, 120f),
                atom("b1", "2045", 195f, 100f, 240f, 120f),
                atom("b2", "149154", 245f, 100f, 310f, 120f),
                atom("b3", "9395", 315f, 100f, 360f, 120f),
            ),
        )

        val e = layer.ruleEvidence()

        assertEquals(listOf("track-shaped"), e["a1"])
        assertEquals(listOf("track-shaped"), e["b1"])
        assertEquals(listOf("track-shaped"), e["b3"])
    }

    @Test
    fun `номер через косую и S10 помечены целым токеном`() {
        val layer = AtomLayer(
            listOf(
                atom("s", "5900162/7808586", 10f, 100f, 200f, 120f),
                atom("u", "RA123456785UA", 10f, 200f, 190f, 220f),
                atom("bad", "RA123456789UA", 10f, 300f, 190f, 320f),
                atom("price", "1234/5678", 10f, 400f, 120f, 420f),
            ),
        )

        val e = layer.ruleEvidence()

        assertEquals(listOf("track-shaped"), e["s"])
        assertEquals(listOf("track-shaped"), e["u"])

        assertEquals(null, e["bad"])

        assertEquals(null, e["price"])
    }
}
