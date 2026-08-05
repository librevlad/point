package com.point.data

import com.point.core.flow.AI_KEY_HINT
import com.point.core.flow.LlmClient
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import javax.inject.Inject

/**
 * Runs an ordered list of providers, returning the first success. If a provider is
 * unconfigured or fails (e.g. HTTP 429), the next is tried. When all fail the errors
 * are **summarised** (#48): a shared cause — no network, or no key at all — becomes
 * one plain line, and otherwise duplicates collapse, so the user never sees a wall
 * of repeated "Unable to resolve host …" text from every provider × model.
 */
class FallbackLlmClient @Inject constructor(
    private val providers: List<@JvmSuppressWildcards LlmClient>,
) : LlmClient {

    /**
     * Цепочка настроена, пока настроен хоть один в ней. В раздаваемой сборке встроенных ключей нет,
     * и список сводится к ключу человека — то есть ответ здесь и есть ответ на вопрос «есть ли у
     * Point вообще AI прямо сейчас» (#467).
     */
    override val configured: Boolean get() = providers.any { it.configured }

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {
        if (providers.isEmpty()) error("AI не настроен — $AI_KEY_HINT")
        // For an image, lead with strong vision models — the free ones garble dense/handwritten
        // /rotated tables (#22). Stable sort, so within each group the original order holds.
        val ordered = if (obj.mime.startsWith("image/")) {
            providers.sortedByDescending { it.strongVision }
        } else {
            providers
        }
        val errors = mutableListOf<String>()
        var considered = 0
        for (provider in ordered) {
            if (!provider.canHandle(obj)) continue // e.g. a photo to a text-only model (#60)
            considered++
            try {
                return provider.run(obj, prompt)
            } catch (e: Exception) {
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        if (considered == 0) {
            // Подсказка называет то, чего не хватило ИМЕННО этому объекту (#223). Прежний текст
            // говорил про изображения всегда — в том числе про голосовое, где картинка ни при чём.
            val needed = if (obj.mime.startsWith("audio/") || obj.mime == "application/ogg") {
                "с поддержкой аудио"
            } else {
                "с поддержкой изображений"
            }
            error("Для этого объекта нет подходящей AI-модели — $AI_KEY_HINT $needed")
        }
        error(summarise(errors))
    }

    /**
     * One human line instead of every provider's raw error concatenated.
     *
     * Кончившаяся бесплатная квота — не поломка, и ветка у неё та же, что у облачного чтения
     * ([summariseCloudErrors]): причина одна, а два набора слов про одно и то же — вторая правда.
     * До неё вся цепочка, упёршаяся в лимит, показывала под объектом «AI недоступен — » и склейку
     * провайдерских строк: человек читал их как поломку и шёл чинить исправное.
     *
     * Признаки — общие ([isNetworkError], [isQuotaError]), а не свой список рядом: пока список
     * «нет сети» лежал здесь копией, он тихо расходился с тем, по которому судит облако.
     */
    private fun summarise(errors: List<String>): String = when {
        errors.isNotEmpty() && errors.all { it.isNetworkError() } ->
            "AI недоступен — нет подключения к интернету"
        errors.isNotEmpty() && errors.all { it.isQuotaError() } ->
            "Бесплатные лимиты AI исчерпаны — вернитесь позже, платить не идём"
        else -> "AI недоступен — " +
            errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; ")
    }
}
