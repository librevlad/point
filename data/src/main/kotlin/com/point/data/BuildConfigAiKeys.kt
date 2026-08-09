package com.point.data

import com.point.core.flow.BuiltInAiKeys
import com.point.core.flow.GROQ_PROVIDER_ID
import com.point.core.flow.MISTRAL_PROVIDER_ID
import javax.inject.Inject

/**
 * Ключи, зашитые в сборку. Человек их не вписывал и заменить не может — но
 * видеть, что сервис работает не на его ключе, обязан (#699).
 */
class BuildConfigAiKeys @Inject constructor() : BuiltInAiKeys {

    private val ours: Map<String, String> = mapOf(
        "openrouter" to BuildConfig.OPENROUTER_API_KEY,
        GROQ_PROVIDER_ID to BuildConfig.GROQ_API_KEY,
        MISTRAL_PROVIDER_ID to BuildConfig.MISTRAL_API_KEY,
        "sambanova" to BuildConfig.SAMBANOVA_API_KEY,
        "gemini" to BuildConfig.GEMINI_API_KEY,
        "cerebras" to BuildConfig.CEREBRAS_API_KEY,
        "zhipu" to BuildConfig.ZHIPU_API_KEY,
        "openai" to BuildConfig.OPENAI_API_KEY,
        "anthropic" to BuildConfig.ANTHROPIC_API_KEY,
    )

    override fun key(providerId: String): String = ours[providerId].orEmpty().trim()

    override fun have(): Set<String> = ours.filterValues { it.isNotBlank() }.keys
}
