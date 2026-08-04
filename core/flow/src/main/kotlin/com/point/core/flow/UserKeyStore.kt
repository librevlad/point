package com.point.core.flow

/**
 * The user's own AI credentials (bring-your-own-key), entered in-app. A released
 * build must run on the USER's quota, not ours — so the key lives here, on-device,
 * not baked into the APK.
 */
data class UserAiConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
) {
    companion object {
        /** Sensible starting point shown in the key screen: a free OpenRouter model. */
        val DEFAULT = UserAiConfig(
            apiKey = "",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "google/gemma-4-31b-it:free",
        )
    }
}

/**
 * По чему узнают отказ «работать нечем, нужен твой ключ» (#452).
 *
 * Отказ приходит с самого дна (`:data`) обычным текстом, а решает по нему экран (`:app`): показать
 * рядом с причиной предложение задать ключ. Пока фраза была написана в обоих местах отдельно,
 * это была догадка по прозе: правка текста в одном модуле молча отключала предложение в другом.
 * Теперь она одна на всех — общий модуль, от которого зависят оба.
 *
 * Марка — часть фразы, а не вся: тексты у отказов разные («AI не настроен — …», «нет подходящей
 * модели — …»), общее в них ровно это.
 */
const val AI_KEY_HINT = "задайте свой ключ"

/** Persists [UserAiConfig] on-device. [read] is cheap (a prefs get) so the AI layer
 *  can consult it on every call — the key can change at any moment via the screen. */
interface UserKeyStore {
    fun read(): UserAiConfig?
    suspend fun save(config: UserAiConfig)
    suspend fun clear()
}
