package com.point.data

import android.graphics.Bitmap
import com.point.core.flow.FrameTransform
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

class OutboundFrame(
    val bytes: ByteArray,
    val mime: String,
    val fileName: String,
    val transform: FrameTransform,
)

interface OutboundFrames {

    suspend fun of(obj: PointObject): OutboundFrame?
}

class BitmapOutboundFrames @Inject constructor() : OutboundFrames {

    override suspend fun of(obj: PointObject): OutboundFrame? = withContext(Dispatchers.IO) {
        if (!obj.mime.startsWith("image/")) return@withContext null
        val frame = decodeSelectionFrame(obj.uri.value, PAGE_MAX_PX) ?: return@withContext null

        val ready = preparedBitmap(frame.bitmap, knownTextHeightPx(obj))

        if (ready.frame !== frame.bitmap) frame.bitmap.recycle()
        try {
            val out = ByteArrayOutputStream()
            ready.frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            OutboundFrame(
                bytes = out.toByteArray(),
                mime = "image/jpeg",
                fileName = "page.jpg",
                transform = frame.transform.copy(
                    upscale = ready.scale,
                    uprightWidth = ready.frame.width,
                    uprightHeight = ready.frame.height,
                ),
            )
        } finally {
            ready.frame.recycle()
        }
    }

    private companion object {

        const val PAGE_MAX_PX = 2048

        const val JPEG_QUALITY = 92
    }
}
