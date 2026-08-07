package com.point.data

import android.graphics.Bitmap
import android.graphics.Matrix
import com.point.core.flow.CropEvidence
import com.point.core.flow.CropPurpose
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.EvidenceImage
import com.point.core.flow.readingCropUpscale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor

class BitmapEvidenceCropper @Inject constructor() : EvidenceCropper {

    override suspend fun crop(evidence: CropEvidence): EvidenceImage? = withContext(Dispatchers.IO) {
        runCatching {
            val region = evidence.region

            val cut = cropRegion(
                evidence.imagePath,
                floor(region.left).toInt(),
                floor(region.top).toInt(),
                ceil(region.right).toInt(),
                ceil(region.bottom).toInt(),
            ) ?: return@runCatching null
            val upright = cut.turned(evidence.uprightDegrees)
            val reading = evidence.purpose == CropPurpose.READING
            val sized = if (reading) upright.enlargedForReading() else upright.bounded(MAX_EDGE_PX)
            val quality = if (reading) READING_QUALITY else JPEG_QUALITY
            val bytes = ByteArrayOutputStream().use { out ->
                sized.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
            val image = EvidenceImage(bytes, sized.width, sized.height, "jpg")
            if (sized !== upright) sized.recycle()
            if (upright !== cut) upright.recycle()
            cut.recycle()
            image
        }.getOrNull()
    }

    private fun Bitmap.turned(degrees: Int): Bitmap {
        val angle = ((degrees % 360) + 360) % 360
        if (angle == 0) return this
        return Bitmap.createBitmap(
            this, 0, 0, width, height, Matrix().apply { postRotate(angle.toFloat()) }, true,
        )
    }

    private fun Bitmap.bounded(maxEdge: Int): Bitmap {
        val edge = maxOf(width, height)
        if (edge <= maxEdge) return this
        val scale = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun Bitmap.enlargedForReading(): Bitmap {
        val scale = readingCropUpscale(width, height)
        if (scale <= 1) return this
        return Bitmap.createScaledBitmap(this, width * scale, height * scale, true)
    }

    private companion object {

        const val MAX_EDGE_PX = 1400

        const val JPEG_QUALITY = 80

        const val READING_QUALITY = 90
    }
}
