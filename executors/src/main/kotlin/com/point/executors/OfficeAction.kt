package com.point.executors

import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.ObjectStore
import com.point.core.flow.OfficeTextExtractor
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

internal const val OFFICE_READ_STAGE = "Читаю документ"

class OfficeRealizer @Inject constructor(
    private val store: ObjectStore,
    private val officeText: OfficeTextExtractor,
) : Realizer {
    override val capabilityId = OfficeCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage(OFFICE_READ_STAGE)
                val text = officeText.extractText(input)
                if (text.isBlank()) {
                    ActionResult.Failure(
                        "Не удалось извлечь текст (возможно, старый формат .doc/.xls/.ppt)",
                        recoverable = true,
                    )
                } else {
                    val ref = store.newScratchFile("txt")
                    File(ref.value).writeText(text)
                    ActionResult.Success(
                        ResultObject(ObjectKind.TEXT, "text/plain", ref, mapOf("op" to "office-extract")),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка чтения документа", recoverable = true) }
        }
}
