package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ClaudeLlmClient(
    private val http: HttpJson,
    private val store: ObjectStore,

    /** Ключ и адрес приходят снаружи: сборка знает их, ядро — нет (#828). */
    private val apiKey: String,
    baseUrl: String = "",
    model: String = "",

    private val frames: FrameForModel = FrameForModel.NONE,
) : LlmClient {

    private val model: String = model.ifBlank { DEFAULT_MODEL }
    private val baseUrl: String = baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')

    override val strongVision = true

    override val serviceId = "anthropic"

    override fun canHandle(obj: PointObject): Boolean = !isAudio(obj)

    override suspend fun run(obj: PointObject, prompt: String): ResultObject =
        withContext(Dispatchers.IO) {
            val key = apiKey

            // Имя ключа сборки человеку не адресовано (#1236).
            require(key.isNotBlank()) { "AI не настроен — $AI_KEY_HINT" }
            val res = http.post(
                "$baseUrl/v1/messages",
                mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION),
                requestBody(obj, prompt),
            )

            // Отказ сервиса — общими словами, код остаётся внутри исключения (#1236):
            // «Claude HTTP 429: {"type":"error"…}» уходил человеку на баннер дословно. Сам
            // ответ сервиса едет отдельным полем в журнал обменов и человеку не показывается.
            if (res.code !in 200..299) {
                throw AiServiceRefusal(
                    serviceId,
                    res.code,
                    serviceRefusal(res.code, hintFor(res.code)),
                    serviceSaid = res.body,
                )
            }
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
        val attachment = frames.of(obj.uri.value, obj.mime) ?: return null
        val source = JSONObject()
            .put("type", "base64")
            .put("media_type", attachment.mime)
            .put("data", attachment.base64)
        return JSONObject()
            .put("type", if (isImage) "image" else "document")
            .put("source", source)
    }

    private fun hintFor(code: Int): String? = if (code == 401 || code == 403) KEY_SETTINGS_CALL else null

    // Имена полей чужого JSON человеку ничего не объясняют (#1236).
    private fun parseAnswer(json: String): String {
        val root = JSONObject(json)
        if (root.optString("stop_reason") == "refusal") error(SERVICE_REFUSED_REQUEST)
        val content = root.optJSONArray("content") ?: error(UNREADABLE_ANSWER)
        val out = buildString {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
        return out.ifBlank { error(UNREADABLE_ANSWER) }
    }

    private companion object {
        const val DEFAULT_MODEL = "claude-opus-4-8"
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_TOKENS = 8192
    }
}
