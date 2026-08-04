package com.point.data

import com.point.core.flow.AiKeyCheck
import com.point.core.flow.KEY_PROBE_PROMPT
import com.point.core.flow.KeyProbe
import com.point.core.flow.UserAiConfig
import com.point.core.flow.withoutKey
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Живая проверка ключа одним коротким запросом (#465).
 *
 * Тем же путём, каким потом пойдут настоящие действия ([OpenAiCompatibleClient]): тот же адрес, тот
 * же заголовок, та же дверь `/chat/completions`. Проверять чем-то другим значит проверять не то —
 * «работает» обязано означать «работает то, что человек нажмёт следующим».
 *
 * Сеть — за [HttpJson], поэтому все исходы (401, 429, оборванная связь) судятся юнит-тестом с
 * фейком, без единого настоящего запроса.
 *
 * Ключ вычёркивается из ответа **здесь**, на границе: дальше текст идёт на экран, и то, что сервис
 * вернул нам обратно, не имеет права принести секрет с собой.
 */
class HttpAiKeyCheck @Inject constructor(
    private val http: HttpJson,
) : AiKeyCheck {

    override suspend fun check(config: UserAiConfig): KeyProbe {
        val key = config.apiKey.trim()
        val base = config.baseUrl.trim().trimEnd('/').ifBlank { UserAiConfig.DEFAULT.baseUrl }
        val model = config.model.trim().ifBlank { UserAiConfig.DEFAULT.model }
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", KEY_PROBE_PROMPT)),
            )
            // Проверка не должна стоить человеку заметной части квоты: ответ нужен короткий.
            .put("max_tokens", PROBE_TOKENS)
            .toString()

        val result = runCatching {
            http.post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
        }
        // До ответа не дошли — статуса нет, и это отдельный исход: «нет связи» чинится не тем же,
        // чем «сервис отказал».
        val response = result.getOrElse { failure ->
            return KeyProbe(error = withoutKey(failure.message ?: failure.toString(), key))
        }
        val text = withoutKey(response.body, key)
        return if (response.code in 200..299) {
            KeyProbe(status = response.code, reply = replyOf(text))
        } else {
            KeyProbe(status = response.code, error = text)
        }
    }

    /** Что сказала модель. Ответ не по форме — пустая строка: приговор назовёт это своим именем. */
    private fun replyOf(json: String): String = runCatching {
        JSONObject(json).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content").trim()
    }.getOrDefault("")

    private companion object {
        const val PROBE_TOKENS = 16
    }
}
