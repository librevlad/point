package com.point.executors

import android.graphics.Bitmap
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerMeta
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class OpenCvScanRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {

    override val capabilityId = ScanCapability.ID

    override val meta = RealizerMeta(priority = 20)

    override fun isAvailable(): Boolean = OpenCvScan.available

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {

                reportStage("Читаю снимок")
                val src = Bitmaps.decodeUpright(input.uri.value, Bitmaps.SCAN_PLUS_MAX_PX)
                    ?: error("Не удалось прочитать изображение")
                val scanned = OpenCvScan.enhance(src)
                src.recycle()

                reportStage("Сохраняю")
                val ref = store.newScratchFile("jpg")
                File(ref.value).outputStream().use { scanned.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                scanned.recycle()

                ActionResult.Success(
                    ResultObject(ObjectKind.IMAGE, "image/jpeg", ref, mapOf("op" to "scan")),
                )
            }.getOrElse {

                ActionResult.Failure(it.message ?: SCAN_FAILED, recoverable = true)
            }
        }

    internal companion object {

        const val SCAN_FAILED = "Страницу на снимке не удалось выпрямить — снимите её целиком и при ровном свете"
    }
}
