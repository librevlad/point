package com.point.executors

/**
 * Turns a photo into a clean black-and-white "scan": grayscale, then Otsu
 * binarisation (auto threshold from the image histogram). Pure Kotlin on ARGB
 * pixel arrays — no Android — so it is unit-tested directly. The Bitmap I/O is a
 * thin wrapper in ScanRealizer.
 */
object ScanFilter {

    /** @param pixels ARGB ints; @return ARGB ints, each pure black or white (opaque). */
    fun apply(pixels: IntArray): IntArray {
        val gray = IntArray(pixels.size)
        val histogram = IntArray(256)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            gray[i] = y
            histogram[y]++
        }
        val threshold = otsuThreshold(histogram, pixels.size)
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            out[i] = if (gray[i] > threshold) WHITE else BLACK
        }
        return out
    }

    /** Classic Otsu: the threshold that maximises between-class variance. */
    fun otsuThreshold(histogram: IntArray, total: Int): Int {
        if (total == 0) return 127
        var sumAll = 0.0
        for (t in 0..255) sumAll += t.toDouble() * histogram[t]

        var sumBackground = 0.0
        var weightBackground = 0
        var maxBetween = -1.0
        var threshold = 127
        for (t in 0..255) {
            weightBackground += histogram[t]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += t.toDouble() * histogram[t]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sumAll - sumBackground) / weightForeground
            val diff = meanBackground - meanForeground
            val between = weightBackground.toDouble() * weightForeground * diff * diff
            if (between > maxBetween) {
                maxBetween = between
                threshold = t
            }
        }
        return threshold
    }

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()
}
