package com.point.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.point.core.flow.ImageCompositor
import com.point.core.flow.ObjectStore
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class AndroidImageCompositor @Inject constructor(
    private val store: ObjectStore,
) : ImageCompositor {

    override suspend fun composite(subjectPath: String, backgroundPath: String): ScratchRef =
        withContext(Dispatchers.IO) {
            val subject = BitmapFactory.decodeFile(subjectPath) ?: error("Не удалось прочитать объект")
            val background = decodeBoundedUpright(backgroundPath, MAX_PX) ?: error("Не удалось прочитать фон")
            try {
                val out = Bitmap.createBitmap(subject.width, subject.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                drawCenterCrop(canvas, background, subject.width, subject.height)
                canvas.drawBitmap(subject, 0f, 0f, null)
                val ref = store.newScratchFile("png")
                File(ref.value).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out.recycle()
                ref
            } finally {
                subject.recycle()
                background.recycle()
            }
        }

    override suspend fun blur(imagePath: String): ScratchRef = withContext(Dispatchers.IO) {
        val src = decodeBoundedUpright(imagePath, MAX_PX) ?: error("Не удалось прочитать изображение")
        val blurred = blurByScale(src)
        try {
            val ref = store.newScratchFile("png")
            File(ref.value).outputStream().use { blurred.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ref
        } finally {
            src.recycle()
            blurred.recycle()
        }
    }

    private fun drawCenterCrop(canvas: Canvas, bg: Bitmap, w: Int, h: Int) {
        val scale = maxOf(w.toFloat() / bg.width, h.toFloat() / bg.height)
        val sw = bg.width * scale
        val sh = bg.height * scale
        val left = (w - sw) / 2f
        val top = (h - sh) / 2f
        canvas.drawBitmap(bg, null, RectF(left, top, left + sw, top + sh), FILTER)
    }

    private fun blurByScale(src: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(
            src, maxOf(1, src.width / DOWNSCALE), maxOf(1, src.height / DOWNSCALE), true,
        )
        val up = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        small.recycle()
        return up
    }

    private companion object {
        const val MAX_PX = 2048
        const val DOWNSCALE = 16
        val FILTER = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}
