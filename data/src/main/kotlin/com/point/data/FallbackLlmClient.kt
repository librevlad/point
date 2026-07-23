package com.point.data

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

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {
        if (providers.isEmpty()) error("AI не настроен — задайте свой ключ")
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
            error("Для этого объекта нет подходящей AI-модели — задайте свой ключ с поддержкой изображений")
        }
        error(summarise(errors))
    }

    /** One human line instead of every provider's raw error concatenated. */
    private fun summarise(errors: List<String>): String = when {
        errors.isNotEmpty() && errors.all { it.isNetworkError() } ->
            "AI недоступен — нет подключения к интернету"
        else -> "AI недоступен — " +
            errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; ")
    }

    private fun String.isNetworkError(): Boolean = NETWORK_HINTS.any { contains(it, ignoreCase = true) }

    private companion object {
        val NETWORK_HINTS = listOf(
            "resolve host", "No address associated", "Unable to resolve",
            "connection abort", "Network is unreachable", "Failed to connect",
            "timed out", "timeout",
        )
    }
}
