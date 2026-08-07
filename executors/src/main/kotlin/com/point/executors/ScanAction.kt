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
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class ScanCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "scan"
    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "Скан"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    override fun yields(state: ObjectState) =
        ActionYield.New(ObjectKind.IMAGE, "чёрно-белую страницу")

    companion object { val ID = CapabilityId("scan") }
}

class ScanRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = ScanCapability.ID

    override val meta = RealizerMeta(priority = 90)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage("Читаю снимок")
                val src = Bitmaps.decodeUpright(input.uri.value)
                    ?: error("Не удалось прочитать изображение")
                val width = src.width
                val height = src.height
                val pixels = IntArray(width * height)
                src.getPixels(pixels, 0, width, 0, 0, width, height)

                reportStage("Свожу к чёрно-белому")
                val scanned = ScanFilter.apply(pixels)
                val output = Bitmap.createBitmap(scanned, width, height, Bitmap.Config.ARGB_8888)

                reportStage("Сохраняю")
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
