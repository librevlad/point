package com.point.executors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.point.core.flow.Capability
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerMeta
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

/** photo -> clean black-and-white scan (grayscale + Otsu). Output stays an image
 *  so it chains into "to PDF" / save / share. */
class ScanCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "scan"
    override fun label(state: ObjectState) = "Скан"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    companion object { val ID = CapabilityId("scan") }
}

class ScanRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = ScanCapability.ID

    // Fallback tier: the pure-filter scan (grayscale + Otsu), always available. A
    // preferred OpenCV realizer (auto edge-detection + perspective, isAvailable-gated,
    // lower priority number) drops in as a Capability Pack and the Resolver prefers
    // it — falling back here automatically when the pack is absent or OpenCV fails.
    override val meta = RealizerMeta(priority = 90)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = BitmapFactory.decodeFile(input.uri.value)
                    ?: error("Не удалось прочитать изображение")
                val width = src.width
                val height = src.height
                val pixels = IntArray(width * height)
                src.getPixels(pixels, 0, width, 0, 0, width, height)

                val scanned = ScanFilter.apply(pixels)
                val output = Bitmap.createBitmap(scanned, width, height, Bitmap.Config.ARGB_8888)

                val ref = store.newScratchFile("png")
                File(ref.value).outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                src.recycle()
                output.recycle()

                ActionResult.Success(
                    ResultObject(ObjectKind.IMAGE, "image/png", ref, mapOf("op" to "scan")),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка сканирования", recoverable = true) }
        }
}
