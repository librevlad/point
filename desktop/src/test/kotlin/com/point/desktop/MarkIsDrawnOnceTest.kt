package com.point.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Знак Point рисуется в одном месте.
 *
 * Копий было три: иконка окна, мини-знак компактного окна и картинка для панели задач.
 * У мини-знака кольцо оказалось шире и толще остальных, хотя рядом стояла приписка «то же
 * кольцо, что у телефона», — расхождение никто не заметил, потому что заметить было негде.
 *
 * Сторож стоит на классе беды, а не на трёх известных местах: любая новая отрисовка кольца
 * своими цветами уронит тест.
 */
class MarkIsDrawnOnceTest {

    private val sources = File("src/main/kotlin/com/point/desktop")
        .walkTopDown().filter { it.name.endsWith(".kt") }.toList()

    /** Цвета кольца: по ним видно, что рисуют именно знак, а не что-то своё. */
    private val ringColours = listOf("0xFFEAF0FF", "0xFF9B7BFF", "0xFF00A6FF")

    @Test
    fun `цвета знака названы в одном файле`() {
        val holders = sources.filter { file ->
            val text = file.readText()
            ringColours.all { text.contains(it) }
        }.map { it.name }

        assertEquals(listOf("PointMark.kt"), holders)
    }

    @Test
    fun `доли кольца не переписываются на месте`() {
        val mark = File(sources.first { it.name == "PointMark.kt" }.path).readText()

        assertTrue("доли знака должны лежать в PointMark", mark.contains("RING_OUTER"))
        val others = sources.filter { it.name != "PointMark.kt" }
            .filter { it.readText().contains("Stroke(width = r * 0.") }
            .map { it.name }
        assertTrue("кольцо снова рисуют своими долями: $others", others.isEmpty())
    }
}
