package com.point.vision

import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Наименьший четырёхугольник, охватывающий лист, у которого больше четырёх видимых вершин
 * (#1044, кадр 18 корпуса).
 *
 * Лист, упёршийся в край кадра или с загнутым углом, на снимке — пяти- или шестиугольник: к
 * его краям прибавляется кромка кадра или линия сгиба. Четыре точки такого контура срезают
 * угол — вместе с текстом в нём, и проверка разреза такие углы честно отвергает; лист тогда
 * отдавался кадром целиком, со всем столом вокруг. Охватывающий четырёхугольник берёт лист
 * целиком и прихватывает только узкий клин фона там, где была лишняя вершина.
 */
internal object PageQuads {

    /**
     * Четыре вершины (в порядке обхода) наименьшего — по жадному шагу — четырёхугольника,
     * охватывающего выпуклую оболочку [hull], не выходящего за кадр [frame] дальше, чем на
     * [FRAME_SLACK] его стороны, и не больше оболочки больше чем в [MAX_GROWTH] раза. `null` —
     * такого нет: оболочка на четырёхугольник не похожа.
     *
     * Шаг: из многоугольника убирается сторона, чьи соседние стороны, продолженные до
     * пересечения, прибавляют наименьший треугольник. Каждый шаг сохраняет охват и выпуклость.
     */
    fun enclosing(hull: MatOfPoint2f, frame: Size): Array<Point>? {
        val arc = Imgproc.arcLength(hull, true)
        if (arc <= 0.0) return null
        // Мелкие зубцы контура — не вершины листа: прореженная оболочка лежит внутри исходной
        // не глубже доли процента периметра.
        val thinned = MatOfPoint2f()
        val poly: MutableList<Point>
        try {
            Imgproc.approxPolyDP(hull, thinned, THIN_FRAC * arc, true)
            poly = thinned.toArray().toMutableList()
        } finally {
            thinned.release()
        }
        if (poly.size < 4) return null
        if (signedArea(poly) < 0) poly.reverse()
        val hullArea = signedArea(poly)
        if (hullArea <= 0.0) return null

        val slack = FRAME_SLACK * maxOf(frame.width, frame.height)
        while (poly.size > 4) {
            val n = poly.size
            var best = -1
            var bestApex: Point? = null
            var bestAdded = Double.MAX_VALUE
            for (i in 0 until n) {
                val a = poly[(i - 1 + n) % n]
                val b = poly[i]
                val c = poly[(i + 1) % n]
                val d = poly[(i + 2) % n]
                val apex = beyond(a, b, c, d) ?: continue
                if (apex.x < -slack || apex.y < -slack || apex.x > frame.width + slack || apex.y > frame.height + slack) continue
                val added = Math.abs(cross(b, apex, c)) / 2
                if (added < bestAdded) {
                    bestAdded = added
                    best = i
                    bestApex = apex
                }
            }
            if (best < 0 || bestApex == null) return null
            poly[best] = bestApex
            poly.removeAt((best + 1) % n)
        }
        if (signedArea(poly) > MAX_GROWTH * hullArea) return null
        return poly.map { Point(it.x.coerceIn(0.0, frame.width - 1), it.y.coerceIn(0.0, frame.height - 1)) }
            .toTypedArray()
    }

    /**
     * Похож ли четырёхугольник (верх-лево, верх-право, низ-право, низ-лево) на лист: выпуклый,
     * без слипшихся углов и без стороны короче [MIN_SIDE_FRAC] меньшей стороны кадра.
     *
     * Охватывающие и повёрнутые догадки на снимке экрана давали треугольник с двумя углами в
     * одной точке (кадр 16 корпуса) — по нему лист выпрямлялся в клин.
     */
    fun plausible(corners: Array<Point>, frame: Size): Boolean {
        if (corners.size != 4) return false
        val minSide = MIN_SIDE_FRAC * minOf(frame.width, frame.height)
        for (i in 0 until 4) {
            if (PageVision.distance(corners[i], corners[(i + 1) % 4]) < minSide) return false
        }
        var sign = 0
        for (i in 0 until 4) {
            val z = cross(corners[i], corners[(i + 1) % 4], corners[(i + 2) % 4])
            if (Math.abs(z) < 1e-6) return false
            val s = if (z > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /**
     * Где сходятся сторона a→b, продолженная за b, и сторона d→c, продолженная за c, — или
     * `null`, если они расходятся (тогда сторону b–c убрать нельзя).
     */
    private fun beyond(a: Point, b: Point, c: Point, d: Point): Point? {
        val r = Point(b.x - a.x, b.y - a.y)
        val s = Point(c.x - d.x, c.y - d.y)
        val denom = r.x * s.y - r.y * s.x
        if (Math.abs(denom) < 1e-9) return null
        val qp = Point(d.x - a.x, d.y - a.y)
        val t = (qp.x * s.y - qp.y * s.x) / denom
        val u = (qp.x * r.y - qp.y * r.x) / denom
        // За b — это t > 1 по направлению a→b; за c — это u > 1 по направлению d→c.
        if (t < 1.0 || u < 1.0) return null
        return Point(a.x + t * r.x, a.y + t * r.y)
    }

    /** Площадь четырёхугольника (или любого многоугольника) по его вершинам. */
    fun area(corners: Array<Point>): Double = Math.abs(signedArea(corners.toList()))

    private fun cross(o: Point, a: Point, b: Point): Double = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /** Площадь многоугольника со знаком обхода (по часовой в координатах снимка — плюс). */
    private fun signedArea(poly: List<Point>): Double {
        var sum = 0.0
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            sum += p.x * q.y - q.x * p.y
        }
        return sum / 2
    }

    /** Насколько вершины охвата могут выйти за кадр: лист, выходящий за кромку, ей и обрезан. */
    private const val FRAME_SLACK = 0.02

    /** Во сколько раз охват может быть больше оболочки: больше — это уже не лист с углом. */
    private const val MAX_GROWTH = 1.2

    /** Прореживание оболочки — доля её периметра. */
    private const val THIN_FRAC = 0.005

    /** Самая короткая сторона листа — доля меньшей стороны кадра. */
    private const val MIN_SIDE_FRAC = 0.1
}
