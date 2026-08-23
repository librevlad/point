package com.point.core.flow

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

    private val frames: FrameForModel = FrameForModel.NONE,
) : LlmClient {

    override val strongVision = true

    override val serviceId = "gemini"

    override fun canHandle(obj: PointObject): Boolean =
        if (isAudio(obj)) geminiAttachmentMime(obj) != null else true

    override suspend fun run(obj: PointObject, prompt: String): ResultObject =
        withContext(Dispatchers.IO) {

            // Имя ключа сборки человеку не адресовано (#1236): он не заводил ни
            // `GEMINI_API_KEY`, ни `local.properties`.
            require(apiKey.isNotBlank()) { "AI не настроен — $AI_KEY_HINT" }
            var refused: Exception? = null
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

                    // Перебор моделей — наша механика, а не событие для человека (#1236):
                    // склейка «m1: …; m2: …» выносила на баннер идентификаторы моделей.
                    // Наружу уходит самый громкий отказ — своими словами и с кодом: последний
                    // терял квоту, стоило следующей модели промолчать пятисотой.
                    refused = louderRefusal(refused, e)
                }
            }

            // Спрашивать было нечего — и это не «ключ не задан»: ключ проверен выше (#1236).
            throw refused ?: IllegalStateException(SERVICE_NOT_SET_UP)
        }

    private suspend fun fetch(model: String, obj: PointObject, prompt: String): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        maybeAttachFile(obj)?.let { parts.put(it) }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val res = http.post(url, emptyMap(), body)

        // Отказ сервиса — теми же словами, что у остальных клиентов, и с кодом внутри
        // исключения: человеку слова, Point — код, чтобы «лимит исчерпан» не превратился
        // в «не отвечал» при пересказе (#1236). Сам ответ сервиса едет третьим полем — в
        // журнал обменов: на баннер он не попадает, а без него на стенде нечем разобрать,
        // чем именно сервис ответил на 400 и 500.
        if (res.code !in 200..299) {
            throw AiServiceRefusal(
                serviceId,
                res.code,
                serviceRefusal(res.code, hintFor(res.code)),
                serviceSaid = res.body,
            )
        }
        return parseAnswer(res.body)
    }

    private fun hintFor(code: Int): String? = if (code == 401 || code == 403) KEY_SETTINGS_CALL else null

    private fun maybeAttachFile(obj: PointObject): JSONObject? {
        val declared = geminiAttachmentMime(obj) ?: return null

        val attachment = frames.of(obj.uri.value, declared) ?: return null
        return JSONObject().put(
            "inlineData",
            JSONObject().put("mimeType", attachment.mime).put("data", attachment.base64),
        )
    }

    // Имена полей чужого JSON человеку ничего не объясняют (#1236): непрочитанный ответ
    // называется одним объявленным словом.
    private fun parseAnswer(json: String): String {
        val candidates = JSONObject(json).optJSONArray("candidates") ?: error(UNREADABLE_ANSWER)
        if (candidates.length() == 0) error(UNREADABLE_ANSWER)
        val parts = candidates.getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
        val out = buildString {
            for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
        }
        return out.ifBlank { error(UNREADABLE_ANSWER) }
    }
}
