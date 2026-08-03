package com.point.data

import android.graphics.Bitmap
import android.graphics.Matrix
import com.point.core.flow.CropEvidence
import com.point.core.flow.CropPurpose
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.EvidenceImage
import com.point.core.flow.readingCropUpscale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Резак кусков кадра (#267, #273): фрагмент исходного снимка по адресу атомов — либо картинкой в
 * .docx рядом со спорным фрагментом, либо входом для перечита спорной ячейки.
 *
 * Живёт в `:data`, потому что режет Android-декодер: [cropRegion] читает нужный прямоугольник
 * через `BitmapRegionDecoder`, не поднимая в память всю ведомость 4000×3000.
 *
 * Три вещи, без которых кусок бесполезен:
 * - **доворот**: координаты атомов приведены к сырому кадру, а телефон хранит поворот в EXIF и
 *   держит пиксели боком — не довернув, мы положим человеку в документ строку, лежащую на боку;
 * - **размер по назначению** ([CropPurpose]): картинку для глаз ужимаем до [MAX_EDGE_PX], потому
 *   что шире колонки документа она всё равно не станет, а вес файла вырастет; кусок **для чтения**
 *   не ужимаем никогда и мелкий увеличиваем ([readingCropUpscale]) — там пиксели и есть весь вход;
 * - **тишина при неудаче**: файла нет, рамка выродилась, декодер не справился — `null`, и
 *   документ соберётся без картинки, а спор ячейки просто останется человеку.
 */
class BitmapEvidenceCropper @Inject constructor() : EvidenceCropper {

    override suspend fun crop(evidence: CropEvidence): EvidenceImage? = withContext(Dispatchers.IO) {
        runCatching {
            val region = evidence.region
            // Наружу — а не внутрь: срезанный по половине пикселя край буквы и есть та деталь,
            // ради которой кусок и режут.
            val cut = cropRegion(
                evidence.imagePath,
                floor(region.left).toInt(),
                floor(region.top).toInt(),
                ceil(region.right).toInt(),
                ceil(region.bottom).toInt(),
            ) ?: return@runCatching null
            val upright = cut.turned(evidence.uprightDegrees)
            val reading = evidence.purpose == CropPurpose.READING
            val sized = if (reading) upright.enlargedForReading() else upright.bounded(MAX_EDGE_PX)
            val quality = if (reading) READING_QUALITY else JPEG_QUALITY
            val bytes = ByteArrayOutputStream().use { out ->
                sized.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
            val image = EvidenceImage(bytes, sized.width, sized.height, "jpg")
            if (sized !== upright) sized.recycle()
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

    /**
     * Кусок для чтения: целое увеличение по [readingCropUpscale] и ни одного ужатия.
     *
     * Потолка по длинной стороне здесь нет сознательно — его отсутствие и есть правка (#273):
     * полоса строки ведомости шириной под 4000 px уезжала модели ужатой до 1400, то есть с
     * высотой знака почти втрое ниже снятой. Что такое ужатие стоит чтения, измерено на этом же
     * кадре (#360), и платить эту цену перечиту, который и заведён ради взгляда на пиксели, было
     * нечем.
     */
    private fun Bitmap.enlargedForReading(): Bitmap {
        val scale = readingCropUpscale(width, height)
        if (scale <= 1) return this
        return Bitmap.createScaledBitmap(this, width * scale, height * scale, true)
    }

    private companion object {
        /** Хватает, чтобы прочитать строку глазами; дальше растёт только вес документа. */
        const val MAX_EDGE_PX = 1400

        /** Улика — фотография бумаги, а не иллюстрация: качество ниже съедает мелкий почерк. */
        const val JPEG_QUALITY = 80

        /**
         * Качество куска, который читает модель, — то же 90, что у целого кадра
         * (`InlineAttachment`), и по той же названной там причине: ниже начинают звенеть тонкие
         * линии таблицы, а модель читает их как границы ячеек.
         */
        const val READING_QUALITY = 90
    }
}
