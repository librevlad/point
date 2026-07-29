package com.point.executors

import kotlin.math.abs

/**
 * Pure-Kotlin bivariate degree-3 displacement field, fit by least squares.
 *
 * No Android/OpenCV — JVM-unit-tested (the native OpenCV remap that consumes this field
 * stays behind [OpenCvScan]). Callers normalize pixel coordinates to [-1, 1] before use.
 */
object DewarpField {
    private const val TERMS = 10

    /** A known displacement [v] at normalized coordinate ([x], [y]). */
    data class Anchor(val x: Double, val y: Double, val v: Double)

    /** Degree-3 bivariate monomial basis: 1, x, y, x², xy, y², x³, x²y, xy², y³. */
    fun terms(xn: Double, yn: Double): DoubleArray = doubleArrayOf(
        1.0, xn, yn, xn * xn, xn * yn, yn * yn,
        xn * xn * xn, xn * xn * yn, xn * yn * yn, yn * yn * yn,
    )

    fun eval(coeffs: DoubleArray, xn: Double, yn: Double): Double {
        val t = terms(xn, yn)
        var s = 0.0
        for (i in 0 until TERMS) s += coeffs[i] * t[i]
        return s
    }

    /**
     * Fit the field to [anchors] by solving the normal equations (AᵀA)c = Aᵀv.
     * Returns the zero field when under-determined (< 12 anchors) or singular.
     */
    fun fit(anchors: List<Anchor>): DoubleArray {
        if (anchors.size < 12) return DoubleArray(TERMS)
        val ata = Array(TERMS) { DoubleArray(TERMS) }
        val atv = DoubleArray(TERMS)
        for (a in anchors) {
            val t = terms(a.x, a.y)
            for (i in 0 until TERMS) {
                atv[i] += t[i] * a.v
                for (j in 0 until TERMS) ata[i][j] += t[i] * t[j]
            }
        }
        return solve(ata, atv) ?: DoubleArray(TERMS)
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i ->
            DoubleArray(n + 1).also { r ->
                for (j in 0 until n) r[j] = a[i][j]
                r[n] = b[i]
            }
        }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (r in 0 until n) if (r != col) {
                val f = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { i -> m[i][n] / m[i][i] }
    }
}
