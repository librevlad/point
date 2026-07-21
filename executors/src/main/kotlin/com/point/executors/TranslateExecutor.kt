package com.point.executors

import com.point.core.flow.Executor
import com.point.core.flow.LlmClient
import com.point.core.flow.PdfTextExtractor
import com.point.core.model.ExecutorId
import com.point.core.model.ExecutorResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** text / pdf -> translated text via the LLM (PDF text is extracted first). */
class TranslateExecutor @Inject constructor(
    private val llm: LlmClient,
    private val pdfText: PdfTextExtractor,
) : Executor {
    override val id = ExecutorId("translate")
    override val icon = "translate"
    override fun title(state: ObjectState) = "Перевести"
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.TEXT, ObjectKind.PDF)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    override suspend fun execute(input: PointObject, amendment: String?): ExecutorResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = when (input.state.kind) {
                    ObjectKind.TEXT -> File(input.uri.value).readText()
                    ObjectKind.PDF -> pdfText.extractText(input)
                    else -> ""
                }
                if (text.isBlank()) {
                    ExecutorResult.Failure("Нет текста для перевода", recoverable = true)
                } else {
                    val target = amendment?.takeIf { it.isNotBlank() } ?: "русский"
                    val prompt = "Переведи текст на $target. Верни только перевод, без пояснений.\n\n$text"
                    ExecutorResult.Success(llm.run(input, prompt))
                }
            }.getOrElse { ExecutorResult.Failure(it.message ?: "Ошибка перевода", recoverable = true) }
        }
}
