package com.point.data

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.point.core.flow.BackgroundRemover
import com.point.core.flow.ObjectStore
import com.point.core.model.ScratchRef
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
            // Чужое сообщение остаётся в журнале, человеку — свои слова (#686, #992). На свежем
            // устройстве сюда прилетает английское «Waiting for the subject segmentation optional
            // module to be downloaded» — и уходило прямо на экран, со значком отказа.
            val result = runCatching { segmenter.process(InputImage.fromBitmap(bitmap, 0)).await() }
                .getOrElse { error(com.point.core.flow.ourWordsFor(it.message, CUTOUT_FAILED)) }
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

        const val CUTOUT_FAILED = "Не удалось отделить объект от фона — попробуйте ещё раз"

        const val MAX_PX = 2048
        const val GRID = 40
        const val MIN_OPAQUE_RATIO = 0.01
        const val MAX_OPAQUE_RATIO = 0.96
    }
}
