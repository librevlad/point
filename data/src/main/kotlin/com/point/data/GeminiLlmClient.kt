package com.point.data

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

/**
 * Minimal Gemini (Generative Language API) client over [HttpJson] — no SDK. The
 * model's text answer is materialised into a scratch `.md` file and returned as a
 * TEXT [ResultObject]. Image/PDF objects are attached inline (base64) by
 * [inlineAttachment] — оно же держит предел размера кадра, общий для всех клиентов.
 *
 * Key and [models] are injected (from BuildConfig, in DataModule) rather than read
 * from BuildConfig here — so the multi-model fallback is unit-testable regardless of
 * the build's keys. [models] are tried in order, so a stale/zero-quota model (e.g.
 * gemini-2.0-flash 429s on the free tier) falls through to the next.
 */
class GeminiLlmClient(
    private val http: HttpJson,
    private val store: ObjectStore,
    private val apiKey: String,
    private val models: List<String>,
) : LlmClient {

    override val strongVision = true // Gemini reads dense/handwritten tables far better than free models

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
                    File(ref.value).writeText(answer)
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
        val res = http.post(url, emptyMap(), body) // Gemini authenticates via the ?key= query param
        if (res.code !in 200..299) error("Gemini HTTP ${res.code}: ${res.body.take(300)}")
        return parseAnswer(res.body)
    }

    private fun maybeAttachFile(obj: PointObject): JSONObject? {
        val attachable = obj.mime.startsWith("image/") || obj.mime == "application/pdf"
        if (!attachable) return null
        // Размер отправляемого кадра решает [inlineAttachment] — один предел на всех клиентов;
        // mime берётся у вложения, а не у объекта: ужатый кадр перекодирован, и запрос обязан
        // называть то, что в нём лежит.
        val attachment = inlineAttachment(obj.uri.value, obj.mime) ?: return null
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
