package com.point.executors

import com.point.core.flow.Executor
import com.point.core.flow.ObjectStore
import com.point.core.flow.OfficeTextExtractor
import com.point.core.model.ExecutorId
import com.point.core.model.ExecutorResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Office document (docx/xlsx/pptx) -> extracted plain text. The resulting TEXT
 * object then unlocks the text actions (translate, -> PDF, AI). Legacy binary
 * .doc/.xls/.ppt yield no text and produce a recoverable failure.
 */
class OfficeExecutor @Inject constructor(
    private val store: ObjectStore,
    private val officeText: OfficeTextExtractor,
) : Executor {
    override val id = ExecutorId("office")
    override val icon = "office"
    override fun title(state: ObjectState) = "Извлечь текст"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.OFFICE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    override suspend fun execute(input: PointObject, amendment: String?): ExecutorResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = officeText.extractText(input)
                if (text.isBlank()) {
                    ExecutorResult.Failure(
                        "Не удалось извлечь текст (возможно, старый формат .doc/.xls/.ppt)",
                        recoverable = true,
                    )
                } else {
                    val ref = store.newScratchFile("txt")
                    File(ref.value).writeText(text)
                    ExecutorResult.Success(
                        ResultObject(ObjectKind.TEXT, "text/plain", ref, mapOf("op" to "office-extract")),
                    )
                }
            }.getOrElse { ExecutorResult.Failure(it.message ?: "Ошибка чтения документа", recoverable = true) }
        }
}
