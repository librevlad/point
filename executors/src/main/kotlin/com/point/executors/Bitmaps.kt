package com.point.executors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface

/**
 * Bitmap decode that respects a camera photo's EXIF orientation. Phones store the
 * sensor orientation in EXIF and leave the pixels sideways; a naïve `decodeFile`
 * therefore produces a rotated scan/PDF (#45). Everything that turns a shared photo
 * into pixels goes through here so scans come out upright.
 */
object Bitmaps {

    fun decodeUpright(path: String): Bitmap? {
        val decoded = BitmapFactory.decodeFile(path) ?: return null
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

    /** EXIF orientation → clockwise degrees needed to make the image upright. */
    fun rotationDegrees(orientation: Int): Float = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
}
