package com.point.executors

import com.point.core.flow.Executor
import com.point.core.flow.LlmClient
import com.point.core.model.ExecutorId
import com.point.core.model.ExecutorResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * text -> translated text via the LLM. (PDF translation needs text extraction,
 * which is not available yet — see PdfExecutor.)
 */
class TranslateExecutor @Inject constructor(
    private val llm: LlmClient,
) : Executor {
    override val id = ExecutorId("translate")
    override val icon = "translate"
    override fun title(state: ObjectState) = "Перевести"
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.TEXT, ObjectKind.PDF)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    override suspend fun execute(input: PointObject, amendment: String?): ExecutorResult {
        if (input.state.kind != ObjectKind.TEXT) {
            return ExecutorResult.Failure(
                "Перевод PDF пока не поддержан (нужно извлечение текста)",
                recoverable = true,
            )
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val text = File(input.uri.value).readText()
                val target = amendment?.takeIf { it.isNotBlank() } ?: "русский"
                val prompt = "Переведи текст на $target. Верни только перевод, без пояснений.\n\n$text"
                ExecutorResult.Success(llm.run(input, prompt))
            }.getOrElse { ExecutorResult.Failure(it.message ?: "Ошибка перевода", recoverable = true) }
        }
    }
}
