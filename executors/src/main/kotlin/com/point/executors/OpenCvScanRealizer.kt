package com.point.executors

import android.graphics.Bitmap
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerMeta
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The preferred "Скан" realizer: the CamScanner-grade on-device pipeline (#200) — straighten the
 * page by its table-line intersections (TPS), finish to pure-white paper with live colour, clean
 * the edges, upscale. Shares the `scan` capability with the pure-filter [ScanRealizer]; a lower
 * [RealizerMeta.priority] makes the Resolver prefer it. Gated by [isAvailable] (OpenCV native load);
 * a recoverable failure hands off to the pure grayscale filter through the Resolver's fallback
 * chain — so a missing pack or a hard photo never dead-ends the scan. («Скан+» goes to the cloud.)
 */
class OpenCvScanRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {

    override val capabilityId = ScanCapability.ID

    // Lower than ScanRealizer (90) → preferred whenever OpenCV is available.
    override val meta = RealizerMeta(priority = 20)

    override fun isAvailable(): Boolean = OpenCvScan.available

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = Bitmaps.decodeUpright(input.uri.value, Bitmaps.SCAN_PLUS_MAX_PX)
                    ?: error("Не удалось прочитать изображение")
                val scanned = OpenCvScan.enhance(src)
                src.recycle()

                val ref = store.newScratchFile("jpg")
                File(ref.value).outputStream().use { scanned.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                scanned.recycle()

                ActionResult.Success(
                    ResultObject(ObjectKind.IMAGE, "image/jpeg", ref, mapOf("op" to "scan")),
                )
            }.getOrElse {
                // Recoverable → the Resolver's FallbackRealizer hands off to the pure ScanRealizer.
                ActionResult.Failure(it.message ?: "Ошибка OpenCV-скана", recoverable = true)
            }
        }
}
