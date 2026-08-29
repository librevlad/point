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

    fun decodeUpright(path: String, maxPx: Int = PROCESS_MAX_PX): Bitmap? = uprightFrame(path, maxPx)?.bitmap

    fun decodeThumbnail(path: String, maxPx: Int): Bitmap? = uprightFrame(path, maxPx)?.bitmap

    /**
     * Тот же развёрнутый кадр — и то, что с ним по дороге сделали (#1046).
     *
     * Во сколько раз кадр мельче файла и на сколько его развернули, знает только декодер. Тот,
     * кто возвращает прочитанное на снимок человека, обязан спросить это здесь, а не считать
     * заново своей копией той же логики: своя копия и забыла про метку камеры.
     */
    fun uprightFrame(path: String, maxPx: Int): UprightFrame? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val shrink = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = shrink })
            ?: return null
        val degrees = runCatching {
            rotationDegrees(
                ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            )
        }.getOrDefault(0f)
        return UprightFrame(turned(decoded, degrees), shrink, degrees)
    }

    fun sampleSize(width: Int, height: Int, maxPx: Int): Int =
        com.point.core.flow.sampleSizeFor(width, height, maxPx)

    fun rotationDegrees(orientation: Int): Float = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

    /**
     * Повернуть кадр на [degrees] и отпустить исходный.
     *
     * Тот же ход в обе стороны: им кадр разворачивают по метке камеры и им же копию кладут
     * обратно в раскладку файла.
     */
    fun turned(decoded: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return decoded
        val rotated = Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(degrees) }, true,
        )
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }
}

/**
 * Кадр, развёрнутый по метке камеры (#1046).
 *
 * [shrink] — во сколько раз он мельче файла, [degrees] — на сколько его повернули. Оба числа
 * нужны тому, кто возвращает прочитанное на снимок человека.
 */
class UprightFrame(val bitmap: Bitmap, val shrink: Int, val degrees: Float)
