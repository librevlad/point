package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

class AtomLayerTest {

    private fun atom(
        id: String,
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float = 0.9f,
    ) = Atom(id = id, text = text, box = Box(left, top, right, bottom), confidence = confidence)

    @Test
    fun `область возвращает атомы, чьи центроиды внутри неё`() {
        val layer = AtomLayer(
            listOf(
                atom("a1", "20 4514", 10f, 100f, 90f, 120f),
                atom("a2", "9154", 95f, 100f, 140f, 120f),
                atom("a3", "9395", 145f, 100f, 190f, 120f),
                atom("a4", "Отримувач", 10f, 130f, 120f, 150f),
            )
        )

        val inRegion = layer.atomsIn(Box(0f, 95f, 200f, 125f))

        assertEquals(listOf("a1", "a2", "a3"), inRegion.map { it.id })
    }

    @Test
    fun `значение области собирается в порядке чтения, а не в порядке выдачи ридера`() {
        val layer = AtomLayer(
            listOf(
                atom("a3", "9395", 145f, 100f, 190f, 120f),
                atom("a1", "20", 10f, 100f, 40f, 120f),
                atom("a2", "4514 9154", 45f, 100f, 140f, 120f),
            )
        )

        val value = layer.textIn(Box(0f, 95f, 200f, 125f))

        assertEquals("20 4514 9154 9395", value)
    }

    @Test
    fun `слова одной строки идут слева направо, даже когда их верхние края не совпадают`() {
        val layer = AtomLayer(
            listOf(
                atom("a3", "9395", 145f, 99f, 190f, 119f),
                atom("a1", "20", 10f, 100f, 40f, 120f),
                atom("a2", "4514 9154", 45f, 102f, 140f, 122f),
            )
        )

        val value = layer.textIn(Box(0f, 90f, 200f, 130f))

        assertEquals("20 4514 9154 9395", value)
    }

    @Test
    fun `строки разделяются переводом строки, а не склеиваются в одну`() {
        val layer = AtomLayer(
            listOf(
                atom("a1", "Одержувач", 10f, 100f, 120f, 120f),
                atom("a2", "Нор І.А", 125f, 101f, 190f, 121f),
                atom("a3", "Відправник", 10f, 140f, 130f, 160f),
            )
        )

        assertEquals("Одержувач Нор І.А\nВідправник", layer.text)
    }

    @Test
    fun `слой показывает, что стоит перечитать, а не выбрасывает это`() {
        val layer = AtomLayer(
            listOf(
                atom("a1", "Одержувач", 10f, 100f, 120f, 120f, confidence = 0.94f),
                atom("a2", "Нор", 125f, 100f, 160f, 120f, confidence = 0.41f),
            )
        )

        assertEquals(listOf("a2"), layer.doubtful(below = 0.6f).map { it.id })
        assertEquals(2, layer.atoms.size)
    }

    @Test
    fun `текст ридера побеждает пересобранный, когда ридер его дал`() {
        val layer = AtomLayer(
            atoms = listOf(
                atom("a1", "Ліворуч", 10f, 100f, 80f, 120f),
                atom("a2", "Праворуч", 300f, 100f, 380f, 120f),
            ),
            readerText = "Ліворуч\nПраворуч",
        )

        assertEquals("Ліворуч\nПраворуч", layer.text)
    }
}
