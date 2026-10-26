package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Порядок читателей — по числам корпуса, а не по порядку строк в коде (#433, срез E). */
class ReaderMeasuresTest {

    private fun measure(reader: String, read: Int, of: Int, ms: Long = 1000) =
        ReaderMeasure(reader, read, of, ms, "26.10.2026")

    @Test
    fun `больше прочитавший идёт первым, где бы ни стоял в списке`() {
        val order = inMeasuredOrder(
            listOf("a", "b", "c"),
            listOf(measure("a", 10, 40), measure("b", 30, 40), measure("c", 20, 40)),
        ) { it }

        assertEquals(listOf("b", "c", "a"), order)
    }

    @Test
    fun `при равной доле первым идёт быстрый`() {
        val order = inMeasuredOrder(
            listOf("slow", "fast"),
            listOf(measure("slow", 5, 10, ms = 9000), measure("fast", 10, 20, ms = 2000)),
        ) { it }

        assertEquals(listOf("fast", "slow"), order)
    }

    @Test
    fun `неизмеренный — после измеренных, в прежнем порядке`() {
        val order = inMeasuredOrder(
            listOf("x", "measured", "y"),
            listOf(measure("measured", 1, 40)),
        ) { it }

        assertEquals(listOf("measured", "x", "y"), order)
    }

    @Test
    fun `без замеров очередь не меняется`() {
        assertEquals(listOf("a", "b"), inMeasuredOrder(listOf("a", "b"), emptyList()) { it })
    }

    @Test
    fun `в замере одна запись на читателя и доля не больше целого`() {
        val names = READER_MEASURES.map { it.reader }
        assertEquals(names.distinct(), names)
        READER_MEASURES.forEach {
            assertTrue("${it.reader}: ${it.read}/${it.of}", it.read in 0..it.of)
        }
    }
}
