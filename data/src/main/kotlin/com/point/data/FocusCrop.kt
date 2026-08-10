package com.point.data

import android.graphics.Bitmap
import com.point.core.flow.Box
import com.point.core.flow.FocusCropPlan
import com.point.core.flow.ObjectStore
import com.point.core.flow.focusCropPlan
import com.point.core.flow.heightOf
import com.point.core.flow.width
import java.io.File

/**
 * Вырезать показанное человеком и увеличить, чтобы читать его, а не страницу целиком (#426).
 *
 * Что вырезать и во сколько раз увеличить, решает чистый `focusCropPlan`; здесь только пиксели.
 * Исходник не трогается: вырезка — временный кадр ради одного чтения, а не новый объект.
 */
class FocusCrop(private val store: ObjectStore) {

    suspend fun of(path: String, region: Box): File? {
        val source = decodeBoundedUpright(path, MAX_SOURCE_PX) ?: return null
        return try {
            val page = Box(0f, 0f, source.width.toFloat(), source.height.toFloat())
            val plan = focusCropPlan(region, page) ?: return null
            write(source, plan)
        } finally {
            source.recycle()
        }
    }

    private suspend fun write(source: Bitmap, plan: FocusCropPlan): File? {
        val left = plan.crop.left.toInt().coerceIn(0, source.width - 1)
        val top = plan.crop.top.toInt().coerceIn(0, source.height - 1)
        val width = plan.crop.width().toInt().coerceIn(1, source.width - left)
        val height = plan.crop.heightOf().toInt().coerceIn(1, source.height - top)

        val cut = Bitmap.createBitmap(source, left, top, width, height)
        val grown = if (plan.scale <= 1f) {
            cut
        } else {
            Bitmap.createScaledBitmap(cut, (width * plan.scale).toInt(), (height * plan.scale).toInt(), true)
        }
        val ref = store.newScratchFile("jpg")
        val file = File(ref.value)
        file.outputStream().use { grown.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        if (grown !== cut) grown.recycle()
        cut.recycle()
        return file
    }

    private companion object {

        const val MAX_SOURCE_PX = 4096

        const val QUALITY = 92
    }
}
