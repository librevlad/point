package com.point.executors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface

/**
 * Bitmap decode that respects a camera photo's EXIF orientation. Phones store the
 * sensor orientation in EXIF and leave the pixels sideways; a naïve `decodeFile`
 * therefore produces a rotated scan/PDF (#45). Everything that turns a shared photo
 * into pixels goes through here so scans — and history thumbnails (#56) — come out
 * upright.
 */
object Bitmaps {

    /** Working cap for full processing (scan / PDF / compress): the long edge is subsampled
     *  to under 2× this before decode, so a 48 MP photo can't OOM a weak device (#18). */
    const val PROCESS_MAX_PX = 1600

    /** Upright decode, subsampled to at most ~[maxPx] on the long edge (default: the
     *  processing cap) — for scans, PDF pages and compression. Bounds peak memory (#18). */
    fun decodeUpright(path: String, maxPx: Int = PROCESS_MAX_PX): Bitmap? = decodeBounded(path, maxPx)

    /**
     * Small upright preview for lists — the same bounded decode with a thumbnail cap, so a
     * 12 MP photo costs a few hundred KB instead of tens of MB (#56).
     */
    fun decodeThumbnail(path: String, maxPx: Int): Bitmap? = decodeBounded(path, maxPx)

    private fun decodeBounded(path: String, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        return BitmapFactory.decodeFile(path, opts)?.let { uprighted(it, path) }
    }

    /** Largest power-of-two subsample keeping the long edge ≥ [maxPx]. Pure — unit-tested. */
    fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        if (maxPx <= 0) return 1
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge / 2 >= maxPx) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }

    /** EXIF orientation → clockwise degrees needed to make the image upright. */
    fun rotationDegrees(orientation: Int): Float = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

    /** Rotate [decoded] by its EXIF orientation, recycling the source if a copy is made. */
    private fun uprighted(decoded: Bitmap, path: String): Bitmap {
        val degrees = runCatching {
            rotationDegrees(
                ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            )
        }.getOrDefault(0f)
        if (degrees == 0f) return decoded
        val rotated = Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(degrees) }, true,
        )
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }
}
