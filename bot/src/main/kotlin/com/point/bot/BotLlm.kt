package com.point.bot

import com.point.core.flow.LlmClient
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bot's own JVM Gemini client (#92) — the same REST shape as the app's
 * `GeminiLlmClient`, but pure-JVM (java.util.Base64, HttpURLConnection). Small image/PDF
 * objects are inlined so the bot can read a photographed table or document. The answer is
 * materialised into a scratch `.md` and returned as TEXT.
 */
class BotLlm(
    private val apiKey: String,
    private val models: List<String>,
    private val scratchDir: File,
) : LlmClient {

    override val strongVision = true

    override suspend fun run(obj: PointObject, prompt: String): ResultObject = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "GEMINI_API_KEY не задан — AI недоступен" }
        val errors = StringBuilder()
        for (model in models) {
            runCatching { fetch(model, obj, prompt) }
                .onSuccess { answer ->
                    val ref = File(scratchDir.apply { mkdirs() }, "ai-${System.nanoTime()}.md")
                    ref.writeText(answer)
                    return@withContext ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(ref.absolutePath), mapOf("source" to "gemini"))
                }
                .onFailure { errors.append(model).append(": ").append(it.message).append("; ") }
        }
        error("Gemini недоступен — $errors")
    }

    private fun fetch(model: String, obj: PointObject, prompt: String): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        maybeAttach(obj)?.let { parts.put(it) }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).toString()
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        c.disconnect()
        if (code !in 200..299) error("HTTP $code: ${text.take(200)}")
        return parseAnswer(text)
    }

    private fun maybeAttach(obj: PointObject): JSONObject? {
        if (!(obj.mime.startsWith("image/") || obj.mime == "application/pdf")) return null
        val file = File(obj.uri.value)
        if (!file.isFile || file.length() !in 1..MAX_INLINE) return null
        val data = Base64.getEncoder().encodeToString(file.readBytes())
        return JSONObject().put("inlineData", JSONObject().put("mimeType", obj.mime).put("data", data))
    }

    private fun parseAnswer(json: String): String {
        val parts = JSONObject(json).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: error("пустой ответ")
        return buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
            .ifBlank { error("пустой текст") }
    }

    private companion object {
        const val MAX_INLINE = 15L * 1024 * 1024
    }
}
