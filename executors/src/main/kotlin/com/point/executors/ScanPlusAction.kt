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

class ScanPlusCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "scan"
    override val meta = CapabilityMeta(latency = Latency.SLOW)

    override fun label(state: ObjectState) = "Скан с цветом"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    override fun yields(state: ObjectState) =
        ActionYield.New(ObjectKind.IMAGE, "картинку · дольше, зато на устройстве")

    companion object { val ID = CapabilityId("scan-plus") }
}

class ScanPlusRealizer(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = ScanPlusCapability.ID
    override val meta = RealizerMeta(priority = 20)

    override fun isAvailable(): Boolean = OpenCvScan.available

    override fun unavailableReason(): String? = "нужен пакет обработки снимков"

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {

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
