package com.point.executors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DewarpFieldTest {
    @Test
    fun `fit recovers a degree-2 field within tolerance`() {
        fun truth(x: Double, y: Double) = 0.4 + 0.3 * x - 0.2 * y + 0.15 * x * y
        val anchors = buildList {
            for (gx in -5..5) for (gy in -5..5) {
                val xn = gx / 5.0
                val yn = gy / 5.0
                add(DewarpField.Anchor(xn, yn, truth(xn, yn)))
            }
        }
        val c = DewarpField.fit(anchors)
        for ((x, y) in listOf(-0.7 to 0.3, 0.2 to -0.9, 0.0 to 0.0)) {
            assertEquals(truth(x, y), DewarpField.eval(c, x, y), 1e-6)
        }
    }

    @Test
    fun `fewer than 12 anchors yields the zero field`() {
        val c = DewarpField.fit(List(5) { DewarpField.Anchor(0.1 * it, 0.1 * it, 1.0) })
        assertTrue(c.all { it == 0.0 })
    }

    @Test
    fun `eval computes the polynomial basis`() {
        val c = DoubleArray(10).also { it[1] = 2.0; it[2] = 3.0 } // v = 2·xn + 3·yn
        assertEquals(2 * 0.5 + 3 * -0.25, DewarpField.eval(c, 0.5, -0.25), 1e-9)
    }
}
