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

/** Persists [UserAiConfig] on-device. [read] is cheap (a prefs get) so the AI layer
 *  can consult it on every call — the key can change at any moment via the screen. */
interface UserKeyStore {
    fun read(): UserAiConfig?
    suspend fun save(config: UserAiConfig)
    suspend fun clear()
}
