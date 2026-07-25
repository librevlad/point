package com.point.executors

import android.graphics.Bitmap
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
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

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
        val bw = Mat().also { scratch += it }
        Imgproc.adaptiveThreshold(
            gray, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 15, 10.0,
        )
        val rgba = Mat().also { scratch += it }
        Imgproc.cvtColor(bw, rgba, Imgproc.COLOR_GRAY2RGBA)
        return rgba
    }

    /** Sort four corners into (top-left, top-right, bottom-right, bottom-left). Pure — testable. */
    internal fun orderCorners(pts: Array<Point>): Array<Point> {
        val bySum = pts.sortedBy { it.x + it.y }   // tl smallest, br largest
        val byDiff = pts.sortedBy { it.y - it.x }  // tr smallest, bl largest
        return arrayOf(bySum.first(), byDiff.first(), bySum.last(), byDiff.last())
    }

    internal fun distance(a: Point, b: Point): Double = Math.hypot(a.x - b.x, a.y - b.y)

    private const val DETECT_MAX_PX = 720.0
    /** Illumination blur: wide enough (~1/8 frame) to smooth a lamp gradient, not page detail. */
    private const val ILLUMINATION_SIGMA = 90.0
    /** A page smaller than this fraction of the frame is clutter… */
    private const val MIN_PAGE_FRACTION = 0.12
    /** …and one this close to the full frame means detection found nothing useful. */
    private const val MAX_PAGE_FRACTION = 0.97
}
