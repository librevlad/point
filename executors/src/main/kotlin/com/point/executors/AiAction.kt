package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** The 3 most-likely AI prompts for an object of [kind] (#86) — tappable so the user rarely has to
 *  type. Kept short and imperative; the LLM gets the object's content alongside. */
internal fun aiSuggestions(kind: ObjectKind): List<String> = when (kind) {
    ObjectKind.IMAGE -> listOf("Что на изображении?", "Извлеки весь текст", "Переведи текст с картинки")
    ObjectKind.TEXT -> listOf("Кратко перескажи", "Исправь ошибки и стиль", "Ответь на это")
    ObjectKind.PDF -> listOf("Краткое содержание", "Главные тезисы", "О чём документ?")
    ObjectKind.OFFICE -> listOf("Краткое содержание", "Извлеки ключевые данные", "Главные тезисы")
    ObjectKind.URL -> listOf("О чём эта ссылка?", "Краткое содержание страницы", "Главные тезисы")
    ObjectKind.ZIP, ObjectKind.COLLECTION -> listOf("Что внутри?", "Что можно сделать?")
    ObjectKind.UNKNOWN -> listOf("Что это?", "Что можно сделать?")
}

/**
 * Emergency universal capability. Accepts any object; asks the user what to do
 * (NeedsInput), routes object + prompt to the LLM, materialises the answer
 * (markdown -> `.md`). `produces` is null — the AI output type is unknown until
 * the result is classified.
 */
class AiCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "ai"
    override val meta = CapabilityMeta(priority = 100, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "AI"
    override fun accepts(state: ObjectState) = state.kind != ObjectKind.COLLECTION
    override fun produces(state: ObjectState): ObjectState? = null // unknown until classified

    companion object { val ID = CapabilityId("ai") }
}

class AiRealizer @Inject constructor(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = AiCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        if (amendment == null) {
            return ActionResult.NeedsInput(
                "Что сделать с объектом? (пусто = авто-анализ)",
                suggestions = aiSuggestions(input.state.kind),
            )
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                ActionResult.Success(llm.run(input, buildPrompt(input, amendment)))
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка AI", recoverable = true) }
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
        // AI is not offered for collections (accepts excludes them), but the
        // when stays exhaustive so a new kind forces a review here too.
        ObjectKind.COLLECTION -> "Это набор файлов. Подскажи, что с ними можно сделать."
        ObjectKind.UNKNOWN -> "Помоги разобраться с этим объектом."
    }
}
