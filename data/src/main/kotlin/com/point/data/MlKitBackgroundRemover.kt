package com.point.data

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.point.core.flow.BackgroundRemover
import com.point.core.flow.ENGINE_PREPARING
import com.point.core.flow.ObjectStore
import com.point.core.flow.OwnWords
import com.point.core.flow.ownWords
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

    /**
     * Печать слоя (#992): наружу выходят либо слова Point — [OwnWords], — либо ничего.
     *
     * Раньше перехватывался один вызов — сегментация, — и текст любого другого сбоя внутри
     * (нет места на диске, не хватило памяти) уходил человеку в лицо через три действия
     * сразу. Здесь заперт весь слой: чужой текст остаётся в журнале, а безымянный отказ
     * называет само действие, которое нажал человек.
     *
     * Граница «своё / чужое» проходит по объявленному примитиву [OwnWords] (#1225), а не по
     * тексту сообщения: по `message` её не видно. Второго такого механизма в `:data` нет —
     * соседи по модулю говорят человеку тем же способом.
     */
    override suspend fun cutout(imagePath: String): ScratchRef = withContext(Dispatchers.IO) {
        try {
            segment(imagePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OwnWords) {
            throw e
        } catch (e: Throwable) {
            val code = (e as? MlKitException)?.errorCode

            // Журнал не имеет права стать причиной, по которой человек остался без слов.
            runCatching { Log.w(TAG, "cutout failed" + (code?.let { " (code=$it)" } ?: ""), e) }

            // `UNAVAILABLE` — модуль сегментации ещё качается из Play Services: работа не
            // начиналась, и лечится это ожиданием. Случай различается по коду исключения,
            // не по тексту.
            if (code == MlKitException.UNAVAILABLE) ownWords(ENGINE_PREPARING)
            throw IllegalStateException(null as String?, e)
        }
    }

    private suspend fun segment(imagePath: String): ScratchRef {

        // Байты не разобрались в снимок — тот же признак, что видят оба читателя кодов, и
        // слово о нём одно на всех (#992, #1236/#1237). Свой литерал здесь заводил второй
        // словарь на один и тот же случай.
        val bitmap = decodeBoundedUpright(imagePath, MAX_PX) ?: ownWords(UNREADABLE_IMAGE)
        try {
            val result = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
            val foreground = result.foregroundBitmap ?: ownWords("Объект на фото не найден")
            val opaque = opaqueRatio(foreground)

            if (opaque < MIN_OPAQUE_RATIO) ownWords("Объект на фото не найден")
            if (opaque > MAX_OPAQUE_RATIO) {
                ownWords("На фото почти нет фона — объект занимает весь кадр")
            }
            val ref = store.newScratchFile("png")
            File(ref.value).outputStream().use { foreground.compress(Bitmap.CompressFormat.PNG, 100, it) }
            foreground.recycle()
            return ref
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
