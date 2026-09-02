package com.point.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.point.core.flow.FrameTransform
import com.point.core.flow.UprightAngle
import javax.inject.Inject

data class SelectionFrame(val bitmap: Bitmap, val transform: FrameTransform)

internal fun decodeBoundedUpright(path: String, maxPx: Int): Bitmap? =
    decodeSelectionFrame(path, maxPx)?.bitmap

fun decodeSelectionFrame(path: String, maxPx: Int): SelectionFrame? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    // Ужатие считается тем же правилом, что и у остальных декодеров (#1013): этот
    // FrameTransform получают и выделение, и замазывание, и чтение на устройстве —
    // расхождение хотя бы на шаг сдвинуло бы метку поиска с найденной строки.
    val sample = com.point.core.flow.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPx)
    val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val degrees = uprightDegreesOf(path)
    val upright = if (degrees == 0) {
        decoded
    } else {
        Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(degrees.toFloat()) }, true,
        ).also { if (it != decoded) decoded.recycle() }
    }
    return SelectionFrame(
        bitmap = upright,
        transform = FrameTransform(
            sample = sample,
            rotationDegrees = degrees,
            uprightWidth = upright.width,
            uprightHeight = upright.height,
        ),
    )
}

/**
 * На сколько развёрнут кадр в этом файле по метке камеры.
 *
 * Тот же ответ получает экран выделения, поэтому вырезанное из файла встаёт ровно так же, как
 * то, что человек видел, когда выделял.
 */
fun uprightDegreesOf(path: String): Int {
    val orientation = runCatching {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}

class ExifUprightAngle @Inject constructor() : UprightAngle {
    override fun degreesOf(path: String): Int = uprightDegreesOf(path)
}

fun cropRegion(path: String, left: Int, top: Int, right: Int, bottom: Int): Bitmap? {
    @Suppress("DEPRECATION")
    val decoder = android.graphics.BitmapRegionDecoder.newInstance(path, false) ?: return null
    return try {
        val r = android.graphics.Rect(
            left.coerceAtLeast(0),
            top.coerceAtLeast(0),
            right.coerceAtMost(decoder.width),
            bottom.coerceAtMost(decoder.height),
        )
        if (r.width() < MIN_CROP_PX || r.height() < MIN_CROP_PX) null else decoder.decodeRegion(r, null)
    } finally {
        decoder.recycle()
    }
}

private const val MIN_CROP_PX = 16
