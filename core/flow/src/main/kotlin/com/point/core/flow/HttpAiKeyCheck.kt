package com.point.core.flow

import org.json.JSONArray
import org.json.JSONObject

class HttpAiKeyCheck(
    private val http: HttpJson,
) : AiKeyCheck {

    override suspend fun check(config: UserAiConfig): KeyProbe {
        val key = config.apiKey.trim()
        val model = config.model.trim().ifBlank { UserAiConfig.DEFAULT.model }
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", KEY_PROBE_PROMPT)),
            )

            .put("max_tokens", PROBE_TOKENS)
            .toString()

        val result = runCatching {
            http.post(probeUrl(config.baseUrl), mapOf("Authorization" to "Bearer $key"), body)
        }

        val response = result.getOrElse { failure ->
            return KeyProbe(error = withoutKey(failure.message ?: failure.toString(), key))
        }
        val text = withoutKey(response.body, key)
        return if (response.code in 200..299) {
            val reply = replyOf(text)
            KeyProbe(status = response.code, reply = reply, error = if (reply == null) errorOf(text) else null)
        } else {
            KeyProbe(status = response.code, error = text)
        }
    }

    /**
     * Слова модели; пустая строка — модель ответила, но слов не прислала; `null` — ответа
     * модели в теле нет.
     */
    private fun replyOf(json: String): String? = runCatching {
        val message = JSONObject(json).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        if (message.isNull("content")) "" else message.optString("content").trim()
    }.getOrNull()

    /** Что сервис сказал вместо ответа: шлюзы присылают ошибку и под кодом 200. */
    private fun errorOf(json: String): String? = runCatching {
        val error = JSONObject(json).opt("error")
        when (error) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {

        // Запас — на одно слово и короткое раздумье: думающие модели начинают с него.
        const val PROBE_TOKENS = 64
    }
}

/**
 * Адрес проверки: у сервиса записана «база», а компьютер до сих пор хранил полный адрес
 * вызова. Оба вида читаются одинаково — иначе проверка стучалась бы не туда (#610).
 *
 * Правило приехало с компьютера в общий код (#828): своя копия проверки ключа там больше не
 * живёт, а устойчивость к полному адресу досталась заодно и телефону.
 */
fun probeUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/').ifBlank { UserAiConfig.DEFAULT.baseUrl }
    return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
}
