package com.point.executors

import android.graphics.Bitmap
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerMeta
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * «Скан+» (#200) — the detail-preserving premium scan: find + straighten the page, then lift faint ink
 * with CLAHE **keeping colour** and upscale, instead of «Скан»'s harsh black-and-white. For handwriting,
 * pencil and coloured forms where binarisation throws away detail. Output stays an image so it chains
 * into «В Excel» / to-PDF / save / share. OpenCV-only — the colour enhance is the whole point.
 */
class ScanPlusCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "scan"
    override val meta = CapabilityMeta(latency = Latency.SLOW) // detect + warp + CLAHE + upscale
    override fun label(state: ObjectState) = "Скан+"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    companion object { val ID = CapabilityId("scan-plus") }
}

class ScanPlusRealizer(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = ScanPlusCapability.ID
    override val meta = RealizerMeta(priority = 20)

    // OpenCV-native (CLAHE/perspective): offered only when the pack loaded — no pure fallback, because a
    // colour-preserving enhance is exactly what a grayscale-Otsu filter cannot do.
    override fun isAvailable(): Boolean = OpenCvScan.available

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                // Стадии (#288): «Скан+» на телефоне идёт десятки секунд — декод многомегапиксельного
                // кадра, конвейер OpenCV ([OpenCvScan.enhance] рассказывает о себе сам), сжатие в JPEG.
                // Каждая строка называет то, что происходит именно сейчас.
                reportStage("Читаю снимок")
                val src = Bitmaps.decodeUpright(input.uri.value, Bitmaps.SCAN_PLUS_MAX_PX)
                    ?: error("Не удалось прочитать изображение")
                val enhanced = OpenCvScan.enhance(src)
                reportStage("Сохраняю")
                val ref = store.newScratchFile("jpg")
                File(ref.value).outputStream().use { enhanced.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                src.recycle()
                enhanced.recycle()
                ActionResult.Success(
                    ResultObject(ObjectKind.IMAGE, "image/jpeg", ref, mapOf("op" to "scan-plus")),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка сканирования", recoverable = true) }
        }
}
