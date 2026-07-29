package com.point.executors

import org.junit.Assert.assertEquals
import org.junit.Test

class TpsFieldTest {
    @Test
    fun `interpolates the control values exactly at control points`() {
        val px = doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.5)
        val py = doubleArrayOf(0.0, 0.0, 1.0, 1.0, 0.5)
        val v = doubleArrayOf(1.0, 2.0, 3.0, 5.0, 2.5)
        val f = TpsField.fit(px, py, v)
        for (i in px.indices) {
            assertEquals(v[i], f.eval(px[i], py[i]), 1e-5)
        }
    }

    @Test
    fun `reproduces an affine field away from the control points`() {
        // v = 2x + 3y + 1 lies in the TPS affine span → exact everywhere, weights ≈ 0.
        val px = doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.3, 0.7)
        val py = doubleArrayOf(0.0, 0.0, 1.0, 1.0, 0.8, 0.2)
        val v = DoubleArray(px.size) { 2 * px[it] + 3 * py[it] + 1 }
        val f = TpsField.fit(px, py, v)
        assertEquals(2 * 0.4 + 3 * 0.6 + 1, f.eval(0.4, 0.6), 1e-4)
    }

    @Test
    fun `too few control points yields a flat zero field`() {
        val f = TpsField.fit(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0), doubleArrayOf(5.0, 9.0))
        assertEquals(0.0, f.eval(0.5, 0.5), 1e-9)
    }
}
