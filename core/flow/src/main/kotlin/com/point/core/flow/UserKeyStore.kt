package com.point.core.flow

/**
 * Одно обращение к сервису: адрес, ключ и модель. Не хранилище — хранилище
 * держит ключ на каждый сервис ([UserAiKeys], #699).
 */
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

    fun keys(): UserAiKeys

    suspend fun save(key: UserAiKey)

    suspend fun forget(providerId: String)

    suspend fun clear()
}

const val KEY_SETTINGS_CALL = "Задайте ключ в настройках"

fun refusalNeedsKey(reason: String): Boolean =
    KEY_REFUSAL_MARKS.any { reason.contains(it, ignoreCase = true) }

private val KEY_REFUSAL_MARKS = listOf(AI_KEY_HINT, KEY_SETTINGS_CALL)
