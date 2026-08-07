package com.point.desktop

import com.point.core.flow.LlmClient
import com.point.core.flow.jsonObject
import com.point.core.flow.parseJson
import com.point.core.flow.str
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class DesktopLlmClient(
    private val config: () -> AiConfig,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,
) : LlmClient {

    override fun canHandle(obj: PointObject): Boolean = obj.state.kind != ObjectKind.IMAGE

    override val configured: Boolean get() = config().key.isNotBlank()

    override suspend fun run(obj: PointObject, prompt: String): ResultObject = withContext(Dispatchers.IO) {
        val cfg = config()
        require(cfg.key.isNotBlank()) { "Ключ AI не задан" }
        val text = readText(obj)
        val body = jsonRequest(cfg.model, prompt, text)
        val answer = post(cfg.url, cfg.key, body)
        val file = File.createTempFile("pc-ai-", ".txt").apply { writeText(answer) }
        ResultObject(
            type = ObjectKind.TEXT,
            mime = "text/plain",
            uri = ScratchRef(file.absolutePath),
            metadata = mapOf("name" to "Ответ AI"),
        )
    }

    private fun readText(obj: PointObject): String {
        val file = File(obj.uri.value)
        require(file.isFile) { "Файла объекта нет на диске" }

        return file.readText().take(MAX_CHARS)
    }

    private fun jsonRequest(model: String, prompt: String, text: String): String {
        val message = jsonObject("role" to "user", "content" to (prompt + "\n\n" + text))
        return """{"model":"${escape(model)}","messages":[$message]}"""
    }

    private fun post(url: String, key: String, body: String): String {
        val connection = URL(url.trimEnd('/')).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val reply = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(code in 200..299) { refusal(code, reply) }
            answerOf(reply) ?: error("Сервис ответил, но без ответа")
        } finally {
            connection.disconnect()
        }
    }

    private fun refusal(code: Int, reply: String): String = when (code) {
        401, 403 -> "Ключ AI не подошёл — проверьте его в ~/.point-pc/config"
        402 -> "У этого ключа кончилась бесплатная квота"
        429 -> "Сервис просит подождать — слишком много запросов подряд"
        in 500..599 -> "Сервис AI сейчас не отвечает"
        else -> "Сервис AI отказал (" + code + ")" + reply.take(200).let { if (it.isBlank()) "" else ": $it" }
    }

    private fun answerOf(reply: String): String? {
        val json = parseJson(reply)
        val choices = (json as? com.point.core.flow.JsonValue.Obj)?.fields?.get("choices")
        val first = (choices as? com.point.core.flow.JsonValue.Arr)?.items?.firstOrNull()
        val message = (first as? com.point.core.flow.JsonValue.Obj)?.fields?.get("message")
        return message.str("content")?.takeIf { it.isNotBlank() }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {

        const val MAX_CHARS = 120_000
    }
}

data class AiConfig(
    val key: String = "",
    val url: String = DEFAULT_URL,
    val model: String = DEFAULT_MODEL,
) {
    companion object {

        const val DEFAULT_URL = "https://openrouter.ai/api/v1/chat/completions"

        const val DEFAULT_MODEL = "meta-llama/llama-3.3-70b-instruct:free"
    }
}
