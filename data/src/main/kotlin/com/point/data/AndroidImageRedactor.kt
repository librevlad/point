package com.point.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

                val bytes = ByteArrayOutputStream()
                    .also { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    .toByteArray()
                EvidenceImage(bytes, page.width, page.height, extension = "png")
            } finally {
                page.recycle()
            }
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
