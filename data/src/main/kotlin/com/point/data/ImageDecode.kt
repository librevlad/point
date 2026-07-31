package com.point.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.point.core.flow.FrameTransform

/**
 * Кадр для экрана выделения (#259): EXIF-выпрямленный битмап **плюс преобразование его координат
 * в сырой файл**. Атомы слоя живут в сыром кадре, человеку показывается выпрямленная копия —
 * без преобразования подсветка захвата рисовалась бы мимо слов, а рамка жеста уезжала бы в
 * чужое место файла.
 */
data class SelectionFrame(val bitmap: Bitmap, val transform: FrameTransform)

/**
 * Decode [path] subsampled so its long edge stays under 2×[maxPx] (OOM guard on big photos), then
 * rotate it upright by EXIF — a phone photo stores its rotation in EXIF and keeps the pixels
 * sideways, which throws off any model fed the raw bitmap (the OCR-gibberish lesson). Returns null
 * if the file can't be decoded.
 */
internal fun decodeBoundedUpright(path: String, maxPx: Int): Bitmap? =
    decodeSelectionFrame(path, maxPx)?.bitmap

/** [decodeBoundedUpright], который не выбрасывает знание о том, как именно он крутил и жал:
 *  та же копия + её [FrameTransform]. Одна реализация на обе дороги — разъехавшиеся копии
 *  дали бы подсветку по одной геометрии поверх картинки с другой. */
fun decodeSelectionFrame(path: String, maxPx: Int): SelectionFrame? {
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
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
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
