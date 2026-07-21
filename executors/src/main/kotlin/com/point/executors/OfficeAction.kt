package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.ObjectStore
import com.point.core.flow.OfficeTextExtractor
import com.point.core.flow.Realizer
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

/** Office document (docx/xlsx/pptx) -> extracted plain text. */
class OfficeCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "office"
    override fun label(state: ObjectState) = "Извлечь текст"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.OFFICE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("office") }
}

class OfficeRealizer @Inject constructor(
    private val store: ObjectStore,
    private val officeText: OfficeTextExtractor,
) : Realizer {
    override val capabilityId = OfficeCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
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
