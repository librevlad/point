package com.point.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface

/**
 * Decode [path] subsampled so its long edge stays under 2×[maxPx] (OOM guard on big photos), then
 * rotate it upright by EXIF — a phone photo stores its rotation in EXIF and keeps the pixels
 * sideways, which throws off any model fed the raw bitmap (the OCR-gibberish lesson). Returns null
 * if the file can't be decoded.
 */
internal fun decodeBoundedUpright(path: String, maxPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    var edge = maxOf(bounds.outWidth, bounds.outHeight)
    while (edge / 2 >= maxPx) {
        edge /= 2
        sample *= 2
    }
    val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val orientation = runCatching {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> return decoded
    }
    val rotated = Bitmap.createBitmap(
        decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true,
    )
    if (rotated != decoded) decoded.recycle()
    return rotated
}
