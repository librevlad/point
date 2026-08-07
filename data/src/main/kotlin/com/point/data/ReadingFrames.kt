package com.point.data

import android.graphics.Bitmap
import android.util.Log
import com.point.core.flow.AtomCodec
import com.point.core.flow.FrameUpscaler
import com.point.core.flow.META_CLOUD_ATOMS_REF
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.ReadyFrame
import com.point.core.flow.preparedForReading
import com.point.core.flow.typicalTextHeightPx
import com.point.core.model.PointObject
import java.io.File

internal val bitmapUpscaler = FrameUpscaler<Bitmap> { bitmap, scale ->
    Bitmap.createScaledBitmap(bitmap, bitmap.width * scale, bitmap.height * scale, true)
}

internal fun preparedBitmap(bitmap: Bitmap, textHeightPx: Int?): ReadyFrame<Bitmap> =
    try {
        preparedForReading(bitmap, bitmap.width, bitmap.height, textHeightPx, bitmapUpscaler)
    } catch (e: OutOfMemoryError) {

        Log.w("PointOCR", "frame upscale skipped: out of memory", e)
        ReadyFrame(bitmap, 1)
    }

internal fun knownTextHeightPx(obj: PointObject): Int? =
    (obj.metadata[META_OCR_ATOMS_REF] ?: obj.metadata[META_CLOUD_ATOMS_REF])
        ?.let { path -> runCatching { AtomCodec.decode(File(path).readText()) }.getOrNull() }
        ?.let(::typicalTextHeightPx)
