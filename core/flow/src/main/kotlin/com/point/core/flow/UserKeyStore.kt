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

/**
 * Ключ человека для КОНКРЕТНОГО провайдера — или пустая строка, если выбран не он.
 *
 * [UserAiConfig] хранит один ключ выбранного провайдера, и какой это провайдер, видно по адресу.
 * Развилка нужна не только цепочке моделей: у Groq есть отдельная ручка расшифровки
 * (`/audio/transcriptions`), и до #467 она умела брать ключ только из сборки — то есть в
 * раздаваемой сборке не работала никогда, а введённый человеком ключ Groq не включал её вовсе.
 */
fun UserAiConfig?.keyFor(providerId: String): String =
    if (this != null && providerForBaseUrl(baseUrl)?.id == providerId) apiKey.trim() else ""

/**
 * Слова, которыми отказ зовёт человека в настройки.
 *
 * Не украшение: по ним экран узнаёт отказ, который чинится ключом, и открывает экран ключей сам —
 * одним тапом вместо «ищите шестерёнку».
 */
const val KEY_SETTINGS_CALL = "Задайте ключ в настройках"

/**
 * Этот отказ человек чинит ключом?
 *
 * Раньше `FlowViewModel` сравнивал текст с единственной фразой «задайте свой ключ», и отказ,
 * сказанный другими словами, оставлял человека наедине с непонятной ошибкой (#467). Признак живёт
 * здесь, в чистом Kotlin, — одно место, которое судится тестом, а не переписывается в каждом
 * реализаторе.
 */
fun refusalNeedsKey(reason: String): Boolean =
    KEY_REFUSAL_MARKS.any { reason.contains(it, ignoreCase = true) }

/** Оба поколения слов: новое — из [KEY_SETTINGS_CALL], старое — из цепочки моделей. */
private val KEY_REFUSAL_MARKS = listOf(KEY_SETTINGS_CALL, "задайте свой ключ")
