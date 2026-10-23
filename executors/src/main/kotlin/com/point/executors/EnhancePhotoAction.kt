package com.point.executors

import android.graphics.Bitmap
import com.point.core.flow.Capability
import com.point.core.flow.ObjectStore
import com.point.core.flow.PhotoEnhancer
import com.point.core.flow.reportStage
import com.point.core.model.ObjectState
import com.point.core.model.ScratchRef
import com.point.core.model.tr
import com.point.vision.PhotoVision
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File
import javax.inject.Inject

/** Дверь Hilt: «EnhancePhotoCapability» живёт в `:core:flow` (#433). Члены руками, а не `by`: KSP. */
class EnhancePhotoCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.EnhancePhotoCapability()
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun label(graph: com.point.core.flow.GraphState): String = inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: com.point.core.flow.GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): com.point.core.model.ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<com.point.core.model.Intent> = inner.intents(state)
    override fun missing(state: ObjectState): String? = inner.missing(state)
}

/**
 * «Улучшить фото» на телефоне (#433): сам конвейер — общий с компьютером [PhotoVision], здесь
 * только `Bitmap` ↔ матрица и файл.
 *
 * Снимок читается не крупнее [Bitmaps.SCAN_PLUS_MAX_PX] — тот же предел, что у скана: на
 * 50 Мп шумоподавление телефона шло бы минутами и упиралось в память.
 */
internal class OpenCvPhotoEnhancer(private val store: ObjectStore) : PhotoEnhancer {

    override suspend fun enhance(imagePath: String): ScratchRef {
        reportStage(tr("Читаю снимок"))
        val src = Bitmaps.decodeUpright(imagePath, Bitmaps.SCAN_PLUS_MAX_PX)
            ?: error(tr("Не удалось прочитать изображение"))
        val rgba = Mat()
        val scratch = mutableListOf(rgba)
        val done: Bitmap
        try {
            Utils.bitmapToMat(src, rgba)
            val out = PhotoVision.enhance(rgba, scratch)
            done = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, done)
        } finally {
            scratch.forEach { it.release() }
            src.recycle()
        }

        reportStage(tr("Сохраняю"))
        val ref = store.newScratchFile("jpg")
        File(ref.value).outputStream().use { done.compress(Bitmap.CompressFormat.JPEG, Bitmaps.JPEG_QUALITY, it) }
        done.recycle()
        return ref
    }
}
