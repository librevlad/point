package com.point.data

import android.util.Base64
import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Endpoint config for one OpenAI-compatible provider (OpenRouter, Groq, Cerebras, Mistral, OpenAI, …). */
data class OpenAiProvider(
    val label: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

/** The active providers, order preserved — a blank key means "not signed up", so skip it. */
fun List<OpenAiProvider>.configured(): List<OpenAiProvider> = filter { it.apiKey.isNotBlank() }

/**
 * Any provider speaking the OpenAI Chat Completions API — OpenRouter, Groq,
 * Cerebras, Mistral, OpenAI, or a local server — behind one [OpenAiProvider]
 * config (base URL, key, model). No SDK: HttpURLConnection + org.json. Small
 * images are attached as a data-URL; the answer is materialised to `.md`. One
 * instance per configured provider; FallbackLlmClient chains them for reliability.
 */
class OpenAiCompatibleClient(
    private val store: ObjectStore,
    private val provider: OpenAiProvider,
) : LlmClient {

    private val baseUrl: String = provider.baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')

    override suspend fun run(obj: PointObject, prompt: String): ResultObject =
        withContext(Dispatchers.IO) {
            require(provider.apiKey.isNotBlank()) { "${provider.label}: ключ не задан" }
            val answer = request(obj, prompt)
            val ref = store.newScratchFile("md")
            File(ref.value).writeText(answer)
            ResultObject(
                type = ObjectKind.TEXT,
                mime = "text/markdown",
                uri = ref,
                metadata = mapOf("source" to provider.label, "model" to provider.model),
            )
        }

    private fun request(obj: PointObject, prompt: String): String {
        val message = JSONObject().put("role", "user")
        val image = maybeImage(obj)
        if (image != null) {
            message.put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(image),
            )
        } else {
            message.put("content", prompt) // plain string — maximal compatibility
        }

        val body = JSONObject()
            .put("model", provider.model)
            .put("messages", JSONArray().put(message))

        val conn = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("${provider.label} HTTP $code: ${text.take(300)}")
        return parseAnswer(text)
    }

    private fun maybeImage(obj: PointObject): JSONObject? {
        if (!obj.mime.startsWith("image/")) return null
        val file = File(obj.uri.value)
        if (!file.exists() || file.length() !in 1..MAX_INLINE_BYTES) return null
        val data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:${obj.mime};base64,$data"))
    }

    private fun parseAnswer(json: String): String {
        val choices = JSONObject(json).optJSONArray("choices")
            ?: error("${provider.label}: ответ без choices")
        if (choices.length() == 0) error("${provider.label}: пустой ответ")
        val content = choices.getJSONObject(0).getJSONObject("message").optString("content")
        return content.ifBlank { error("${provider.label}: пустой текст") }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val MAX_INLINE_BYTES = 15L * 1024 * 1024
    }
}
