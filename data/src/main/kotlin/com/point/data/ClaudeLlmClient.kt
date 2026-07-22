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
import javax.inject.Inject

/**
 * Anthropic (Claude) provider over the native Messages API — HttpURLConnection +
 * org.json, no SDK (consistent with the Gemini/OpenAI clients; keeps the APK
 * lean). Images and PDFs are attached inline as base64 content blocks; the text
 * answer is materialised to a scratch `.md` file.
 *
 * Key/model/base URL come from BuildConfig (local.properties). A blank key fails
 * fast so the [FallbackLlmClient] moves on to the next provider. Model defaults
 * to `claude-opus-4-8`; override with CLAUDE_MODEL.
 */
class ClaudeLlmClient @Inject constructor(
    private val store: ObjectStore,
) : LlmClient {

    private val model: String get() = BuildConfig.CLAUDE_MODEL.ifBlank { DEFAULT_MODEL }
    private val baseUrl: String get() = BuildConfig.ANTHROPIC_BASE_URL.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')

    override suspend fun run(obj: PointObject, prompt: String): ResultObject =
        withContext(Dispatchers.IO) {
            val key = BuildConfig.ANTHROPIC_API_KEY
            require(key.isNotBlank()) { "ANTHROPIC_API_KEY не задан" }
            val answer = request(key, obj, prompt)
            val ref = store.newScratchFile("md")
            File(ref.value).writeText(answer)
            ResultObject(
                type = ObjectKind.TEXT,
                mime = "text/markdown",
                uri = ref,
                metadata = mapOf("source" to "claude", "model" to model),
            )
        }

    private fun request(key: String, obj: PointObject, prompt: String): String {
        // content: attachment first (Anthropic's recommended ordering), then the prompt text.
        val content = JSONArray()
        maybeAttachment(obj)?.let { content.put(it) }
        content.put(JSONObject().put("type", "text").put("text", prompt))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val conn = (URL("$baseUrl/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("Claude HTTP $code: ${text.take(300)}")
        return parseAnswer(text)
    }

    /** Image mimes become an image block; application/pdf a document block; others skipped. */
    private fun maybeAttachment(obj: PointObject): JSONObject? {
        val isImage = obj.mime.startsWith("image/")
        val isPdf = obj.mime == "application/pdf"
        if (!isImage && !isPdf) return null
        val file = File(obj.uri.value)
        if (!file.exists() || file.length() !in 1..MAX_INLINE_BYTES) return null
        val data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val source = JSONObject()
            .put("type", "base64")
            .put("media_type", obj.mime)
            .put("data", data)
        return JSONObject()
            .put("type", if (isImage) "image" else "document")
            .put("source", source)
    }

    private fun parseAnswer(json: String): String {
        val root = JSONObject(json)
        if (root.optString("stop_reason") == "refusal") error("Claude отклонил запрос")
        val content = root.optJSONArray("content") ?: error("Claude не вернул content")
        val out = buildString {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
        return out.ifBlank { error("Claude вернул пустой текст") }
    }

    private companion object {
        const val DEFAULT_MODEL = "claude-opus-4-8"
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_TOKENS = 8192
        const val MAX_INLINE_BYTES = 15L * 1024 * 1024
    }
}
