package com.point.data

import android.graphics.Bitmap
import android.graphics.Matrix
import com.point.core.flow.CropEvidence
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.EvidenceImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Резак улик (#267): кусок исходного кадра по адресу атомов — картинкой, которую можно положить
 * в .docx рядом со спорным фрагментом.
 *
 * Живёт в `:data`, потому что режет Android-декодер: [cropRegion] читает нужный прямоугольник
 * через `BitmapRegionDecoder`, не поднимая в память всю ведомость 4000×3000.
 *
 * Три вещи, без которых улика бесполезна:
 * - **доворот**: координаты атомов приведены к сырому кадру, а телефон хранит поворот в EXIF и
 *   держит пиксели боком — не довернув, мы положим человеку в документ строку, лежащую на боку;
 * - **ужатие**: полоса ведомости шириной 4000 px весит сотни килобайт, а в документе она всё
 *   равно шире колонки текста не станет; длинная сторона режется до [MAX_EDGE_PX];
 * - **тишина при неудаче**: файла нет, рамка выродилась, декодер не справился — `null`, и
 *   документ соберётся без картинки. Улика — довесок к подсветке, а не условие экспорта.
 */
class BitmapEvidenceCropper @Inject constructor() : EvidenceCropper {

    override suspend fun crop(evidence: CropEvidence): EvidenceImage? = withContext(Dispatchers.IO) {
        runCatching {
            val region = evidence.region
            // Наружу — а не внутрь: срезанный по половине пикселя край буквы и есть та деталь,
            // ради которой улику смотрят.
            val cut = cropRegion(
                evidence.imagePath,
                floor(region.left).toInt(),
                floor(region.top).toInt(),
                ceil(region.right).toInt(),
                ceil(region.bottom).toInt(),
            ) ?: return@runCatching null
            val upright = cut.turned(evidence.uprightDegrees)
            val bounded = upright.bounded(MAX_EDGE_PX)
            val bytes = ByteArrayOutputStream().use { out ->
                bounded.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            val image = EvidenceImage(bytes, bounded.width, bounded.height, "jpg")
            if (bounded !== upright) bounded.recycle()
            if (upright !== cut) upright.recycle()
            cut.recycle()
            image
        }.getOrNull()
    }

    /** Копия, довёрнутая по часовой стрелке; 0° — тот же битмап, без лишней памяти. */
    private fun Bitmap.turned(degrees: Int): Bitmap {
        val angle = ((degrees % 360) + 360) % 360
        if (angle == 0) return this
        return Bitmap.createBitmap(
            this, 0, 0, width, height, Matrix().apply { postRotate(angle.toFloat()) }, true,
        )
    }

    /** Длинная сторона не длиннее [maxEdge]; меньшее не растягивается — улику не «улучшают». */
    private fun Bitmap.bounded(maxEdge: Int): Bitmap {
        val edge = maxOf(width, height)
        if (edge <= maxEdge) return this
        val scale = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        /** Хватает, чтобы прочитать строку глазами; дальше растёт только вес документа. */
        const val MAX_EDGE_PX = 1400

        /** Улика — фотография бумаги, а не иллюстрация: качество ниже съедает мелкий почерк. */
        const val JPEG_QUALITY = 80
    }
}
