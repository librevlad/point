package com.point.executors

import android.graphics.Bitmap
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

/**
 * CamScanner-grade document scan via OpenCV: find the page, correct its perspective, then
 * adaptive-threshold to crisp black-on-white that survives uneven lighting and shadows (#45).
 *
 * Native and heavy, so it is gated by [available]: when OpenCV can't load, the realizer reports
 * unavailable and the Resolver runs the pure-Otsu [ScanFilter] instead. All [Mat]s are released
 * to avoid leaking native memory.
 */
object OpenCvScan {

    /** True once the bundled OpenCV native library has loaded. Evaluated lazily, cached. */
    val available: Boolean by lazy { runCatching { OpenCVLoader.initLocal() }.getOrDefault(false) }

    /** The full pipeline: an upright, binarised scan. The caller owns [src]. */
    fun process(src: Bitmap): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val scratch = mutableListOf(rgba)
        try {
            val document = detectDocument(rgba, scratch) ?: rgba // no clear page → scan the whole frame
            val scanned = binarise(document, scratch)
            val out = Bitmap.createBitmap(scanned.cols(), scanned.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(scanned, out)
            return out
        } finally {
            scratch.forEach { it.release() }
        }
    }

    /**
     * «Скан» (#200): a flat-bed-quality colour scan. Straighten the page by its table-line
     * intersections ([dewarpByTps]) — falling back to corner-perspective ([detectDocument]) when a
     * document has too few rules — then finish to pure-white paper with live colour, clean edges
     * ([whitenFinish]) and upscale. For handwriting, coloured forms and stamps where binarisation
     * throws away information the eye (and later OCR) needs. The caller owns [src].
     *
     * `suspend` не ради потока, а ради канала стадий (#288): на телефоне владельца этот рецепт идёт
     * десятки секунд, и до этого среза человек всё это время видел один голый счётчик. Стадии здесь —
     * настоящие ветки конвейера, а не отсчёт по часам: сказано ровно то, что делается сейчас
     * («Увеличиваю» не произносится, когда снимок уже крупный, — см. [upscale]).
     */
    suspend fun enhance(src: Bitmap): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val scratch = mutableListOf(rgba)
        try {
            reportStage("Ищу страницу на снимке")
            val straight = dewarpByTps(rgba, scratch)   // straighten by table-line intersections (TPS)…
                ?: detectDocument(rgba, scratch)        // …else correct perspective by page corners…
                ?: rgba                                 // …else take the frame as-is
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

    /** Upscale a small scan toward [UPSCALE_TARGET] on its long side (cubic); a big one is left as-is.
     *  Стадия объявляется ПОСЛЕ проверки размера (#288): на крупном снимке увеличения не происходит,
     *  и сказать «Увеличиваю» значило бы описать шаг, которого нет. */
    private suspend fun upscale(mat: Mat, scratch: MutableList<Mat>): Mat {
        val longSide = maxOf(mat.rows(), mat.cols())
        if (longSide >= UPSCALE_TARGET) return mat
        reportStage("Увеличиваю")
        val s = UPSCALE_TARGET.toDouble() / longSide
        val up = Mat().also { scratch += it }
        Imgproc.resize(mat, up, Size(mat.cols() * s, mat.rows() * s), 0.0, 0.0, Imgproc.INTER_CUBIC)
        return up
    }

    /**
     * Find the page and perspective-warp it upright; null when no page-like shape exists.
     *
     * #116 hardening (the owner's photos came back with skewed, cropped corners):
     * - detection runs on a ≤720px copy (noise-robust and fast), corners scale back up;
     * - pass 1 finds the paper as a bright mass (illumination-flattened Otsu), so a cast
     *   shadow can never glue itself to the page outline; pass 2 falls back to an edge
     *   map (median-adaptive Canny + CLOSE) for tables that match the paper's brightness;
     * - only EXTERNAL contours compete — the receipt's inner rules can never win again;
     * - a near-full-frame quad is rejected (warping the whole photo fixes nothing);
     * - fallback ladder per contour: exact 4-corner approx → convex hull approx →
     *   minAreaRect, so a hidden or rounded corner still yields a page.
     */
    private fun detectDocument(rgba: Mat, scratch: MutableList<Mat>): Mat? {
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

        // Pass 1 — the paper as a BRIGHT mass (Otsu): paper is near-white, its cast
        // shadow is dark, so the shadow cannot glue itself to the page the way it does
        // on an edge map. This is what fixes the owner's skewed-corner photos (#116).
        // A global threshold dies under uneven light (a lamp-side page edge drops below
        // Otsu and the corner gets cropped), so brightness is first flattened by dividing
        // the frame by its own large-blur estimate of the illumination.
        val illumination = Mat().also { scratch += it }
        Imgproc.GaussianBlur(gray, illumination, Size(0.0, 0.0), ILLUMINATION_SIGMA)
        val flat = Mat().also { scratch += it }
        Core.divide(blurred, illumination, flat, 128.0, CvType.CV_8U)
        val otsu = Mat().also { scratch += it }
        Imgproc.threshold(flat, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(otsu, otsu, Imgproc.MORPH_OPEN, kernel)
        var corners = quadFromMask(otsu, frameArea, scratch)

        // Pass 2 — the classic edge map, for low-contrast tables where Otsu merges.
        if (corners == null) {
            val median = medianBrightness(blurred)
            val edges = Mat().also { scratch += it }
            Imgproc.Canny(blurred, edges, 0.66 * median, 1.33 * median)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            corners = quadFromMask(edges, frameArea, scratch)
        }
        if (corners == null) return null

        val scaledBack = corners.map { Point(it.x / downscale, it.y / downscale) }.toTypedArray()
        return warp(rgba, orderCorners(scaledBack), scratch)
    }

    /** Top external contours of a binary mask → the first page-like quad, else null. */
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

    /** The median of a grayscale frame — the anchor for adaptive Canny thresholds. */
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

    /** A page candidate: sane area (not the whole frame), 4 corners via the fallback ladder. */
    private fun pageQuad(contour: MatOfPoint, frameArea: Double): Array<Point>? {
        val area = Imgproc.contourArea(contour)
        if (area < MIN_PAGE_FRACTION * frameArea || area > MAX_PAGE_FRACTION * frameArea) return null

        val c2f = MatOfPoint2f(*contour.toArray())
        try {
            // 1) the classic: the contour itself approximates to exactly 4 corners
            fourCorners(c2f)?.let { return it }
            // 2) a torn/partly hidden outline: its convex hull often still has 4 corners
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
            // 3) last resort: the minimal rotated rectangle around the shape
            val box = Imgproc.minAreaRect(c2f)
            val pts = arrayOfNulls<Point>(4)
            box.points(pts)
            return pts.filterNotNull().toTypedArray().takeIf { it.size == 4 }
        } finally {
            c2f.release()
        }
    }

    /** approxPolyDP at growing tolerances; 4 convex points win, else null. */
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

    /** Perspective-transform the ordered [corners] (tl, tr, br, bl) to a straight rectangle. */
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

    /** Grey + adaptive threshold: crisp black text on white, robust to uneven lighting. */
    private fun binarise(mat: Mat, scratch: MutableList<Mat>): Mat {
        val gray = Mat().also { scratch += it }
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        // #200: lift faint ink (pencil, worn pen) with CLAHE before thresholding, so it survives the
        // binarisation instead of dropping to white — the same enhancement that rescued faint scans in ocr++.
        Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(gray, gray)
        val bw = Mat().also { scratch += it }
        Imgproc.adaptiveThreshold(
            gray, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 15, 10.0,
        )
        val rgba = Mat().also { scratch += it }
        Imgproc.cvtColor(bw, rgba, Imgproc.COLOR_GRAY2RGBA)
        return rgba
    }

    /** A detected table rule as a polyline: paired (along, across) coords in the working copy. */
    private class Rule(val along: DoubleArray, val across: DoubleArray)

    /**
     * Long, straight table rules. axis 0 = horizontal (across=y as a function of along=x),
     * axis 1 = vertical (across=x of along=y). Morphological OPEN with a long kernel isolates
     * the ruled lines; per connected component we average the across-coord at each along-coord.
     */
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
            Core.findNonZero(mask, pts) // only this rule's pixels — cheap
            val sum = HashMap<Int, DoubleArray>() // along -> [sumAcross, count]
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

    /** Value of a rule (polyline) at an along-coordinate, by linear interpolation (clamped to span). */
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

    /**
     * «Скан» geometry (#200): straighten the page from the GRID OF TABLE-LINE INTERSECTIONS via a
     * thin-plate spline ([TpsField]). Each horizontal×vertical rule crossing is a control point
     * mapped to a regular grid (columns to their mean x, rows to their mean y), so BOTH row
     * curvature and column/perspective are removed — where a per-rule global polynomial blew up
     * (~30-44 %). The field fades to zero outside the rule band and is clamped, then the full frame
     * is remapped. Returns null with too few rules — the caller falls back to corner-perspective.
     */
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
        if (hRules.size > MAX_TPS_H) {                        // cap control points = |H|·|V|
            val step = hRules.size.toDouble() / MAX_TPS_H
            hRules = (0 until MAX_TPS_H).map { hRules[(it * step).toInt()] }
        }

        val ti = hRules.map { it.across.average() }           // target row heights
        val sj = vRules.map { it.across.average() }           // target column x's
        val d = maxOf(w, h).toDouble()                        // normalise coords for TPS stability
        val n = ti.size * sj.size
        val tpx = DoubleArray(n); val tpy = DoubleArray(n)
        val obx = DoubleArray(n); val oby = DoubleArray(n)
        var k = 0
        for (i in hRules.indices) for (j in vRules.indices) {
            tpx[k] = sj[j] / d; tpy[k] = ti[i] / d            // control point on the regular target grid
            obx[k] = evalRule(vRules[j], ti[i]) / d           // observed x: vertical rule at that row height
            oby[k] = evalRule(hRules[i], sj[j]) / d           // observed y: horizontal rule at that column x
            k++
        }
        val fx = TpsField.fit(tpx, tpy, obx, TPS_SMOOTH)      // output(target) → source x
        val fy = TpsField.fit(tpx, tpy, oby, TPS_SMOOTH)      // output(target) → source y

        val yMin = ti.minOrNull() ?: 0.0
        val yMax = ti.maxOrNull() ?: h.toDouble()
        val fade = FADE_FRAC * h
        val maxDx = MAX_DISP_FRAC * w
        val maxDy = MAX_DISP_FRAC * h
        val xMax = (w - 1).toDouble()
        val yMaxPx = (h - 1).toDouble()

        // Sample the smooth field on a decimated grid (then resize up) — not every one of ~2M pixels.
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
            val wgt = (1.0 - outside / fade).coerceIn(0.0, 1.0) // 1 in the rule band, →0 in the margins
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

    /**
     * «Скан+» finisher (#200): a clean flat-bed look — pure-white paper, live colour.
     * Inpaint bright glare → white-balance (median a/b → 128) → mark ink relative to the LOCAL mean
     * (so uneven-lit paper still reads as background) and colour by saturation → blend the paper to
     * pure white while keeping crisp dark text and a saturation-boosted stamp. All OpenCV.
     */
    private fun whitenFinish(rgba: Mat, scratch: MutableList<Mat>): Mat {
        val bgr = Mat().also { scratch += it }
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

        // 1) inpaint bright specular glare (blown, low-saturation blobs)
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

        // 2) white balance: shift each of a/b so its (paper-dominated) median sits at 128
        val lab = Mat().also { scratch += it }
        Imgproc.cvtColor(clean, lab, Imgproc.COLOR_BGR2Lab)
        val lc = ArrayList<Mat>().also { Core.split(lab, it) }.onEach { scratch += it }
        Core.add(lc[1], Scalar(128.0 - medianOf(lc[1])), lc[1])
        Core.add(lc[2], Scalar(128.0 - medianOf(lc[2])), lc[2])
        Core.merge(lc, lab)
        val wb = Mat().also { scratch += it }
        Imgproc.cvtColor(lab, wb, Imgproc.COLOR_Lab2BGR)

        // 3) content mask: ink = darker than LOCAL mean; colour = saturated. In [0,1].
        val gray = Mat().also { scratch += it }
        Imgproc.cvtColor(wb, gray, Imgproc.COLOR_BGR2GRAY)
        val grayF = Mat().also { scratch += it }
        gray.convertTo(grayF, CvType.CV_32F)
        val local = Mat().also { scratch += it }
        Imgproc.blur(grayF, local, Size(51.0, 51.0))
        val ink = Mat().also { scratch += it }           // (local - 12 - gray) / 26
        Core.subtract(local, grayF, ink)
        Core.subtract(ink, Scalar(12.0), ink)
        ink.convertTo(ink, -1, 1.0 / 26.0)
        val hsv = Mat().also { scratch += it }
        Imgproc.cvtColor(wb, hsv, Imgproc.COLOR_BGR2HSV)
        val hc = ArrayList<Mat>().also { Core.split(hsv, it) }.onEach { scratch += it }
        val col = Mat().also { scratch += it }           // (S - 30) / 30
        hc[1].convertTo(col, CvType.CV_32F)
        Core.subtract(col, Scalar(30.0), col)
        col.convertTo(col, -1, 1.0 / 30.0)
        val content = Mat().also { scratch += it }
        Core.max(ink, col, content)
        Imgproc.GaussianBlur(content, content, Size(0.0, 0.0), 1.0)
        Imgproc.threshold(content, content, 1.0, 1.0, Imgproc.THRESH_TRUNC)   // clamp ≤ 1
        Imgproc.threshold(content, content, 0.0, 0.0, Imgproc.THRESH_TOZERO)  // clamp ≥ 0

        // 4) crisp ink source: ink contrast + unsharp
        val srcv = Mat().also { scratch += it }
        wb.convertTo(srcv, -1, 1.12, -25.0 * 1.12)       // (px - 25) * 1.12
        val ublur = Mat().also { scratch += it }
        Imgproc.GaussianBlur(srcv, ublur, Size(0.0, 0.0), 1.2)
        Core.addWeighted(srcv, 1.30, ublur, -0.30, 0.0, srcv)

        // 5) blend: paper → pure white, content → srcv (all float 3-ch)
        val srcvF = Mat().also { scratch += it }
        srcv.convertTo(srcvF, CvType.CV_32F)
        val c3 = Mat().also { scratch += it }
        Imgproc.cvtColor(content, c3, Imgproc.COLOR_GRAY2BGR)   // mask → 3-ch, values [0,1]
        val paperTerm = Mat().also { scratch += it }
        c3.convertTo(paperTerm, -1, 255.0)                     // 255 * content
        val whiteFull = Mat(c3.size(), c3.type(), Scalar.all(255.0)).also { scratch += it }
        Core.subtract(whiteFull, paperTerm, paperTerm)         // 255 * (1 - content)
        Core.multiply(srcvF, c3, srcvF)                        // srcv * content
        Core.add(srcvF, paperTerm, srcvF)
        val bgrOut = Mat().also { scratch += it }
        srcvF.convertTo(bgrOut, CvType.CV_8U)

        // 6) boost colour saturation — paper S≈0 untouched, stamp/pen go vivid
        val hsv2 = Mat().also { scratch += it }
        Imgproc.cvtColor(bgrOut, hsv2, Imgproc.COLOR_BGR2HSV)
        val hc2 = ArrayList<Mat>().also { Core.split(hsv2, it) }.onEach { scratch += it }
        hc2[1].convertTo(hc2[1], -1, 1.7)
        Core.merge(hc2, hsv2)
        Imgproc.cvtColor(hsv2, bgrOut, Imgproc.COLOR_HSV2BGR)

        // 7) убрать мусор по краям (плёночная бахрома/тёмные кромки): добел внешней margin-полосы там,
        //    где нет длинных ГОРИЗОНТАЛЬНЫХ структур (текст-строк/линий) и нет цвета — контент защищён.
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
        Imgproc.GaussianBlur(garbage, garbage, Size(0.0, 0.0), 5.0)      // feather → soft alpha
        val gf = Mat().also { scratch += it }
        garbage.convertTo(gf, CvType.CV_32F, 1.0 / 255.0)
        val gf3 = Mat().also { scratch += it }
        Imgproc.cvtColor(gf, gf3, Imgproc.COLOR_GRAY2BGR)
        val bf = Mat().also { scratch += it }
        bgrOut.convertTo(bf, CvType.CV_32F)
        val inv = Mat(gf3.size(), gf3.type(), Scalar.all(1.0)).also { scratch += it }
        Core.subtract(inv, gf3, inv)                                     // 1 − alpha
        Core.multiply(bf, inv, bf)                                       // bgr · (1 − alpha)
        val wterm = Mat().also { scratch += it }
        gf3.convertTo(wterm, -1, 255.0)                                  // 255 · alpha
        Core.add(bf, wterm, bf)
        bf.convertTo(bgrOut, CvType.CV_8U)

        val outRgba = Mat().also { scratch += it }
        Imgproc.cvtColor(bgrOut, outRgba, Imgproc.COLOR_BGR2RGBA)
        return outRgba
    }

    /** Median of a single-channel 8U [chan] via a 256-bin histogram (as [medianBrightness]). */
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

    /** Sort four corners into (top-left, top-right, bottom-right, bottom-left). Pure — testable. */
    internal fun orderCorners(pts: Array<Point>): Array<Point> {
        val bySum = pts.sortedBy { it.x + it.y }   // tl smallest, br largest
        val byDiff = pts.sortedBy { it.y - it.x }  // tr smallest, bl largest
        return arrayOf(bySum.first(), byDiff.first(), bySum.last(), byDiff.last())
    }

    internal fun distance(a: Point, b: Point): Double = Math.hypot(a.x - b.x, a.y - b.y)

    private const val DETECT_MAX_PX = 720.0
    /** «Скан+» upscales a small scan toward this long-side resolution (cubic); a larger one is untouched. */
    private const val UPSCALE_TARGET = 2400
    /** Rule detection + displacement field run on this long-side working copy (#200 dewarp). */
    private const val RULE_DETECT_PX = 1600.0
    /** Fewer horizontal rules than this → no rule-dewarp; fall back to corner-perspective. */
    private const val MIN_H_RULES = 6
    /** Need at least this many vertical rules too — the TPS grid needs both axes to straighten columns. */
    private const val MIN_V_RULES = 5
    /** Cap horizontal rules used for TPS control points (|H|·|V|) so the linear solve stays small. */
    private const val MAX_TPS_H = 10
    /** TPS regularization (smoothing) — tolerates noisy detected intersections. */
    private const val TPS_SMOOTH = 2.0
    /** Displacement fades to zero over this fraction of height beyond the rule band (no margin smear). */
    private const val FADE_FRAC = 0.10
    /** Hard cap on per-axis displacement (fraction of the dimension) — a safety net against blow-ups. */
    private const val MAX_DISP_FRAC = 0.12
    /** Outer margin band (fraction of each side) whitened where free of content — edge/corner garbage. */
    private const val BORDER_FRAC = 0.09
    /** The smooth field is sampled every this-many px, then the remap is resized up. */
    private const val REMAP_DECIMATE = 8
    /** Illumination blur: wide enough (~1/8 frame) to smooth a lamp gradient, not page detail. */
    private const val ILLUMINATION_SIGMA = 90.0
    /** A page smaller than this fraction of the frame is clutter… */
    private const val MIN_PAGE_FRACTION = 0.12
    /** …and one this close to the full frame means detection found nothing useful. */
    private const val MAX_PAGE_FRACTION = 0.97
}
