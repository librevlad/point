package com.point.data

import com.point.core.flow.AiKeyCheck
import com.point.core.flow.KEY_PROBE_PROMPT
import com.point.core.flow.KeyProbe
import com.point.core.flow.UserAiConfig
import com.point.core.flow.withoutKey
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

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

            .put("max_tokens", PROBE_TOKENS)
            .toString()

        val result = runCatching {
            http.post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
        }

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

    private fun replyOf(json: String): String = runCatching {
        JSONObject(json).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content").trim()
    }.getOrDefault("")

    private companion object {
        const val PROBE_TOKENS = 16
    }
}
