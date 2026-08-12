package com.point.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.point.core.flow.readingUpscale
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

internal class InlineAttachment(val base64: String, val mime: String)

/** Готовилка кадра для моделей на телефоне: ужать через Bitmap и закодировать (#828). */
fun inlineFrame(path: String, mime: String): com.point.core.flow.InlineFrame? =
    inlineAttachment(path, mime)?.let { com.point.core.flow.InlineFrame(it.base64, it.mime) }

internal fun inlineAttachment(path: String, mime: String): InlineAttachment? {
    val file = File(path)
    val size = if (file.exists()) file.length() else 0L
    if (size < 1L) return null
    shrunkFrame(file, mime)?.let { return InlineAttachment(base64(it.bytes), it.mime) }
    enlargedFrame(file, mime)?.let { return InlineAttachment(base64(it.bytes), it.mime) }

    if (size > MAX_INLINE_BYTES) return null
    return InlineAttachment(base64(file.readBytes()), mime)
}

private fun enlargedFrame(file: File, mime: String): Frame? {
    if (!mime.startsWith("image/")) return null
    return runCatching {

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val scale = readingUpscale(bounds.outWidth, bounds.outHeight)
        if (scale <= 1) return null
        val upright = decodeBoundedUpright(file.path, MODEL_MAX_EDGE_PX) ?: return null
        val scaled = Bitmap.createScaledBitmap(upright, upright.width * scale, upright.height * scale, true)
        if (scaled !== upright) upright.recycle()
        val png = scaled.hasAlpha()
        val out = ByteArrayOutputStream()
        scaled.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()
        val bytes = out.toByteArray()

        if (bytes.size > MAX_INLINE_BYTES) null else Frame(bytes, if (png) "image/png" else "image/jpeg")
    }.getOrNull()
}

private fun shrunkFrame(file: File, mime: String): Frame? {
    if (!mime.startsWith("image/")) return null
    if (!oversizedForModel(file.length())) return null
    return runCatching {
        val upright = decodeBoundedUpright(file.path, MODEL_MAX_EDGE_PX) ?: return null
        val (w, h) = fittedSize(upright.width, upright.height, MODEL_MAX_EDGE_PX)
        val scaled = if (w == upright.width && h == upright.height) {
            upright
        } else {
            Bitmap.createScaledBitmap(upright, w, h, true).also { if (it !== upright) upright.recycle() }
        }

        val png = scaled.hasAlpha()
        val out = ByteArrayOutputStream()
        scaled.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()
        val bytes = out.toByteArray()

        if (bytes.size >= file.length()) null else Frame(bytes, if (png) "image/png" else "image/jpeg")
    }.getOrNull()
}

private class Frame(val bytes: ByteArray, val mime: String)

internal fun oversizedForModel(
    bytes: Long,
    budget: Long = MODEL_INLINE_BUDGET_BYTES,
): Boolean = bytes > budget

internal fun fittedSize(width: Int, height: Int, maxEdgePx: Int = MODEL_MAX_EDGE_PX): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (longEdge <= 0 || longEdge <= maxEdgePx) return width to height
    val k = maxEdgePx.toDouble() / longEdge
    return maxOf(1, (width * k).roundToInt()) to maxOf(1, (height * k).roundToInt())
}

private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

internal const val MAX_INLINE_BYTES = 15L * 1024 * 1024

internal const val MODEL_MAX_EDGE_PX = 3072

internal const val MODEL_INLINE_BUDGET_BYTES = 4L * 1024 * 1024

private const val JPEG_QUALITY = 90
