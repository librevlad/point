package com.point.desktop

import com.point.core.flow.ObjectStore
import com.point.core.flow.PhotoEnhancer
import com.point.core.flow.reportStage
import com.point.core.model.ScratchRef
import com.point.core.model.tr
import com.point.vision.PhotoVision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Улучшить фото» на компьютере (#433): тот же конвейер [PhotoVision], что у телефона, своей
 * OpenCV. Памяти у компьютера больше, поэтому и снимок читается крупнее.
 */
class PcPhotoEnhancer(private val store: ObjectStore) : PhotoEnhancer {

    override suspend fun enhance(imagePath: String): ScratchRef = withContext(Dispatchers.IO) {
        reportStage(tr("Читаю снимок"))
        val picture = uprightPicture(File(imagePath), MAX_PX) ?: error(tr("Не удалось прочитать изображение"))
        val enhanced = onFrame(picture) { rgba, scratch -> imageOf(PhotoVision.enhance(rgba, scratch)) }

        reportStage(tr("Сохраняю"))
        val ref = store.newScratchFile("jpg")
        writeJpeg(enhanced, File(ref.value))
        ref
    }

    private companion object {
        const val MAX_PX = 4096
    }
}
