package com.point.executors

import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.ObjectStore
import com.point.core.flow.OfficeTextExtractor
import com.point.core.flow.Realizer
import com.point.core.flow.officeTextMissingReason
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

internal const val OFFICE_READ_STAGE = "Читаю документ"

/**
 * Текст документа — знание самого документа (#995) и настоящая причина отказа (#997).
 *
 * Текст уезжал в отдельный объект, а современная .xlsx получала отказ про старые .doc и .xls:
 * причину, которая к ней не относится. Теперь читает свой читатель таблиц, а если текста
 * действительно нет — названо то, что есть на самом деле.
 */
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
                        officeTextMissingReason(input.metadata["name"], input.mime),
                        recoverable = false,
                    )
                } else {
                    val ref = store.newScratchFile("txt")
                    File(ref.value).writeText(text)
                    ActionResult.Done(
                        com.point.core.flow.capabilities.TEXT_IS_WITH_DOCUMENT,
                        Findings(
                            features = setOf(Feature.HAS_TEXT),
                            metadata = mapOf(META_OCR_TEXT_REF to ref.value),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка чтения документа", recoverable = true) }
        }
}
