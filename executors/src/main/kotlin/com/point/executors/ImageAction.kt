package com.point.executors

import com.point.core.flow.capabilities.ImageCapability
import android.graphics.Bitmap
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
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

class ImageRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = ImageCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage("Читаю изображение")
                val bitmap = Bitmaps.decodeUpright(input.uri.value)
                    ?: error("Не удалось прочитать изображение")
                reportStage("Сжимаю снимок")
                val ref = store.newScratchFile("jpg")
                File(ref.value).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                bitmap.recycle()
                ActionResult.Success(
                    ResultObject(ObjectKind.IMAGE, "image/jpeg", ref, mapOf("op" to "compress")),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка сжатия", recoverable = true) }
        }
}
