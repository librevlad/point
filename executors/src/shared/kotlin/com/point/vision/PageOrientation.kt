package com.point.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * Как поставить страницу, чтобы её можно было читать (#1044, замер 23.10.2026).
 *
 * Выпрямление ставит лист так, как он лежал на снимке: снятая боком накладная выходила боком,
 * а положенный вверх ногами лист — вверх ногами. На корпусе владельца так вышло три кадра из
 * шести, и прочитанное с такой копии хуже исходника.
 *
 * Здесь без чтения текста — по тому, как лежат строки:
 * - строки идут поперёк страницы, и между ними пустые полосы: у повёрнутой на бок страницы
 *   эти полосы стоят столбцами, а не рядами;
 * - вверх ногами говорят два признака: над строкой у кириллицы и латиницы больше, чем под
 *   ней (заглавные, цифры, точки «і», «ї», «й», выносные «б», «д»), и левый край текста
 *   ровный, а правый рваный. Переворот признаётся, только когда признаки не спорят.
 *
 * Не уверен — страница остаётся как лежала: неверный поворот хуже несделанного.
 */
internal object PageOrientation {

    /** Сколько четвертей по часовой стрелке повернуть страницу: 0..3. */
    fun quarterTurns(rgba: Mat, scratch: MutableList<Mat>): Int {
        val ink = inkOf(rgba, scratch) ?: return 0
        val sideways = when (lineDirection(ink)) {
            Direction.ACROSS -> false
            Direction.ALONG -> true
            Direction.UNKNOWN -> return 0
        }
        val level = if (sideways) turned(ink, 1, scratch) else ink
        return when (upsideDown(level)) {
            true -> if (sideways) 3 else 2
            false -> if (sideways) 1 else 0
            // Бок виден, а верх нет: из двух четвертей берётся та, что чаще у снимка с рук —
            // телефон повёрнут против часовой, и лист на снимке лежит вершиной влево.
            null -> if (sideways) 1 else 0
        }
    }

    /** Повернуть матрицу на [turns] четвертей по часовой стрелке. */
    fun turned(mat: Mat, turns: Int, scratch: MutableList<Mat>): Mat {
        val code = when (Math.floorMod(turns, 4)) {
            0 -> return mat
            1 -> Core.ROTATE_90_CLOCKWISE
            2 -> Core.ROTATE_180
            else -> Core.ROTATE_90_COUNTERCLOCKWISE
        }
        val out = Mat().also { scratch += it }
        Core.rotate(mat, out, code)
        return out
    }

    /** Чернила текста: тёмное на светлом, без длинных линий разлиновки и рамок. */
    private fun inkOf(rgba: Mat, scratch: MutableList<Mat>): Mat? {
        val longSide = maxOf(rgba.rows(), rgba.cols()).toDouble()
        if (longSide < MIN_SIDE) return null
        val s = if (longSide > WORK_SIDE) WORK_SIDE / longSide else 1.0
        val small = Mat().also { scratch += it }
        if (s < 1.0) Imgproc.resize(rgba, small, Size(rgba.cols() * s, rgba.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA) else rgba.copyTo(small)
        val gray = Mat().also { scratch += it }
        Imgproc.cvtColor(small, gray, if (small.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)

        val ink = Mat().also { scratch += it }
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 15.0)

        // Линии таблиц и рамки — не строки: они сделали бы любую страницу «и такой, и такой».
        for (size in listOf(Size(ink.cols() / 12.0, 1.0), Size(1.0, ink.rows() / 12.0))) {
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, size).also { scratch += it }
            val lines = Mat().also { scratch += it }
            Imgproc.morphologyEx(ink, lines, Imgproc.MORPH_OPEN, kernel)
            Core.subtract(ink, lines, ink)
        }
        return ink.takeIf { Core.countNonZero(it) > MIN_INK_FRACTION * it.total() }
    }

    private enum class Direction { ACROSS, ALONG, UNKNOWN }

    /** Строки идут поперёк кадра (ряды с пустыми полосами) или вдоль (столбцы с ними). */
    private fun lineDirection(ink: Mat): Direction {
        val rows = gapShare(profile(ink, byRows = true))
        val cols = gapShare(profile(ink, byRows = false))
        return when {
            rows > cols + DIRECTION_MARGIN -> Direction.ACROSS
            cols > rows + DIRECTION_MARGIN -> Direction.ALONG
            else -> Direction.UNKNOWN
        }
    }

    /** Сумма чернил по рядам (или по столбцам). */
    private fun profile(ink: Mat, byRows: Boolean): IntArray {
        val reduced = Mat()
        try {
            Core.reduce(ink, reduced, if (byRows) 1 else 0, Core.REDUCE_SUM, CvType.CV_32S)
            val values = IntArray(reduced.total().toInt())
            reduced.get(0, 0, values)
            return IntArray(values.size) { values[it] / 255 }
        } finally {
            reduced.release()
        }
    }

    /**
     * Доля почти пустых полос между первой и последней строкой текста. Между строками —
     * пустые ряды; между буквами пустые столбцы есть, но у разных строк они не совпадают.
     */
    private fun gapShare(p: IntArray): Double {
        val first = p.indexOfFirst { it > 0 }
        val last = p.indexOfLast { it > 0 }
        if (first < 0 || last - first < 20) return 0.0
        val body = p.copyOfRange(first, last + 1)
        val mean = body.average()
        return body.count { it < GAP_LEVEL * mean }.toDouble() / body.size
    }

    /** Строка текста: её ряды на странице. */
    private class Line(val top: Int, val bottom: Int)

    /**
     * Перевёрнута ли страница со строками поперёк: `true` / `false` / `null` — не понять.
     */
    private fun upsideDown(ink: Mat): Boolean? {
        val rows = profile(ink, byRows = true)
        val lines = linesOf(rows)
        if (lines.size < MIN_LINES) return null

        val byAscent = ascentVote(ink, lines)
        val byMargin = marginVote(ink, lines)
        return when {
            byAscent == null && byMargin == null -> null
            byAscent == null -> byMargin
            byMargin == null -> byAscent
            byAscent == byMargin -> byAscent
            else -> null
        }
    }

    private fun linesOf(rows: IntArray): List<Line> {
        val level = rows.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: return emptyList()
        val lines = ArrayList<Line>()
        var start = -1
        for (y in 0..rows.size) {
            val inside = y < rows.size && rows[y] >= GAP_LEVEL * level
            if (inside && start < 0) start = y
            if (!inside && start >= 0) {
                if (y - start >= MIN_LINE_HEIGHT) lines += Line(start, y - 1)
                start = -1
            }
        }
        return lines
    }

    /**
     * Над строкой или под ней больше чернил. Середина строки — её плотная полоса (высота
     * строчных букв); всё выше — заглавные, цифры, выносные и точки, всё ниже — хвосты.
     */
    private fun ascentVote(ink: Mat, lines: List<Line>): Boolean? {
        var above = 0L
        var below = 0L
        for (line in lines) {
            val band = profile(ink.rowRange(line.top, line.bottom + 1), byRows = true)
            val peak = band.maxOrNull() ?: continue
            val core = band.indices.filter { band[it] >= CORE_LEVEL * peak }
            if (core.isEmpty()) continue
            for (i in 0 until core.first()) above += band[i]
            for (i in core.last() + 1 until band.size) below += band[i]
        }
        return when {
            above > ASCENT_RATIO * below -> false
            below > ASCENT_RATIO * above -> true
            else -> null
        }
    }

    /** Ровный край у текста слева, рваный справа. Перевёрнутый лист — наоборот. */
    private fun marginVote(ink: Mat, lines: List<Line>): Boolean? {
        val lefts = ArrayList<Double>()
        val rights = ArrayList<Double>()
        for (line in lines) {
            val cols = profile(ink.rowRange(line.top, line.bottom + 1), byRows = false)
            val left = cols.indexOfFirst { it > 0 }
            val right = cols.indexOfLast { it > 0 }
            if (left < 0) continue
            lefts += left.toDouble()
            rights += right.toDouble()
        }
        if (lefts.size < MIN_LINES) return null
        val l = spread(lefts)
        val r = spread(rights)
        return when {
            l < MARGIN_RATIO * r -> false
            r < MARGIN_RATIO * l -> true
            else -> null
        }
    }

    /** Разброс без крайних значений: заголовок по центру и подпись не решают за весь лист. */
    private fun spread(values: List<Double>): Double {
        val sorted = values.sorted()
        val cut = sorted.size / 5
        val core = sorted.subList(cut, sorted.size - cut).ifEmpty { sorted }
        val mean = core.average()
        return sqrt(core.sumOf { (it - mean) * (it - mean) } / core.size) + 1.0
    }

    private const val WORK_SIDE = 1200.0
    private const val MIN_SIDE = 200.0
    private const val MIN_INK_FRACTION = 0.002
    private const val GAP_LEVEL = 0.1
    private const val DIRECTION_MARGIN = 0.1
    private const val MIN_LINES = 4
    private const val MIN_LINE_HEIGHT = 6
    private const val CORE_LEVEL = 0.5
    private const val ASCENT_RATIO = 1.25
    private const val MARGIN_RATIO = 0.5
}
