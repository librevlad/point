package com.point.desktop

import com.point.core.flow.AiKeyCheck
import com.point.core.flow.KEY_PROBE_PROMPT
import com.point.core.flow.KeyProbe
import com.point.core.flow.UserAiConfig
import com.point.core.flow.JsonValue
import com.point.core.flow.jsonObject
import com.point.core.flow.parseJson
import com.point.core.flow.str
import com.point.core.flow.withoutKey
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Проверка ключа на компьютере — та же, что на телефоне (#610).
 *
 * На телефоне сервис выбирается из списка и ключ проверяется до первого дела; на компьютере
 * стояло голое поле, и человек узнавал о неподошедшем ключе из провалившегося действия. Одно
 * и то же положение человека называлось на двух устройствах по-разному.
 *
 * Слова отказа сюда не пишутся: их говорит общий `keyVerdict`, один на обе стороны.
 */
class DesktopAiKeyCheck(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : AiKeyCheck {

    override suspend fun check(config: UserAiConfig): KeyProbe = withContext(Dispatchers.IO) {
        val key = config.apiKey.trim()
        val body = """{"model":"${config.model.trim()}","messages":[""" +
            jsonObject("role" to "user", "content" to KEY_PROBE_PROMPT) +
            """],"max_tokens":$PROBE_TOKENS}"""

        val connection = runCatching { open(probeUrl(config.baseUrl)) }.getOrElse {
            return@withContext KeyProbe(error = withoutKey(it.message ?: it.toString(), key))
        }
        try {
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val said = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val text = withoutKey(said, key)
            if (code in 200..299) KeyProbe(status = code, reply = replyOf(text)) else KeyProbe(status = code, error = text)
        } catch (failure: Exception) {
            KeyProbe(error = withoutKey(failure.message ?: failure.toString(), key))
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
    }

    private fun replyOf(json: String): String {
        val choices = (parseJson(json) as? JsonValue.Obj)?.fields?.get("choices")
        val first = (choices as? JsonValue.Arr)?.items?.firstOrNull()
        val message = (first as? JsonValue.Obj)?.fields?.get("message")
        return message.str("content").orEmpty().trim()
    }

    private companion object { const val PROBE_TOKENS = 16 }
}

/**
 * Адрес проверки: у сервиса записана «база», а компьютер до сих пор хранил полный адрес
 * вызова. Оба вида здесь читаются одинаково — иначе проверка стучалась бы не туда.
 */
fun probeUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/').ifBlank { UserAiConfig.DEFAULT.baseUrl }
    return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
}
