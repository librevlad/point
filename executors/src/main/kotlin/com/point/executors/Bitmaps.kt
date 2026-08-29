package com.point.executors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface

object Bitmaps {

    const val PROCESS_MAX_PX = 1600

    const val SCAN_PLUS_MAX_PX = 2600

    /** Чем сохраняется обработанный снимок: одно качество на всю обработку, а не по копии. */
    const val JPEG_QUALITY = 92

    fun decodeUpright(path: String, maxPx: Int = PROCESS_MAX_PX): Bitmap? = decodeBounded(path, maxPx)

    fun decodeThumbnail(path: String, maxPx: Int): Bitmap? = decodeBounded(path, maxPx)

    private fun decodeBounded(path: String, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        return BitmapFactory.decodeFile(path, opts)?.let { uprighted(it, path) }
    }

    fun sampleSize(width: Int, height: Int, maxPx: Int): Int =
        com.point.core.flow.sampleSizeFor(width, height, maxPx)

    fun rotationDegrees(orientation: Int): Float = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

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
