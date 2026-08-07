package com.point.data

import com.point.core.flow.LlmClient
import com.point.core.flow.withoutPreamble
import com.point.core.flow.ObjectStore
import com.point.core.flow.modelReadableAudio
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun geminiAttachmentMime(obj: PointObject): String? = when {
    obj.mime.startsWith("image/") || obj.mime == "application/pdf" -> obj.mime
    else -> modelReadableAudio(obj.mime, obj.metadata["name"])
}

internal fun isAudio(obj: PointObject): Boolean =
    obj.mime.startsWith("audio/") || obj.mime == "application/ogg"

class GeminiLlmClient(
    private val http: HttpJson,
    private val store: ObjectStore,
    private val apiKey: String,
    private val models: List<String>,
) : LlmClient {

    override val strongVision = true

    override fun canHandle(obj: PointObject): Boolean =
        if (isAudio(obj)) geminiAttachmentMime(obj) != null else true

    override suspend fun run(obj: PointObject, prompt: String): ResultObject =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) {
                "GEMINI_API_KEY не задан в local.properties — AI недоступен"
            }
            val errors = StringBuilder()
            for (model in models) {
                try {
                    val answer = fetch(model, obj, prompt)
                    val ref = store.newScratchFile("md")

            File(ref.value).writeText(withoutPreamble(answer))
                    return@withContext ResultObject(
                        type = ObjectKind.TEXT,
                        mime = "text/markdown",
                        uri = ref,
                        metadata = mapOf("source" to "gemini", "model" to model),
                    )
                } catch (e: Exception) {
                    errors.append(model).append(": ").append(e.message ?: "error").append("; ")
                }
            }
            error("Gemini недоступен — $errors")
        }

    private suspend fun fetch(model: String, obj: PointObject, prompt: String): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        maybeAttachFile(obj)?.let { parts.put(it) }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val res = http.post(url, emptyMap(), body)
        if (res.code !in 200..299) error("Gemini HTTP ${res.code}: ${res.body.take(300)}")
        return parseAnswer(res.body)
    }

    private fun maybeAttachFile(obj: PointObject): JSONObject? {
        val declared = geminiAttachmentMime(obj) ?: return null

        val attachment = inlineAttachment(obj.uri.value, declared) ?: return null
        return JSONObject().put(
            "inlineData",
            JSONObject().put("mimeType", attachment.mime).put("data", attachment.base64),
        )
    }

    private fun parseAnswer(json: String): String {
        val candidates = JSONObject(json).optJSONArray("candidates")
            ?: error("Gemini не вернул кандидатов")
        if (candidates.length() == 0) error("Gemini вернул пустой ответ")
        val parts = candidates.getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
        val out = buildString {
            for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
        }
        return out.ifBlank { error("Gemini вернул пустой текст") }
    }
}
