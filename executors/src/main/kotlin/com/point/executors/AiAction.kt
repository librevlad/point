package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.capabilities.PdfCapability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.flow.reportStage
import com.point.core.flow.labelNeedingKey
import com.point.core.model.ActionResult
import dagger.Lazy
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

fun aiSuggestions(kind: ObjectKind): List<String> = when (kind) {
    ObjectKind.IMAGE -> listOf("Что на изображении?", "Извлеки весь текст", "Переведи текст с картинки")
    ObjectKind.TEXT -> listOf("Кратко перескажи", "Исправь ошибки и стиль", "Ответь на это")
    ObjectKind.PDF -> listOf("Краткое содержание", "Главные тезисы", "О чём документ?")
    ObjectKind.OFFICE -> listOf("Краткое содержание", "Извлеки ключевые данные", "Главные тезисы")
    ObjectKind.URL -> listOf("О чём эта ссылка?", "Краткое содержание страницы", "Главные тезисы")
    ObjectKind.AUDIO -> listOf("О чём эта запись?", "Кто что сказал?", "Что от меня хотят?")
    ObjectKind.ZIP, ObjectKind.COLLECTION -> listOf("Что внутри?", "Что можно сделать?")
    else -> listOf("Что это?", "Что можно сделать?")
}

private val WORD_HINTS = listOf("word", "ворд", "docx")
private val EXCEL_HINTS = listOf("excel", "эксель", "xlsx", "таблиц")
private val PDF_HINTS = listOf("pdf", "пдф")
private val QUESTION_STARTERS =
    listOf("что ", "что-", "как ", "какой", "кака", "каки", "почему", "зачем", "кто ", "где ",
        "когда", "сколько", "объясни", "расскажи", "опиши", "правда ли", "верно ли")

fun aiTransformTarget(prompt: String): CapabilityId? {
    val p = prompt.lowercase().trim()
    if (p.endsWith("?")) return null
    if (QUESTION_STARTERS.any { p.startsWith(it) }) return null
    return when {
        WORD_HINTS.any { it in p } -> WordPlusCapability.ID
        EXCEL_HINTS.any { it in p } -> ExcelCapability.ID
        PDF_HINTS.any { it in p } -> PdfCapability.ID
        else -> null
    }
}

class AiCapability @Inject constructor(
    private val keys: AiReadiness,
) : Capability {
    override val id = ID
    override val icon = "ai"
    override val meta = CapabilityMeta(priority = 100, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)

    override fun label(state: ObjectState) = labelNeedingKey("AI", keys.keySet())
    override fun accepts(state: ObjectState) = state.kind.isFileBacked
    override fun produces(state: ObjectState): ObjectState? = null

    companion object { val ID = CapabilityId("ai") }
}

class AiRealizer @Inject constructor(
    private val llm: LlmClient,

    private val resolver: Lazy<Resolver>,
) : Realizer {
    override val capabilityId = AiCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        if (amendment == null) {
            return ActionResult.NeedsInput(
                "Что сделать с объектом? (пусто = авто-анализ)",
                suggestions = aiSuggestions(input.state.kind),
            )
        }

        aiTransformTarget(amendment)?.let { target ->
            return resolver.get().realizerFor(target).perform(input, null)
        }

        return withContext(Dispatchers.IO) {
            runCatching {

                reportStage("Читаю объект")
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

        ObjectKind.AUDIO -> "Это аудиозапись. Послушай её и ответь по существу."

        ObjectKind.COLLECTION -> "Это набор файлов. Подскажи, что с ними можно сделать."

        else -> "Помоги разобраться с этим объектом."
    }
}
