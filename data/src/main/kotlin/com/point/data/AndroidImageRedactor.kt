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
 * PNG, а не JPEG: результат отдают дальше, и второе сжатие поверх исходного добавляло бы
 * артефакты к тому, что человек и так уже показал.
 */
class AndroidImageRedactor @Inject constructor() : ImageRedactor {

    override suspend fun hide(imagePath: String, places: List<Box>): EvidenceImage? =
        withContext(Dispatchers.IO) {
            if (places.isEmpty()) return@withContext null
            val source = BitmapFactory.decodeFile(imagePath) ?: return@withContext null
            try {
                val width = source.width
                val height = source.height
                val pixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, 0, 0, width, height)

                Redaction.hide(pixels, width, height, places)

                val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    out.setPixels(pixels, 0, width, 0, 0, width, height)
                    val bytes = ByteArrayOutputStream().also {
                        out.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }.toByteArray()
                    EvidenceImage(bytes, width, height, extension = "png")
                } finally {
                    out.recycle()
                }
            } finally {
                source.recycle()
            }
        }
}
