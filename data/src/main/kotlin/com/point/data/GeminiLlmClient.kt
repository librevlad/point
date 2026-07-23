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
 * Minimal Gemini (Generative Language API) client over HttpURLConnection — no
 * extra networking dependency. The model's text answer is materialised into a
 * scratch `.md` file and returned as a TEXT [ResultObject]. Small image/PDF
 * objects are attached inline (base64); larger ones are skipped (size guard).
 *
 * The key comes from BuildConfig.GEMINI_API_KEY (local.properties). A blank key
 * fails fast with a clear message the executor surfaces as a recoverable error.
 */
class GeminiLlmClient @Inject constructor(
    private val store: ObjectStore,
) : LlmClient {

    override suspend fun run(obj: PointObject, prompt: String): ResultObject =
        withContext(Dispatchers.IO) {
            val key = BuildConfig.GEMINI_API_KEY
            require(key.isNotBlank()) {
                "GEMINI_API_KEY не задан в local.properties — AI недоступен"
            }
            // Several models tried in order: a stale/zero-quota one (e.g. gemini-2.0-flash
            // 429s on the free tier) falls through to the next. Aliases like
            // gemini-flash-latest track a currently-serving free model.
            val models = BuildConfig.GEMINI_MODELS.split(',').map(String::trim).filter(String::isNotBlank)
            val errors = StringBuilder()
            for (model in models) {
                try {
                    val answer = request(key, model, obj, prompt)
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

    private fun request(key: String, model: String, obj: PointObject, prompt: String): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        maybeAttachFile(obj)?.let { parts.put(it) }

        val body = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("parts", parts)),
        )

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("Gemini HTTP $code: ${text.take(300)}")
        return parseAnswer(text)
    }

    private fun maybeAttachFile(obj: PointObject): JSONObject? {
        val attachable = obj.mime.startsWith("image/") || obj.mime == "application/pdf"
        if (!attachable) return null
        val file = File(obj.uri.value)
        if (!file.exists() || file.length() !in 1..MAX_INLINE_BYTES) return null
        val data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return JSONObject().put(
            "inlineData",
            JSONObject().put("mimeType", obj.mime).put("data", data),
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

    companion object {
        private const val MAX_INLINE_BYTES = 15L * 1024 * 1024
    }
}
