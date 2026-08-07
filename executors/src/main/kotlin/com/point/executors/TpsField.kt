package com.point.executors

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

class TpsField private constructor(
    private val px: DoubleArray,
    private val py: DoubleArray,
    private val w: DoubleArray,
    private val a0: Double,
    private val a1: Double,
    private val a2: Double,
) {
    fun eval(x: Double, y: Double): Double {
        var s = a0 + a1 * x + a2 * y
        for (i in px.indices) s += w[i] * u(hypot(x - px[i], y - py[i]))
        return s
    }

    companion object {
        private fun u(r: Double): Double = if (r < 1e-9) 0.0 else r * r * ln(r)

        private val ZERO = TpsField(DoubleArray(0), DoubleArray(0), DoubleArray(0), 0.0, 0.0, 0.0)

        fun fit(px: DoubleArray, py: DoubleArray, v: DoubleArray, lambda: Double = 0.0): TpsField {
            val n = px.size
            if (n < 3) return ZERO
            val m = n + 3

            val a = Array(m) { DoubleArray(m) }
            val b = DoubleArray(m)
            for (i in 0 until n) {
                for (j in 0 until n) a[i][j] = u(hypot(px[i] - px[j], py[i] - py[j]))
                a[i][i] += lambda
                a[i][n] = 1.0; a[i][n + 1] = px[i]; a[i][n + 2] = py[i]
                a[n][i] = 1.0; a[n + 1][i] = px[i]; a[n + 2][i] = py[i]
                b[i] = v[i]
            }
            val sol = solve(a, b) ?: return ZERO
            return TpsField(px.copyOf(), py.copyOf(), sol.copyOf(n), sol[n], sol[n + 1], sol[n + 2])
        }

        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val mm = Array(n) { i ->
                DoubleArray(n + 1).also { r ->
                    for (j in 0 until n) r[j] = a[i][j]
                    r[n] = b[i]
                }
            }
            for (col in 0 until n) {
                var piv = col
                for (r in col + 1 until n) if (abs(mm[r][col]) > abs(mm[piv][col])) piv = r
                if (abs(mm[piv][col]) < 1e-12) return null
                val t = mm[col]; mm[col] = mm[piv]; mm[piv] = t
                for (r in 0 until n) if (r != col) {
                    val f = mm[r][col] / mm[col][col]
                    for (c in col..n) mm[r][c] -= f * mm[col][c]
                }
            }
            return DoubleArray(n) { i -> mm[i][n] / mm[i][i] }
        }
    }
}
