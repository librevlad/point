package com.point.data

import com.point.core.flow.AI_KEY_HINT
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

data class OpenAiProvider(
    val label: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,

    val vision: Boolean = false,

    val strongVision: Boolean = false,

    /** Сервис, за которым запоминается исход обращения (#699). */
    val id: String = label,
)

fun List<OpenAiProvider>.configured(): List<OpenAiProvider> = filter { it.apiKey.isNotBlank() }

fun openAiModels(label: String, baseUrl: String, apiKey: String, models: String): List<OpenAiProvider> =
    models.split(',').map(String::trim).filter(String::isNotBlank)
        .map {
            OpenAiProvider(
                label, baseUrl, apiKey, it,
                vision = isVisionModel(it),
                strongVision = isMeasuredStrongVision(it),
            )
        }

fun isVisionModel(model: String): Boolean {
    val m = model.lowercase()
    return VISION_MODEL_HINTS.any { it in m }
}

private val VISION_MODEL_HINTS = listOf(
    "gemma-3", "gemma-4", "gemma3", "gemma4", "pixtral", "llava", "-vl", "vl-",
    "gpt-4o", "gpt-4.1", "vision", "llama-3.2", "llama-4", "internvl", "minicpm-v",
    "qwen2-vl", "qwen2.5-vl", "molmo", "phi-3.5-vision", "phi-4-multimodal",

    "mistral-small", "mistral-medium", "ministral",

    "qwen3.6",
)

fun isMeasuredStrongVision(model: String): Boolean {
    val m = model.lowercase()
    return STRONG_VISION_MEASURED.any { it in m }
}

private val STRONG_VISION_MEASURED = listOf(
    "gemma-4", "gemma4", "qwen3.6", "qwen2.5-vl", "qwen2-vl",
)

class OpenAiCompatibleClient(
    private val http: HttpJson,
    private val store: ObjectStore,
    private val provider: OpenAiProvider,
) : LlmClient {

    private val baseUrl: String = provider.baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')

    override val strongVision: Boolean = provider.strongVision

    override val serviceId: String = provider.id

    override fun canHandle(obj: PointObject): Boolean = when {
        isImage(obj) -> provider.vision
        obj.mime == "application/pdf" -> false

        isAudio(obj) -> false
        else -> true
    }

    override suspend fun run(obj: PointObject, prompt: String): ResultObject =
        withContext(Dispatchers.IO) {
            require(provider.apiKey.isNotBlank()) { "${provider.label}: ключ не задан" }
            val res = http.post(
                "$baseUrl/chat/completions",
                mapOf("Authorization" to "Bearer ${provider.apiKey}"),
                requestBody(obj, promptFor(obj, prompt)),
            )
            if (res.code !in 200..299) {
                throw com.point.core.flow.AiServiceRefusal(provider.id, res.code, refusal(res.code))
            }
            val answer = parseAnswer(res.body)

            if (isImage(obj) && answer.trimStart().startsWith(NO_IMAGE_MARKER)) {
                error("${provider.label}: модель не увидела изображение")
            }
            val ref = store.newScratchFile("md")

            File(ref.value).writeText(withoutPreamble(answer))
            ResultObject(
                type = ObjectKind.TEXT,
                mime = "text/markdown",
                uri = ref,
                metadata = mapOf("source" to provider.label, "model" to provider.model),
            )
        }

    private fun requestBody(obj: PointObject, prompt: String): String {
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
            message.put("content", prompt)
        }
        return JSONObject()
            .put("model", provider.model)
            .put("messages", JSONArray().put(message))
            .toString()
    }

    private fun maybeImage(obj: PointObject): JSONObject? {
        if (!obj.mime.startsWith("image/")) return null
        val attachment = inlineAttachment(obj.uri.value, obj.mime) ?: return null
        return JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:${attachment.mime};base64,${attachment.base64}"))
    }

    private fun parseAnswer(json: String): String {
        val choices = JSONObject(json).optJSONArray("choices")
            ?: error("${provider.label}: ответ без choices")
        if (choices.length() == 0) error("${provider.label}: пустой ответ")
        val content = choices.getJSONObject(0).getJSONObject("message").optString("content")
        return content.ifBlank { error("${provider.label}: пустой текст") }
    }

    private fun refusal(code: Int): String = when (code) {
        401, 403 -> "${provider.label}: ${com.point.core.flow.KEY_NOT_ACCEPTED} — $AI_KEY_HINT в настройках"
        402 -> "${provider.label}: сервис просит оплату — у этого ключа нет бесплатного доступа"

        404 -> "${provider.label}: сервис не знает такой модели"
        429 -> "${provider.label}: $FREE_LIMIT_SPENT — вернитесь позже, платить не идём"
        in 500..599 -> "${provider.label}: сервис сейчас не отвечает"
        else -> "${provider.label}: сервис отказал"
    }

    private fun isImage(obj: PointObject): Boolean = obj.mime.startsWith("image/")

    private fun promptFor(obj: PointObject, prompt: String): String =
        if (isImage(obj)) "$prompt\n\n$NO_IMAGE_DIRECTIVE" else prompt

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val NO_IMAGE_MARKER = "NO_IMAGE"
        const val NO_IMAGE_DIRECTIVE =
            "Если изображение не приложено к запросу или ты его не видишь, ответь ровно одним словом без пояснений: NO_IMAGE"
    }
}
