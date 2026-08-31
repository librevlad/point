package com.point.executors

import android.graphics.Bitmap
import com.point.core.flow.PageQuad
import com.point.core.flow.Spot
import com.point.core.flow.reportStage
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

object OpenCvScan {

    val available: Boolean by lazy { runCatching { OpenCVLoader.initLocal() }.getOrDefault(false) }

    /**
     * Выпрямленная страница, сведённая к чёрно-белому, или `null` — страницы не нашлось (#1333).
     *
     * Правило здесь то же, что у [enhance], и это важнее сходства кода: этим путём идёт
     * «Скан в PDF», и прежде он молча подставлял исходный кадр (`?: rgba`) — неисправленный
     * снимок уезжал страницей скана, и человеку он был неотличим от выпрямленной страницы.
     */
    fun process(src: Bitmap): Bitmap? = binarised(src) { rgba, scratch -> detectDocument(rgba, scratch) }

    /**
     * Кадр целиком, сведённый к чёрно-белому (#1333): страницы не нашли, но работа не пропадает.
     *
     * Зовут это, только когда [process] уже ответил «не нашлось», и результат уезжает с
     * пометкой [com.point.core.flow.META_WHOLE_FRAME] — за выпрямленную страницу он не выдаётся.
     */
    fun processAsIs(src: Bitmap): Bitmap? = binarised(src) { rgba, _ -> rgba }

    /**
     * Выпрямленная и подготовленная страница или `null` — страницы на снимке не нашлось (#1333).
     *
     * Прежде неудача молча возвращала исходник (`?: rgba`), и снаружи она была неотличима от
     * успеха: кривой кадр уезжал дальше как «скан». Теперь «не нашлось» — это ответ, а не
     * молчание: по нему кадр обрабатывается целиком ([enhanceAsIs]) и результат помечается
     * [com.point.core.flow.META_WHOLE_FRAME], а не выдаётся за выпрямленную страницу.
     */
    suspend fun enhance(src: Bitmap): Bitmap? = straightened(src)?.bitmap

    /**
     * То же выпрямление — и четыре угла страницы, из которых копия родилась (#1332).
     *
     * Углы нужны тому, кто возвращает прочитанное на снимок человека: копия — это
     * четырёхугольник страницы, растянутый в прямоугольник, и обратный ход по тем же углам
     * ставит найденное туда, куда человек смотрит.
     *
     * Кривую страницу Point расправляет по линиям разлиновки, а не по четырём углам
     * ([dewarpByTps]), и обратного хода у неё нет — тогда углов нет: `null` честнее
     * четырёхугольника, которого не было.
     */
    suspend fun straightened(src: Bitmap): Straightened? {
        var corners: Array<Point>? = null
        val out = prepared(src) { rgba, scratch ->
            reportStage("Ищу страницу на снимке")
            dewarpByTps(rgba, scratch) ?: detectDocument(rgba, scratch) { corners = it }
        } ?: return null
        return Straightened(out, corners?.let(::pageQuadOf))
    }

    /** Углы, найденные на развёрнутом кадре, — в его же координатах. */
    internal fun pageQuadOf(corners: Array<Point>): PageQuad {
        val (tl, tr, br, bl) = corners
        return PageQuad(
            topLeft = Spot(tl.x.toFloat(), tl.y.toFloat()),
            topRight = Spot(tr.x.toFloat(), tr.y.toFloat()),
            bottomRight = Spot(br.x.toFloat(), br.y.toFloat()),
            bottomLeft = Spot(bl.x.toFloat(), bl.y.toFloat()),
        )
    }

    /**
     * Подготовка кадра без выравнивания (#1333) — то самое, что раньше пряталось за `?: rgba`.
     *
     * Страницы не нашёл никто, но выбеливание, снятие бликов и увеличение кадру всё равно
     * помогают: человек не остаётся ни с чем, а результат называет себя пометкой.
     */
    suspend fun enhanceAsIs(src: Bitmap): Bitmap? = prepared(src) { rgba, _ -> rgba }

    /** Общий хвост чёрно-белых путей: найденную страницу и кадр целиком сводит одна бинаризация. */
    private fun binarised(src: Bitmap, align: (Mat, MutableList<Mat>) -> Mat?): Bitmap? {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val scratch = mutableListOf(rgba)
        try {
            val document = align(rgba, scratch) ?: return null
            val scanned = binarise(document, scratch)
            val out = Bitmap.createBitmap(scanned.cols(), scanned.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(scanned, out)
            return out
        } finally {
            scratch.forEach { it.release() }
        }
    }

    /** Общий хвост цветных путей: выбелить бумагу и увеличить мелкое (#1333). */
    private suspend fun prepared(
        src: Bitmap,
        align: suspend (Mat, MutableList<Mat>) -> Mat?,
    ): Bitmap? {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val scratch = mutableListOf(rgba)
        try {
            val straight = align(rgba, scratch) ?: return null
            reportStage("Выбеливаю бумагу")
            val finished = whitenFinish(straight, scratch)
            val scaled = upscale(finished, scratch)
            val out = Bitmap.createBitmap(scaled.cols(), scaled.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(scaled, out)
            return out
        } finally {
            scratch.forEach { it.release() }
        }
    }

    /**
     * Ровный свет и белая бумага — без выпрямления и без увеличения (#1046).
     *
     * Тот же выбеливатель, что и в «Скане»: он снимает тень от руки и градиент от окна,
     * из-за которых движок не видит строк, и оставляет цвет — синяя печать остаётся синей,
     * потому что цвет здесь доказательство подлинности, а не грязь.
     *
     * Геометрия кадра не трогается: слова остаются ровно там, где стояли на снимке, поэтому
     * прочитанное по такой копии можно вернуть исходнику.
     *
     * Лист приходит развёрнутым — как и всюду в этом конвейере. Содержимое защищается по
     * строкам, а строки ищутся горизонтальными прогонами: у бокового кадра они не находятся
     * вовсе, и полоса вдоль краёв стирает в белое то, что в ней лежит.
     */
    fun whiten(src: Bitmap): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val scratch = mutableListOf(rgba)
        try {
            val finished = whitenFinish(rgba, scratch)
            val out = Bitmap.createBitmap(finished.cols(), finished.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(finished, out)
            return out
        } finally {
            scratch.forEach { it.release() }
        }
    }

    private suspend fun upscale(mat: Mat, scratch: MutableList<Mat>): Mat {
        val longSide = maxOf(mat.rows(), mat.cols())
        if (longSide >= UPSCALE_TARGET) return mat
        reportStage("Увеличиваю")
        val s = UPSCALE_TARGET.toDouble() / longSide
        val up = Mat().also { scratch += it }
        Imgproc.resize(mat, up, Size(mat.cols() * s, mat.rows() * s), 0.0, 0.0, Imgproc.INTER_CUBIC)
        return up
    }

    private fun detectDocument(
        rgba: Mat,
        scratch: MutableList<Mat>,
        onPage: (Array<Point>) -> Unit = {},
    ): Mat? {
        val longSide = maxOf(rgba.rows(), rgba.cols()).toDouble()
        val downscale = if (longSide > DETECT_MAX_PX) DETECT_MAX_PX / longSide else 1.0
        val small = Mat().also { scratch += it }
        if (downscale < 1.0) {
            Imgproc.resize(rgba, small, Size(rgba.cols() * downscale, rgba.rows() * downscale))
        } else {
            rgba.copyTo(small)
        }

        val gray = Mat().also { scratch += it }
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        val blurred = Mat().also { scratch += it }
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val frameArea = small.rows().toDouble() * small.cols()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            .also { scratch += it }

        val illumination = Mat().also { scratch += it }
        Imgproc.GaussianBlur(gray, illumination, Size(0.0, 0.0), ILLUMINATION_SIGMA)
        val flat = Mat().also { scratch += it }
        Core.divide(blurred, illumination, flat, 128.0, CvType.CV_8U)
        val otsu = Mat().also { scratch += it }
        Imgproc.threshold(flat, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(otsu, otsu, Imgproc.MORPH_OPEN, kernel)
        var corners = quadFromMask(otsu, frameArea, scratch)

        if (corners == null) {
            val median = medianBrightness(blurred)
            val edges = Mat().also { scratch += it }
            Imgproc.Canny(blurred, edges, 0.66 * median, 1.33 * median)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            corners = quadFromMask(edges, frameArea, scratch)
        }
        if (corners == null) return null

        val scaledBack = corners.map { Point(it.x / downscale, it.y / downscale) }.toTypedArray()
        val page = orderCorners(scaledBack)
        onPage(page)
        return warp(rgba, page, scratch)
    }

    private fun quadFromMask(mask: Mat, frameArea: Double, scratch: MutableList<Mat>): Array<Point>? {
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            mask, contours, Mat().also { scratch += it },
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
        )
        return contours
            .sortedByDescending { Imgproc.contourArea(it) }
            .take(6)
            .firstNotNullOfOrNull { contour -> pageQuad(contour, frameArea) }
    }

    private fun medianBrightness(gray: Mat): Double {
        val hist = Mat()
        Imgproc.calcHist(listOf(gray), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
        val half = gray.rows().toLong() * gray.cols() / 2
        var seen = 0L
        for (i in 0 until 256) {
            seen += hist.get(i, 0)[0].toLong()
            if (seen >= half) {
                hist.release()
                return i.toDouble().coerceAtLeast(10.0)
            }
        }
        hist.release()
        return 128.0
    }

    private fun pageQuad(contour: MatOfPoint, frameArea: Double): Array<Point>? {
        val area = Imgproc.contourArea(contour)
        if (area < MIN_PAGE_FRACTION * frameArea || area > MAX_PAGE_FRACTION * frameArea) return null

        val c2f = MatOfPoint2f(*contour.toArray())
        try {

            fourCorners(c2f)?.let { return it }

            val hullIdx = MatOfInt()
            Imgproc.convexHull(contour, hullIdx)
            val hullPts = hullIdx.toArray().map { contour.toArray()[it] }.toTypedArray()
            hullIdx.release()
            val hull2f = MatOfPoint2f(*hullPts)
            try {
                fourCorners(hull2f)?.let { return it }
            } finally {
                hull2f.release()
            }

            val box = Imgproc.minAreaRect(c2f)
            val pts = arrayOfNulls<Point>(4)
            box.points(pts)
            return pts.filterNotNull().toTypedArray().takeIf { it.size == 4 }
        } finally {
            c2f.release()
        }
    }

    private fun fourCorners(c2f: MatOfPoint2f): Array<Point>? {
        val arc = Imgproc.arcLength(c2f, true)
        for (eps in doubleArrayOf(0.02, 0.035, 0.05)) {
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, eps * arc, true)
            val pts = approx.toArray()
            approx.release()
            if (pts.size == 4) return pts
        }
        return null
    }

    private fun warp(rgba: Mat, corners: Array<Point>, scratch: MutableList<Mat>): Mat {
        val (tl, tr, br, bl) = corners
        val w = maxOf(distance(tl, tr), distance(bl, br)).toInt().coerceAtLeast(1)
        val h = maxOf(distance(tl, bl), distance(tr, br)).toInt().coerceAtLeast(1)

        val srcQuad = MatOfPoint2f(tl, tr, br, bl).also { scratch += it }
        val dstQuad = MatOfPoint2f(
            Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0),
        ).also { scratch += it }
        val transform = Imgproc.getPerspectiveTransform(srcQuad, dstQuad).also { scratch += it }
        val warped = Mat().also { scratch += it }
        Imgproc.warpPerspective(rgba, warped, transform, Size(w.toDouble(), h.toDouble()))
        return warped
    }

    private fun binarise(mat: Mat, scratch: MutableList<Mat>): Mat {
        val gray = Mat().also { scratch += it }
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(gray, gray)
        val bw = Mat().also { scratch += it }
        Imgproc.adaptiveThreshold(
            gray, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 15, 10.0,
        )
        val rgba = Mat().also { scratch += it }
        Imgproc.cvtColor(bw, rgba, Imgproc.COLOR_GRAY2RGBA)
        return rgba
    }

    private class Rule(val along: DoubleArray, val across: DoubleArray)

    private fun detectRules(
        gray: Mat,
        axis: Int,
        minSpanFrac: Double,
        kernel: Mat,
        scratch: MutableList<Mat>,
    ): List<Rule> {
        val w = gray.cols()
        val h = gray.rows()
        val bin = Mat().also { scratch += it }
        Imgproc.adaptiveThreshold(
            gray, bin, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0,
        )
        val op = Mat().also { scratch += it }
        Imgproc.morphologyEx(bin, op, Imgproc.MORPH_OPEN, kernel)
        val labels = Mat().also { scratch += it }
        val stats = Mat().also { scratch += it }
        val centroids = Mat().also { scratch += it }
        val n = Imgproc.connectedComponentsWithStats(op, labels, stats, centroids)
        val dim = if (axis == 0) w else h
        val rules = mutableListOf<Rule>()
        for (i in 1 until n) {
            val span = stats.get(i, if (axis == 0) Imgproc.CC_STAT_WIDTH else Imgproc.CC_STAT_HEIGHT)[0]
            if (span < minSpanFrac * dim) continue
            val mask = Mat().also { scratch += it }
            Core.compare(labels, Scalar(i.toDouble()), mask, Core.CMP_EQ)
            val pts = MatOfPoint().also { scratch += it }
            Core.findNonZero(mask, pts)
            val sum = HashMap<Int, DoubleArray>()
            for (p in pts.toArray()) {
                val along = (if (axis == 0) p.x else p.y).toInt()
                val across = if (axis == 0) p.y else p.x
                val acc = sum.getOrPut(along) { DoubleArray(2) }
                acc[0] += across
                acc[1] += 1.0
            }
            if (sum.size < 8) continue
            val keys = sum.keys.sorted()
            rules += Rule(
                DoubleArray(keys.size) { keys[it].toDouble() },
                DoubleArray(keys.size) { sum[keys[it]]!!.let { a -> a[0] / a[1] } },
            )
        }
        return rules
    }

    private fun evalRule(r: Rule, along: Double): Double {
        val a = r.along
        val last = a.size - 1
        if (along <= a[0]) return r.across[0]
        if (along >= a[last]) return r.across[last]
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (a[mid] <= along) lo = mid else hi = mid
        }
        val t = (along - a[lo]) / (a[hi] - a[lo])
        return r.across[lo] + t * (r.across[hi] - r.across[lo])
    }

    private fun dewarpByTps(rgba: Mat, scratch: MutableList<Mat>): Mat? {
        val longSide = maxOf(rgba.rows(), rgba.cols()).toDouble()
        val s = if (longSide > RULE_DETECT_PX) RULE_DETECT_PX / longSide else 1.0
        val small = Mat().also { scratch += it }
        if (s < 1.0) Imgproc.resize(rgba, small, Size(rgba.cols() * s, rgba.rows() * s)) else rgba.copyTo(small)
        val w = small.cols()
        val h = small.rows()
        val gray = Mat().also { scratch += it }
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)

        val hk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size((w / 12).toDouble(), 1.0)).also { scratch += it }
        val vk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, (h / 28).toDouble())).also { scratch += it }
        var hRules = detectRules(gray, 0, 0.30, hk, scratch)
        val vRules = detectRules(gray, 1, 0.10, vk, scratch)
        if (hRules.size < MIN_H_RULES || vRules.size < MIN_V_RULES) return null
        if (hRules.size > MAX_TPS_H) {
            val step = hRules.size.toDouble() / MAX_TPS_H
            hRules = (0 until MAX_TPS_H).map { hRules[(it * step).toInt()] }
        }

        val ti = hRules.map { it.across.average() }
        val sj = vRules.map { it.across.average() }
        val d = maxOf(w, h).toDouble()
        val n = ti.size * sj.size
        val tpx = DoubleArray(n); val tpy = DoubleArray(n)
        val obx = DoubleArray(n); val oby = DoubleArray(n)
        var k = 0
        for (i in hRules.indices) for (j in vRules.indices) {
            tpx[k] = sj[j] / d; tpy[k] = ti[i] / d
            obx[k] = evalRule(vRules[j], ti[i]) / d
            oby[k] = evalRule(hRules[i], sj[j]) / d
            k++
        }
        val fx = TpsField.fit(tpx, tpy, obx, TPS_SMOOTH)
        val fy = TpsField.fit(tpx, tpy, oby, TPS_SMOOTH)

        val yMin = ti.minOrNull() ?: 0.0
        val yMax = ti.maxOrNull() ?: h.toDouble()
        val fade = FADE_FRAC * h
        val maxDx = MAX_DISP_FRAC * w
        val maxDy = MAX_DISP_FRAC * h
        val xMax = (w - 1).toDouble()
        val yMaxPx = (h - 1).toDouble()

        val wd = maxOf(w / REMAP_DECIMATE, 2)
        val hd = maxOf(h / REMAP_DECIMATE, 2)
        val mapXs = Mat(hd, wd, CvType.CV_32F).also { scratch += it }
        val mapYs = Mat(hd, wd, CvType.CV_32F).also { scratch += it }
        val rowX = FloatArray(wd)
        val rowY = FloatArray(wd)
        for (id in 0 until hd) {
            val sy = id.toDouble() * (h - 1) / (hd - 1)
            val outside = when {
                sy < yMin -> yMin - sy
                sy > yMax -> sy - yMax
                else -> 0.0
            }
            val wgt = (1.0 - outside / fade).coerceIn(0.0, 1.0)
            for (jd in 0 until wd) {
                val sx = jd.toDouble() * (w - 1) / (wd - 1)
                val dx = (fx.eval(sx / d, sy / d) * d - sx).coerceIn(-maxDx, maxDx) * wgt
                val dy = (fy.eval(sx / d, sy / d) * d - sy).coerceIn(-maxDy, maxDy) * wgt
                rowX[jd] = (sx + dx).coerceIn(0.0, xMax).toFloat()
                rowY[jd] = (sy + dy).coerceIn(0.0, yMaxPx).toFloat()
            }
            mapXs.put(id, 0, rowX)
            mapYs.put(id, 0, rowY)
        }
        val mapX = Mat().also { scratch += it }
        val mapY = Mat().also { scratch += it }
        Imgproc.resize(mapXs, mapX, Size(rgba.cols().toDouble(), rgba.rows().toDouble()))
        Imgproc.resize(mapYs, mapY, Size(rgba.cols().toDouble(), rgba.rows().toDouble()))
        Core.multiply(mapX, Scalar(1.0 / s), mapX)
        Core.multiply(mapY, Scalar(1.0 / s), mapY)
        val out = Mat().also { scratch += it }
        Imgproc.remap(rgba, out, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        return out
    }

    private fun whitenFinish(rgba: Mat, scratch: MutableList<Mat>): Mat {
        val bgr = Mat().also { scratch += it }
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

        val gray0 = Mat().also { scratch += it }
        Imgproc.cvtColor(bgr, gray0, Imgproc.COLOR_BGR2GRAY)
        val ch = ArrayList<Mat>().also { Core.split(bgr, it) }.onEach { scratch += it }
        val minCh = Mat().also { scratch += it }
        Core.min(ch[0], ch[1], minCh)
        Core.min(minCh, ch[2], minCh)
        val glare = Mat().also { scratch += it }
        Core.inRange(minCh, Scalar(237.0), Scalar(255.0), glare)
        val glareG = Mat().also { scratch += it }
        Imgproc.threshold(gray0, glareG, 244.0, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_and(glare, glareG, glare)
        val dk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)).also { scratch += it }
        Imgproc.dilate(glare, glare, dk, Point(-1.0, -1.0), 2)
        val clean = Mat().also { scratch += it }
        if (Core.countNonZero(glare) > 0) Photo.inpaint(bgr, glare, clean, 5.0, Photo.INPAINT_TELEA) else bgr.copyTo(clean)

        val lab = Mat().also { scratch += it }
        Imgproc.cvtColor(clean, lab, Imgproc.COLOR_BGR2Lab)
        val lc = ArrayList<Mat>().also { Core.split(lab, it) }.onEach { scratch += it }
        Core.add(lc[1], Scalar(128.0 - medianOf(lc[1])), lc[1])
        Core.add(lc[2], Scalar(128.0 - medianOf(lc[2])), lc[2])
        Core.merge(lc, lab)
        val wb = Mat().also { scratch += it }
        Imgproc.cvtColor(lab, wb, Imgproc.COLOR_Lab2BGR)

        val gray = Mat().also { scratch += it }
        Imgproc.cvtColor(wb, gray, Imgproc.COLOR_BGR2GRAY)
        val grayF = Mat().also { scratch += it }
        gray.convertTo(grayF, CvType.CV_32F)
        val local = Mat().also { scratch += it }
        Imgproc.blur(grayF, local, Size(51.0, 51.0))
        val ink = Mat().also { scratch += it }
        Core.subtract(local, grayF, ink)
        Core.subtract(ink, Scalar(12.0), ink)
        ink.convertTo(ink, -1, 1.0 / 26.0)
        val hsv = Mat().also { scratch += it }
        Imgproc.cvtColor(wb, hsv, Imgproc.COLOR_BGR2HSV)
        val hc = ArrayList<Mat>().also { Core.split(hsv, it) }.onEach { scratch += it }
        val col = Mat().also { scratch += it }
        hc[1].convertTo(col, CvType.CV_32F)
        Core.subtract(col, Scalar(30.0), col)
        col.convertTo(col, -1, 1.0 / 30.0)
        val content = Mat().also { scratch += it }
        Core.max(ink, col, content)
        Imgproc.GaussianBlur(content, content, Size(0.0, 0.0), 1.0)
        Imgproc.threshold(content, content, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        Imgproc.threshold(content, content, 0.0, 0.0, Imgproc.THRESH_TOZERO)

        val srcv = Mat().also { scratch += it }
        wb.convertTo(srcv, -1, 1.12, -25.0 * 1.12)
        val ublur = Mat().also { scratch += it }
        Imgproc.GaussianBlur(srcv, ublur, Size(0.0, 0.0), 1.2)
        Core.addWeighted(srcv, 1.30, ublur, -0.30, 0.0, srcv)

        val srcvF = Mat().also { scratch += it }
        srcv.convertTo(srcvF, CvType.CV_32F)
        val c3 = Mat().also { scratch += it }
        Imgproc.cvtColor(content, c3, Imgproc.COLOR_GRAY2BGR)
        val paperTerm = Mat().also { scratch += it }
        c3.convertTo(paperTerm, -1, 255.0)
        val whiteFull = Mat(c3.size(), c3.type(), Scalar.all(255.0)).also { scratch += it }
        Core.subtract(whiteFull, paperTerm, paperTerm)
        Core.multiply(srcvF, c3, srcvF)
        Core.add(srcvF, paperTerm, srcvF)
        val bgrOut = Mat().also { scratch += it }
        srcvF.convertTo(bgrOut, CvType.CV_8U)

        val hsv2 = Mat().also { scratch += it }
        Imgproc.cvtColor(bgrOut, hsv2, Imgproc.COLOR_BGR2HSV)
        val hc2 = ArrayList<Mat>().also { Core.split(hsv2, it) }.onEach { scratch += it }
        hc2[1].convertTo(hc2[1], -1, 1.7)
        Core.merge(hc2, hsv2)
        Imgproc.cvtColor(hsv2, bgrOut, Imgproc.COLOR_HSV2BGR)

        val bgray = Mat().also { scratch += it }
        Imgproc.cvtColor(bgrOut, bgray, Imgproc.COLOR_BGR2GRAY)
        val bsv = Mat().also { scratch += it }
        Imgproc.cvtColor(bgrOut, bsv, Imgproc.COLOR_BGR2HSV)
        val bsat = ArrayList<Mat>().also { Core.split(bsv, it) }.onEach { scratch += it }
        val runs = Mat().also { scratch += it }
        Imgproc.threshold(bgray, runs, 150.0, 255.0, Imgproc.THRESH_BINARY_INV)
        Imgproc.morphologyEx(runs, runs, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 1.0)).also { scratch += it })
        Imgproc.morphologyEx(runs, runs, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(22.0, 1.0)).also { scratch += it })
        val colored = Mat().also { scratch += it }
        Imgproc.threshold(bsat[1], colored, 45.0, 255.0, Imgproc.THRESH_BINARY)
        val prot = Mat().also { scratch += it }
        Core.max(runs, colored, prot)
        Imgproc.dilate(prot, prot, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(27.0, 27.0)).also { scratch += it }, Point(-1.0, -1.0), 1)
        val bh = bgrOut.rows()
        val bw = bgrOut.cols()
        val border = Mat.zeros(bh, bw, CvType.CV_8U).also { scratch += it }
        val by = (BORDER_FRAC * bh).toInt().coerceAtLeast(1)
        val bx = (BORDER_FRAC * bw).toInt().coerceAtLeast(1)
        border.rowRange(0, by).setTo(Scalar(255.0))
        border.rowRange(bh - by, bh).setTo(Scalar(255.0))
        border.colRange(0, bx).setTo(Scalar(255.0))
        border.colRange(bw - bx, bw).setTo(Scalar(255.0))
        val garbage = Mat().also { scratch += it }
        Core.bitwise_not(prot, garbage)
        Core.bitwise_and(garbage, border, garbage)
        Imgproc.GaussianBlur(garbage, garbage, Size(0.0, 0.0), 5.0)
        val gf = Mat().also { scratch += it }
        garbage.convertTo(gf, CvType.CV_32F, 1.0 / 255.0)
        val gf3 = Mat().also { scratch += it }
        Imgproc.cvtColor(gf, gf3, Imgproc.COLOR_GRAY2BGR)
        val bf = Mat().also { scratch += it }
        bgrOut.convertTo(bf, CvType.CV_32F)
        val inv = Mat(gf3.size(), gf3.type(), Scalar.all(1.0)).also { scratch += it }
        Core.subtract(inv, gf3, inv)
        Core.multiply(bf, inv, bf)
        val wterm = Mat().also { scratch += it }
        gf3.convertTo(wterm, -1, 255.0)
        Core.add(bf, wterm, bf)
        bf.convertTo(bgrOut, CvType.CV_8U)

        val outRgba = Mat().also { scratch += it }
        Imgproc.cvtColor(bgrOut, outRgba, Imgproc.COLOR_BGR2RGBA)
        return outRgba
    }

    private fun medianOf(chan: Mat): Double {
        val hist = Mat()
        Imgproc.calcHist(listOf(chan), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
        val half = chan.total() / 2
        var seen = 0L
        for (b in 0 until 256) {
            seen += hist.get(b, 0)[0].toLong()
            if (seen >= half) {
                hist.release()
                return b.toDouble()
            }
        }
        hist.release()
        return 128.0
    }

    internal fun orderCorners(pts: Array<Point>): Array<Point> {
        val bySum = pts.sortedBy { it.x + it.y }
        val byDiff = pts.sortedBy { it.y - it.x }
        return arrayOf(bySum.first(), byDiff.first(), bySum.last(), byDiff.last())
    }

    internal fun distance(a: Point, b: Point): Double = Math.hypot(a.x - b.x, a.y - b.y)

    private const val DETECT_MAX_PX = 720.0

    private const val UPSCALE_TARGET = 2400

    private const val RULE_DETECT_PX = 1600.0

    private const val MIN_H_RULES = 6

    private const val MIN_V_RULES = 5

    private const val MAX_TPS_H = 10

    private const val TPS_SMOOTH = 2.0

    private const val FADE_FRAC = 0.10

    private const val MAX_DISP_FRAC = 0.12

    private const val BORDER_FRAC = 0.09

    private const val REMAP_DECIMATE = 8

    private const val ILLUMINATION_SIGMA = 90.0

    private const val MIN_PAGE_FRACTION = 0.12

    private const val MAX_PAGE_FRACTION = 0.97
}

/**
 * Выпрямленная страница и четыре её угла на развёрнутом кадре (#1332).
 *
 * [page] — `null`, когда обратного хода нет: страницу расправили по линиям разлиновки, а не
 * по углам.
 */
class Straightened(val bitmap: Bitmap, val page: PageQuad?)
