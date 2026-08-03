package com.point.executors

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

/** Office document (docx/xlsx/pptx) -> extracted plain text. */
class OfficeCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "office"

    /** Не [Latency.INSTANT] (#288): разбор docx/xlsx/pptx идёт секунды на большом файле — ровно
     *  та же работа и те же слова, что у «В PDF» над документом, а тот объявлен [Latency.FAST]. */
    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "Извлечь текст"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.OFFICE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("office") }
}

/**
 * Одна работа — одни слова (#288). Разбор docx/xlsx/pptx идёт секунды на большом файле, и делают
 * его два соседних пузырька над одним и тем же документом: «Извлечь текст» — здесь, «В PDF» — в
 * [PdfRealizer]. Пока говорил только второй, первый читался не как «этот проще», а как «этот
 * завис»; общая константа держит их от расхождения впредь.
 */
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
