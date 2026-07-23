package com.point.executors

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
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

    /** Largest 4-corner contour covering enough of the frame → perspective-warped; else null. */
    private fun detectDocument(rgba: Mat, scratch: MutableList<Mat>): Mat? {
        val gray = Mat().also { scratch += it }
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val blurred = Mat().also { scratch += it }
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        val edges = Mat().also { scratch += it }
        Imgproc.Canny(blurred, edges, 75.0, 200.0)
        Imgproc.dilate(edges, edges, Mat())

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            edges, contours, Mat().also { scratch += it },
            Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val frameArea = rgba.rows().toDouble() * rgba.cols()
        val corners = contours
            .sortedByDescending { Imgproc.contourArea(it) }
            .take(6)
            .firstNotNullOfOrNull { contour -> pageQuad(contour, frameArea) }
            ?: return null

        return warp(rgba, orderCorners(corners), scratch)
    }

    /** A contour that approximates to 4 convex corners covering ≥20% of the frame is a page. */
    private fun pageQuad(contour: MatOfPoint, frameArea: Double): Array<Point>? {
        val c2f = MatOfPoint2f(*contour.toArray())
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(c2f, approx, 0.02 * Imgproc.arcLength(c2f, true), true)
        val pts = approx.toArray()
        c2f.release()
        approx.release()
        val big = pts.size == 4 && Imgproc.contourArea(MatOfPoint(*pts)) > 0.2 * frameArea
        return if (big) pts else null
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
}
