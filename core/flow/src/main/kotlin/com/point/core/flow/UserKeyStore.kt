package com.point.core.flow

data class UserAiConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,

    val savedAt: Long = 0L,
) {
    companion object {

        val DEFAULT = UserAiConfig(
            apiKey = "",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "google/gemma-4-31b-it:free",
        )
    }
}

const val AI_KEY_HINT = "задайте свой ключ"

const val SETTINGS_TITLE = "Настройки"

interface UserKeyStore {
    fun read(): UserAiConfig?
    suspend fun save(config: UserAiConfig)
    suspend fun clear()
}

fun UserAiConfig?.keyFor(providerId: String): String =
    if (this != null && providerForBaseUrl(baseUrl)?.id == providerId) apiKey.trim() else ""

const val KEY_SETTINGS_CALL = "Задайте ключ в настройках"

fun refusalNeedsKey(reason: String): Boolean =
    KEY_REFUSAL_MARKS.any { reason.contains(it, ignoreCase = true) }

private val KEY_REFUSAL_MARKS = listOf(AI_KEY_HINT, KEY_SETTINGS_CALL)
