package com.point.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.point.core.flow.Box
import com.point.core.flow.EvidenceImage
import com.point.core.flow.ImageRedactor
import com.point.core.flow.Redaction
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Замазывание на снимке (#549): Android читает и пишет пиксели, решает — [Redaction].
 *
 * Снимок остаётся в своём разрешении: замазанное отдают дальше вместо исходника, и уменьшать
 * его человеку никто не обещал. Поэтому целиком в память он не поднимается — берутся и
 * возвращаются только пиксели обведённых мест. Полный кадр телефона (4000×3000 — это 48 МБ на
 * копию) в трёх копиях разом клал бы приложение ровно на том снимке, ради которого всё и
 * затевалось.
 *
 * PNG, а не JPEG: второе сжатие поверх исходного добавляло бы артефакты к тому, что человек
 * и так уже показал.
 *
 * Места — в пикселях файла (Focus живёт в сыром кадре, #1013), а результат ставится, как
 * снимок сняли: метка поворота в PNG не переезжает, и без разворота портретный снимок
 * выходил замазанным, но лежащим на боку (#1379). Разворот стоит ещё одну копию кадра:
 * две разом, а не три.
 */
class AndroidImageRedactor @Inject constructor() : ImageRedactor {

    override suspend fun hide(imagePath: String, places: List<Box>): EvidenceImage? =
        withContext(Dispatchers.IO) {
            if (places.isEmpty()) return@withContext null
            val page = BitmapFactory.decodeFile(imagePath, BitmapFactory.Options().apply { inMutable = true })
                ?: return@withContext null
            try {
                var covered = false
                places.forEach { place -> if (hidePlace(page, place)) covered = true }
                if (!covered) return@withContext null

                val upright = upright(page, imagePath)
                try {
                    val bytes = ByteArrayOutputStream()
                        .also { upright.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        .toByteArray()
                    EvidenceImage(bytes, upright.width, upright.height, extension = "png")
                } finally {
                    if (upright !== page) upright.recycle()
                }
            } finally {
                page.recycle()
            }
        }

    /** Кадр, поставленный по метке камеры; без метки — он же. */
    private fun upright(page: Bitmap, imagePath: String): Bitmap {
        val degrees = runCatching {
            when (ExifInterface(imagePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return page
        return Bitmap.createBitmap(page, 0, 0, page.width, page.height, Matrix().apply { postRotate(degrees) }, true)
    }

    /** `false` — место оказалось за краем снимка: замазывать там нечего. */
    private fun hidePlace(page: Bitmap, place: Box): Boolean {
        val left = place.left.toInt().coerceIn(0, page.width)
        val top = place.top.toInt().coerceIn(0, page.height)
        val right = kotlin.math.ceil(place.right).toInt().coerceIn(0, page.width)
        val bottom = kotlin.math.ceil(place.bottom).toInt().coerceIn(0, page.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return false

        val pixels = IntArray(width * height)
        page.getPixels(pixels, 0, width, left, top, width, height)
        Redaction.hide(pixels, width, height, listOf(Box(0f, 0f, width.toFloat(), height.toFloat())))
        page.setPixels(pixels, 0, width, left, top, width, height)
        return true
    }
}
