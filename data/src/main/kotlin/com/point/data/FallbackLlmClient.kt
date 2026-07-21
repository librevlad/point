package com.point.data

import com.point.core.flow.LlmClient
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import javax.inject.Inject

/**
 * Runs an ordered list of providers, returning the first success. If a provider
 * is unconfigured or fails (e.g. Gemini returns HTTP 429 "quota exceeded"), the
 * next one is tried. If all fail, the combined errors are surfaced so the user
 * sees why. This is what makes the alternative AI an actual fallback.
 */
class FallbackLlmClient @Inject constructor(
    private val providers: List<@JvmSuppressWildcards LlmClient>,
) : LlmClient {

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {
        require(providers.isNotEmpty()) { "Нет настроенных AI-провайдеров" }
        val errors = StringBuilder()
        for (provider in providers) {
            try {
                return provider.run(obj, prompt)
            } catch (e: Exception) {
                errors.append(e.message ?: e.javaClass.simpleName).append("; ")
            }
        }
        error("AI недоступен — $errors")
    }
}
