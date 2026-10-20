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
        "cloudflare" to BuildConfig.CLOUDFLARE_API_KEY,
        "modelscope" to BuildConfig.MODELSCOPE_API_KEY,
        "nvidia" to BuildConfig.NVIDIA_API_KEY,
        "anthropic" to BuildConfig.ANTHROPIC_API_KEY,
    )

    override fun key(providerId: String): String = ours[providerId].orEmpty().trim()

    // Workers AI без номера аккаунта в цепочку не входит (`DataModule.cloudflareModels`) —
    // значит, и «работает на ключе Point» про него сказать нельзя.
    override fun have(): Set<String> = ours.filterValues { it.isNotBlank() }.keys
        .filterNot { it == CLOUDFLARE && BuildConfig.CLOUDFLARE_ACCOUNT_ID.isBlank() }.toSet()

    // «Проверить все» стучался с ключом сборки по адресу без номера аккаунта и записывал
    // Workers AI в «не отвечал», хотя цепочка ходит к нему по полному адресу.
    override fun baseUrl(providerId: String): String =
        if (providerId == CLOUDFLARE && BuildConfig.CLOUDFLARE_ACCOUNT_ID.isNotBlank()) {
            "${BuildConfig.CLOUDFLARE_BASE_URL}/${BuildConfig.CLOUDFLARE_ACCOUNT_ID}/ai/v1"
        } else {
            ""
        }

    private companion object {
        const val CLOUDFLARE = "cloudflare"
    }
}
