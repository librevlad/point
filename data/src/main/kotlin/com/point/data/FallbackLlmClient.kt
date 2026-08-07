package com.point.data

import com.point.core.flow.AI_KEY_HINT
import com.point.core.flow.LlmClient
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import javax.inject.Inject

class FallbackLlmClient @Inject constructor(
    private val providers: List<@JvmSuppressWildcards LlmClient>,
) : LlmClient {

    override val configured: Boolean get() = providers.any { it.configured }

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {
        if (providers.isEmpty()) error("AI не настроен — $AI_KEY_HINT")

        val ordered = if (obj.mime.startsWith("image/")) {
            providers.sortedByDescending { it.strongVision }
        } else {
            providers
        }
        val errors = mutableListOf<String>()
        var considered = 0
        for (provider in ordered) {
            if (!provider.canHandle(obj)) continue
            considered++
            try {
                return provider.run(obj, prompt)
            } catch (e: Exception) {
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        if (considered == 0) {

            val needed = if (obj.mime.startsWith("audio/") || obj.mime == "application/ogg") {
                "с поддержкой аудио"
            } else {
                "с поддержкой изображений"
            }
            error("Для этого объекта нет подходящей AI-модели — $AI_KEY_HINT $needed")
        }
        error(summarise(errors))
    }

    private fun summarise(errors: List<String>): String = when {
        errors.isNotEmpty() && errors.all { it.isNetworkError() } ->
            "AI недоступен — нет подключения к интернету"
        errors.isNotEmpty() && errors.all { it.isQuotaError() } ->
            "Бесплатные лимиты AI исчерпаны — вернитесь позже, платить не идём"
        else -> "AI недоступен — " +
            errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; ")
    }
}
