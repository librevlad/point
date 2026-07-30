package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Адресация значения — не «строка №5», а область на странице (#257, #258).
 *
 * Живой случай: 14-значный трек с посылочного экрана OCR отдаёт тремя кусками, а следом идёт
 * подпись «Отримувач» строкой ниже. Область, накрывающая строку с номером, обязана вернуть
 * ровно три куска номера и не захватить подпись — иначе значение соберётся из чужого текста.
 */
class AtomLayerTest {

    private fun atom(id: String, text: String, left: Float, top: Float, right: Float, bottom: Float) =
        Atom(id = id, text = text, box = Box(left, top, right, bottom))

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

    /**
     * Номер `20 4514 9154 9395` — дословное чтение с посылочного экрана, задокументированное в
     * `Identifiers.kt`: ML Kit принял его за телефон, и он провалился в пол. На реальном фото
     * верхние края слов одной строки не совпадают — лист не идеально ровный, а буквы разной
     * высоты. Сортировка по `top` на таком входе переставляет куски номера местами.
     */
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
}
