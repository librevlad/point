package com.point.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * Не режет ли найденный лист текст (#1044, замер 23.10.2026).
 *
 * На двух кадрах корпуса из восьми углы легли не на края бумаги, а внутрь неё: копия выходила
 * ровной, только без концов строк или без левого столбца — и молча. Неверные углы хуже
 * ненайденных: ненайденную страницу Point честно отдаёт кадром целиком.
 *
 * Признак — строки, которые продолжаются за краем. У настоящего края по одну сторону поле
 * листа, по другую стол; у ложного по обе стороны те же строки на той же высоте. Сравниваются
 * полоса внутри у края и полоса снаружи: чернила снаружи должны лежать там же, где внутри.
 * Пёстрый стол (древесина, ткань) чернила даёт, но не строками напротив строк листа.
 */
internal object PageEdges {

    /** Режет ли четырёхугольник [corners] (верх-лево, верх-право, низ-право, низ-лево) текст на [gray]. */
    fun cutsText(gray: Mat, corners: Array<Point>, scratch: MutableList<Mat>): Boolean {
        val (tl, tr, br, bl) = corners
        val pageW = maxOf(PageVision.distance(tl, tr), PageVision.distance(bl, br))
        val pageH = maxOf(PageVision.distance(tl, bl), PageVision.distance(tr, br))
        if (pageW < 1.0 || pageH < 1.0) return false
        val s = WORK_SIDE / maxOf(pageW, pageH)
        val w = pageW * s
        val h = pageH * s
        val m = MARGIN * maxOf(w, h)

        // Лист кладётся в середину холста с запасом вокруг: за краем видно, что было на снимке.
        val src = MatOfPoint2f(tl, tr, br, bl).also { scratch += it }
        val dst = MatOfPoint2f(Point(m, m), Point(m + w, m), Point(m + w, m + h), Point(m, m + h)).also { scratch += it }
        val transform = Imgproc.getPerspectiveTransform(src, dst).also { scratch += it }
        val canvas = Mat().also { scratch += it }
        Imgproc.warpPerspective(
            gray, canvas, transform, Size(w + 2 * m, h + 2 * m),
            Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE,
        )
        val ink = Mat().also { scratch += it }
        Imgproc.adaptiveThreshold(canvas, ink, 1.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0)

        // Где холст вышел за снимок, там не стол и не текст, а ничего (кадр 18 корпуса: угол
        // на самой кромке, и половина полосы снаружи — повтор кромки).
        val seen = Mat().also { scratch += it }
        Imgproc.warpPerspective(
            Mat.ones(gray.size(), CvType.CV_8U).also { scratch += it }, seen, transform, canvas.size(),
            Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT,
        )
        Core.multiply(ink, seen, ink)

        // Длинные линии — разлиновка, рамки, волокна стола — не текст: строки за краем
        // узнаются по буквам, а не по полосам.
        val run = (STRIP * maxOf(w, h)).coerceAtLeast(3.0)
        for (size in listOf(Size(run, 1.0), Size(1.0, run))) {
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, size).also { scratch += it }
            val lines = Mat().also { scratch += it }
            Imgproc.morphologyEx(ink, lines, Imgproc.MORPH_OPEN, kernel)
            Core.subtract(ink, lines, ink)
        }

        val gap = GAP * maxOf(w, h)
        val strip = STRIP * maxOf(w, h)
        val x0 = m.toInt()
        val y0 = m.toInt()
        val x1 = (m + w).toInt()
        val y1 = (m + h).toInt()
        val g = gap.toInt()
        val st = strip.toInt()

        // Слева и справа строки идут поперёк края — сравниваются профили по рядам; сверху
        // и снизу поперёк края идут столбцы текста — профили по столбцам.
        fun edge(inY: IntRange, inX: IntRange, outY: IntRange, outX: IntRange, byRows: Boolean) = Edge(
            inside = ink.submat(inY.first, inY.last, inX.first, inX.last),
            outside = ink.submat(outY.first, outY.last, outX.first, outX.last),
            outsideSeen = Core.countNonZero(seen.submat(outY.first, outY.last, outX.first, outX.last)),
            byRows = byRows,
        )
        return listOf(
            edge(y0..y1, x0 + g..x0 + g + st, y0..y1, x0 - g - st..x0 - g, byRows = true),
            edge(y0..y1, x1 - g - st..x1 - g, y0..y1, x1 + g..x1 + g + st, byRows = true),
            edge(y0 + g..y0 + g + st, x0..x1, y0 - g - st..y0 - g, x0..x1, byRows = false),
            edge(y1 - g - st..y1 - g, x0..x1, y1 + g..y1 + g + st, x0..x1, byRows = false),
        ).any { it.cuts() }
    }

    /**
     * Край режет текст, если за ним те же строки напротив строк — или просто не меньше
     * чернил, чем у края изнутри (кадры 18 и 21 корпуса: отрезанная шапка и начала строк
     * дерева лежат не строка в строку, но гуще, чем внутри). У настоящего края изнутри
     * поле, снаружи стол — чернил там меньше, чем в тексте.
     */
    private class Edge(val inside: Mat, val outside: Mat, val outsideSeen: Int, val byRows: Boolean) {
        fun cuts(): Boolean {
            // Край на кромке снимка: что за ним, не видно, и резать там нечего.
            if (outsideSeen < MIN_SEEN * outside.total()) return false
            val a = profile(inside, byRows)
            val b = profile(outside, byRows)
            val inDensity = a.sum() / inside.total().toDouble()
            val outDensity = b.sum() / outsideSeen.toDouble()
            if (inDensity < MIN_DENSITY) return false
            if (inDensity >= MIN_TEXT_DENSITY && outDensity >= TEXT_GOES_ON * inDensity) return true
            return outDensity >= OUTSIDE_SHARE * inDensity && correlation(a, b) > MIN_CORRELATION
        }
    }

    private fun profile(m: Mat, byRows: Boolean): DoubleArray {
        val reduced = Mat()
        try {
            Core.reduce(m, reduced, if (byRows) 1 else 0, Core.REDUCE_SUM, CvType.CV_32S)
            val values = IntArray(reduced.total().toInt())
            reduced.get(0, 0, values)
            return DoubleArray(values.size) { values[it].toDouble() }
        } finally {
            reduced.release()
        }
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        if (n < 2) return 0.0
        val ma = a.take(n).average()
        val mb = b.take(n).average()
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until n) {
            ab += (a[i] - ma) * (b[i] - mb)
            aa += (a[i] - ma) * (a[i] - ma)
            bb += (b[i] - mb) * (b[i] - mb)
        }
        return if (aa == 0.0 || bb == 0.0) 0.0 else ab / sqrt(aa * bb)
    }

    private const val WORK_SIDE = 800.0
    private const val MARGIN = 0.10
    private const val GAP = 0.012
    private const val STRIP = 0.06
    private const val MIN_DENSITY = 0.02
    private const val OUTSIDE_SHARE = 0.4
    private const val MIN_CORRELATION = 0.5
    private const val MIN_TEXT_DENSITY = 0.03
    private const val TEXT_GOES_ON = 0.8
    private const val MIN_SEEN = 0.3
}
