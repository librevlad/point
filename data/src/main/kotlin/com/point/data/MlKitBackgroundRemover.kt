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

/**
 * On-device subject cutout via ML Kit Subject Segmentation → a transparent-background PNG. The model
 * downloads on first use (any failure — no model, no Play Services — throws, surfaced as a
 * recoverable failure upstream). Constructed via @Provides in DataModule so Dagger's KSP aggregation
 * never resolves the ML Kit AAR types (same fix as the entity extractor).
 *
 * Edge cases handled: big photo (bounded decode), EXIF rotation ([decodeBoundedUpright]), no subject
 * (a near-empty foreground → clear error), and alpha preserved by writing PNG (never JPEG).
 */
class MlKitBackgroundRemover(
    private val store: ObjectStore,
) : BackgroundRemover {

    /** Как и у чтения QR (#114): клиент заводится при первом вырезании, а не при старте приложения. */
    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build(),
        )
    }

    override suspend fun cutout(imagePath: String): ScratchRef = withContext(Dispatchers.IO) {
        val bitmap = decodeBoundedUpright(imagePath, MAX_PX) ?: error("Не удалось прочитать изображение")
        try {
            val result = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
            val foreground = result.foregroundBitmap ?: error("Объект на фото не найден")
            val opaque = opaqueRatio(foreground)
            // No subject → nothing to keep. Subject fills the frame → no background to blur/remove:
            // without this guard the opaque cutout repaints the whole photo and the effect is a
            // silent no-op ("the same picture") — say so instead.
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

    /** Fraction of a coarse grid whose pixels are opaque: ~0 → nothing segmented; ~1 → subject fills the frame. */
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
        const val MAX_PX = 2048
        const val GRID = 40
        const val MIN_OPAQUE_RATIO = 0.01
        const val MAX_OPAQUE_RATIO = 0.96
    }
}
