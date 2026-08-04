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
 * Слова, которыми отказ называет средство: ключ, заданный в настройках.
 *
 * Вторая марка того же признака, что и [AI_KEY_HINT], потому что отказы говорят разными словами.
 * Отказ расшифровки (#467) зовёт задать ключ, ни разу не сказав «задайте свой ключ», — и по одной
 * марке предложение под ним не появилось бы вовсе.
 */
const val KEY_SETTINGS_CALL = "Задайте ключ в настройках"

/**
 * Этот отказ человек чинит ключом?
 *
 * Признак живёт здесь, в чистом Kotlin, а не в экране: отказ приходит с самого дна (`:data`)
 * обычным текстом, и читают его двое — экран (показать предложение задать ключ) и тест. Сравнение
 * с ОДНОЙ фразой уже подводило: отказ, сказанный другими словами, молча оставался без предложения
 * (#467). Марок поэтому столько, сколько у отказов поколений слов, и лежат они рядом.
 */
fun refusalNeedsKey(reason: String): Boolean =
    KEY_REFUSAL_MARKS.any { reason.contains(it, ignoreCase = true) }

/** [AI_KEY_HINT] — из цепочки моделей, [KEY_SETTINGS_CALL] — из расшифровки. */
private val KEY_REFUSAL_MARKS = listOf(AI_KEY_HINT, KEY_SETTINGS_CALL)
