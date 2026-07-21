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
 * Emergency universal executor. Accepts any object; on the first tap it asks the
 * user what to do (NeedsInput), then routes object + prompt to the LLM and
 * materialises the answer (markdown -> `.md`) as a new TEXT object. The user
 * never sees a chat — only a new object.
 */
class AiExecutor @Inject constructor(
    private val llm: LlmClient,
) : Executor {
    override val id = ExecutorId("ai")
    override val icon = "ai"
    override fun title(state: ObjectState) = "AI"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    override suspend fun execute(input: PointObject, amendment: String?): ExecutorResult {
        if (amendment == null) {
            return ExecutorResult.NeedsInput("Что сделать с объектом? (пусто = авто-анализ)")
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                ExecutorResult.Success(llm.run(input, buildPrompt(input, amendment)))
            }.getOrElse { ExecutorResult.Failure(it.message ?: "Ошибка AI", recoverable = true) }
        }
    }

    private fun buildPrompt(input: PointObject, amendment: String): String = buildString {
        append(systemPrompt(input.state.kind))
        if (amendment.isNotBlank()) {
            append("\n\nЗапрос пользователя: ")
            append(amendment)
        }
        if (input.state.kind == ObjectKind.TEXT) {
            append("\n\nСодержимое:\n")
            append(File(input.uri.value).readText().take(20_000))
        }
    }

    private fun systemPrompt(kind: ObjectKind): String = when (kind) {
        ObjectKind.IMAGE -> "Опиши изображение и извлеки из него текст, если он есть."
        ObjectKind.PDF -> "Кратко изложи содержимое этого PDF."
        ObjectKind.TEXT -> "Проанализируй и кратко изложи текст."
        ObjectKind.ZIP -> "Это архив. Подскажи, что с ним можно сделать."
        ObjectKind.OFFICE -> "Это офисный документ. Кратко изложи его содержимое."
        ObjectKind.URL -> "Это ссылка. Кратко скажи, о чём она."
        ObjectKind.UNKNOWN -> "Помоги разобраться с этим объектом."
    }
}
