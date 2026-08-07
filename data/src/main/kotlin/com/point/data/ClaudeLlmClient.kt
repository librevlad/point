package com.point.data

import com.point.core.flow.LlmClient
import com.point.core.flow.withoutPreamble
import com.point.core.flow.ObjectStore
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

class ClaudeLlmClient @Inject constructor(
    private val http: HttpJson,
    private val store: ObjectStore,
) : LlmClient {

    private val model: String get() = BuildConfig.CLAUDE_MODEL.ifBlank { DEFAULT_MODEL }
    private val baseUrl: String get() = BuildConfig.ANTHROPIC_BASE_URL.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')

    override val strongVision = true

    override fun canHandle(obj: PointObject): Boolean = !isAudio(obj)

    override suspend fun run(obj: PointObject, prompt: String): ResultObject =
        withContext(Dispatchers.IO) {
            val key = BuildConfig.ANTHROPIC_API_KEY
            require(key.isNotBlank()) { "ANTHROPIC_API_KEY не задан" }
            val res = http.post(
                "$baseUrl/v1/messages",
                mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION),
                requestBody(obj, prompt),
            )
            if (res.code !in 200..299) error("Claude HTTP ${res.code}: ${res.body.take(300)}")
            val answer = parseAnswer(res.body)
            val ref = store.newScratchFile("md")

            File(ref.value).writeText(withoutPreamble(answer))
            ResultObject(
                type = ObjectKind.TEXT,
                mime = "text/markdown",
                uri = ref,
                metadata = mapOf("source" to "claude", "model" to model),
            )
        }

    private fun requestBody(obj: PointObject, prompt: String): String {

        val content = JSONArray()
        maybeAttachment(obj)?.let { content.put(it) }
        content.put(JSONObject().put("type", "text").put("text", prompt))

        return JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .toString()
    }

    private fun maybeAttachment(obj: PointObject): JSONObject? {
        val isImage = obj.mime.startsWith("image/")
        val isPdf = obj.mime == "application/pdf"
        if (!isImage && !isPdf) return null
        val attachment = inlineAttachment(obj.uri.value, obj.mime) ?: return null
        val source = JSONObject()
            .put("type", "base64")
            .put("media_type", attachment.mime)
            .put("data", attachment.base64)
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
    }
}
