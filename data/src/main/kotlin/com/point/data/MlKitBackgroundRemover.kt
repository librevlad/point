package com.point.data

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.point.core.flow.BackgroundRemover
import com.point.core.flow.ObjectStore
import com.point.core.model.ScratchRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

class MlKitBackgroundRemover(
    private val store: ObjectStore,
) : BackgroundRemover {

    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build(),
        )
    }

    override suspend fun cutout(imagePath: String): ScratchRef = withContext(Dispatchers.IO) {
        val bitmap = decodeBoundedUpright(imagePath, MAX_PX) ?: error("Не удалось прочитать изображение")
        try {
            val result = try {
                segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Человеку — свои слова, вендорский текст остаётся в журнале (#686, #992).
                val code = (e as? MlKitException)?.errorCode
                Log.w(TAG, "subject segmentation failed" + (code?.let { " (code=$it)" } ?: ""), e)
                throw IllegalStateException(segmentationFailureWords(code), e)
            }
            val foreground = result.foregroundBitmap ?: error("Объект на фото не найден")
            val opaque = opaqueRatio(foreground)

            if (opaque < MIN_OPAQUE_RATIO) error("Объект на фото не найден")
            if (opaque > MAX_OPAQUE_RATIO) {
                error("На фото почти нет фона — объект занимает весь кадр")
            }
            val ref = store.newScratchFile("png")
            File(ref.value).outputStream().use { foreground.compress(Bitmap.CompressFormat.PNG, 100, it) }
            foreground.recycle()
            ref
        } finally {
            bitmap.recycle()
        }
    }

    private fun opaqueRatio(bmp: Bitmap): Double {
        val step = maxOf(1, minOf(bmp.width, bmp.height) / GRID)
        var opaque = 0
        var total = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                total++
                if ((bmp.getPixel(x, y) ushr 24) != 0) opaque++
                x += step
            }
            y += step
        }
        return if (total == 0) 0.0 else opaque.toDouble() / total
    }

    private companion object {
        const val TAG = "PointCutout"
        const val MAX_PX = 2048
        const val GRID = 40
        const val MIN_OPAQUE_RATIO = 0.01
        const val MAX_OPAQUE_RATIO = 0.96
    }
}

/**
 * Слова Point для отказа движка сегментации: вендорский текст этого слоя на экран
 * не выходит дословно (#992). Различаем по коду [MlKitException], не по тексту.
 *
 * `UNAVAILABLE` — модуль сегментации ещё качается из Play Services: случай ожидаемый
 * и лечится ожиданием, поэтому слово зовёт попробовать снова. Всё остальное — общий отказ.
 */
internal fun segmentationFailureWords(mlKitErrorCode: Int?): String =
    if (mlKitErrorCode == MlKitException.UNAVAILABLE) CUTOUT_ENGINE_PREPARING else CUTOUT_FAILED

/** Слово на время скачивания модуля: случай лечится ожиданием, поэтому зовёт попробовать снова. */
internal const val CUTOUT_ENGINE_PREPARING = "Готовлю движок выреза — попробуйте через минуту"

/** Общий отказ выреза: вендорский текст остаётся в журнале (#686). */
internal const val CUTOUT_FAILED = "Убрать фон не вышло"
