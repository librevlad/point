package com.point.core.flow

import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.flow.capabilities.TEXT_IS_WITH_DOCUMENT
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val OFFICE_READ_STAGE = "Читаю документ"

/** Что слышит человек, когда текст прочитан, а лечь на диск не смог: причина записи (#995). */
const val TEXT_NOT_KEPT =
    "Текст прочитан, но не сохранился рядом с документом — папка может быть только для чтения " +
        "или на диске нет места. Перенесите документ в свою папку и повторите"

/**
 * Куда ложится прочитанный текст (#1379).
 *
 * Единственное, чем телефон и компьютер здесь отличались: телефон кладёт текст в свою рабочую
 * копию, компьютер — рядом с документом, потому что его `%TEMP%` подметает операционная система
 * (#995). Всё остальное — чтение, отказ с настоящей причиной, знание у самого документа — одно
 * на обоих. Возвращает путь к файлу с текстом; `null` — записать не вышло.
 */
fun interface TextKeeper {
    suspend fun keep(source: PointObject, text: String): String?
}

/**
 * Текст документа — знание самого документа (#995) и настоящая причина отказа (#997).
 *
 * Один исполнитель на телефон и компьютер (#1379, решение владельца: «пк должен все уметь не
 * хуже телефона»). До этого он был написан дважды вокруг одного и того же
 * [OoxmlOfficeTextExtractor], и лучшее из двух прочтений — что чтение и запись падают
 * по-разному — жило только на компьютере.
 *
 * Прочитать и записать — две работы с разными бедами (#995, #997). Пока они лежали в одном
 * `runCatching`, осечка записи выходила человеку как «документ повреждён»: документ был цел, а
 * виноватым назначали его.
 */
class OfficeRealizer(
    private val officeText: OfficeTextExtractor,
    private val keeper: TextKeeper,
) : Realizer {
    override val capabilityId = OfficeCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            reportStage(OFFICE_READ_STAGE)
            val text = runCatching { officeText.extractText(input) }.getOrElse {
                return@withContext ActionResult.Failure(
                    "Текст не достался — документ повреждён или это не офисный файл",
                    recoverable = true,
                )
            }
            if (text.isBlank()) {
                return@withContext ActionResult.Failure(
                    officeTextMissingReason(input.metadata["name"], input.mime),
                    recoverable = false,
                )
            }
            val ref = runCatching { keeper.keep(input, text) }.getOrNull()
                ?: return@withContext ActionResult.Failure(TEXT_NOT_KEPT, recoverable = true)
            ActionResult.Done(
                TEXT_IS_WITH_DOCUMENT,
                Findings(
                    features = setOf(Feature.HAS_TEXT),
                    metadata = mapOf(META_OCR_TEXT_REF to ref),
                ),
            )
        }
}
