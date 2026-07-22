package com.point.executors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanFilterTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `binarises to pure black and white, dark to black and light to white`() {
        val dark = 0xFF101010.toInt()
        val light = 0xFFF0F0F0.toInt()
        val out = ScanFilter.apply(intArrayOf(dark, dark, light, light))

        assertTrue("only pure b/w", out.all { it == black || it == white })
        assertEquals(black, out[0])
        assertEquals(white, out[2])
    }

    @Test
    fun `otsu threshold lands between two peaks`() {
        val histogram = IntArray(256)
        histogram[10] = 100
        histogram[200] = 100
        val t = ScanFilter.otsuThreshold(histogram, 200)
        // Separates the modes: dark peak (10) -> black, light peak (200) -> white.
        assertTrue("threshold separates the modes", t in 10..199)
    }
}
