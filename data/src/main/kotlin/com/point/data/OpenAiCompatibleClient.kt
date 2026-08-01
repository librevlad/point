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

/** Endpoint config for one OpenAI-compatible provider (OpenRouter, Groq, Cerebras, Mistral, OpenAI, …). */
data class OpenAiProvider(
    val label: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** Does this model accept image input? Keeps photos out of text-only models (#60). */
    val vision: Boolean = false,
)

/** The active providers, order preserved — a blank key means "not signed up", so skip it. */
fun List<OpenAiProvider>.configured(): List<OpenAiProvider> = filter { it.apiKey.isNotBlank() }

/**
 * One [OpenAiProvider] per model in the comma-separated [models] — same endpoint and
 * key. So a single key (e.g. OpenRouter) chains several free models as fallbacks:
 * if one is rate-limited or down, the next model on the same key is tried. Each model's
 * vision capability is inferred from its name so image objects skip text-only ones (#60).
 */
fun openAiModels(label: String, baseUrl: String, apiKey: String, models: String): List<OpenAiProvider> =
    models.split(',').map(String::trim).filter(String::isNotBlank)
        .map { OpenAiProvider(label, baseUrl, apiKey, it, vision = isVisionModel(it)) }

/**
 * Best-effort: does a model name denote a vision (image-in) model? Deliberately conservative —
 * it only keeps photos out of *obviously* text-only models. A misclassified text model is still
 * caught by the "no image received" refusal check, and the native vision providers (Gemini,
 * Claude) backstop the chain (#60).
 */
fun isVisionModel(model: String): Boolean {
    val m = model.lowercase()
    return VISION_MODEL_HINTS.any { it in m }
}

private val VISION_MODEL_HINTS = listOf(
    "gemma-3", "gemma-4", "gemma3", "gemma4", "pixtral", "llava", "-vl", "vl-",
    "gpt-4o", "gpt-4.1", "vision", "llama-3.2", "llama-4", "internvl", "minicpm-v",
    "qwen2-vl", "qwen2.5-vl", "molmo", "phi-3.5-vision", "phi-4-multimodal",
    // Названия без всякого намёка на картинку: mistral-small/medium зрячие с 2025 года.
    // Замер на ведомости (02.08.2026): medium прочитал 30 строк и все 27 артикулов, а
    // Point его пропускал — угадывание по имени промахивается в обе стороны.
    "mistral-small", "mistral-medium",
)

/**
 * Any provider speaking the OpenAI Chat Completions API — OpenRouter, Groq,
 * Cerebras, Mistral, OpenAI, or a local server — behind one [OpenAiProvider]
 * config (base URL, key, model). The network lives behind [HttpJson], so request
 * building and response parsing are unit-testable with a fake. Small images are
 * attached as a data-URL; the answer is materialised to `.md`.
 */
class OpenAiCompatibleClient(
    private val http: HttpJson,
    private val store: ObjectStore,
    private val provider: OpenAiProvider,
) : LlmClient {

    private val baseUrl: String = provider.baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')

    override fun canHandle(obj: PointObject): Boolean = when {
        isImage(obj) -> provider.vision            // never send a photo to a text-only model
        obj.mime == "application/pdf" -> false     // OpenAI-compat has no PDF input → Gemini/Claude
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
            if (res.code !in 200..299) error("${provider.label} HTTP ${res.code}: ${res.body.take(300)}")
            val answer = parseAnswer(res.body)
            // Deterministic contract, not phrase-guessing: an image request tells the model to
            // reply with exactly the marker if it can't see the image, so a text-only model that
            // slipped the routing is caught and the chain moves to a real vision model (#60).
            if (isImage(obj) && answer.trimStart().startsWith(NO_IMAGE_MARKER)) {
                error("${provider.label}: модель не увидела изображение")
            }
            val ref = store.newScratchFile("md")
            File(ref.value).writeText(answer)
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
            message.put("content", prompt) // plain string — maximal compatibility
        }
        return JSONObject()
            .put("model", provider.model)
            .put("messages", JSONArray().put(message))
            .toString()
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

    private fun isImage(obj: PointObject): Boolean = obj.mime.startsWith("image/")

    /**
     * Bake a strict escape-hatch into an image prompt: if the model can't see the image it must
     * reply with exactly the marker. That turns "did the model actually get the picture?" into a
     * deterministic check instead of matching free-text refusals. Non-image prompts are untouched.
     */
    private fun promptFor(obj: PointObject, prompt: String): String =
        if (isImage(obj)) "$prompt\n\n$NO_IMAGE_DIRECTIVE" else prompt

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val MAX_INLINE_BYTES = 15L * 1024 * 1024
        const val NO_IMAGE_MARKER = "NO_IMAGE"
        const val NO_IMAGE_DIRECTIVE =
            "Если изображение не приложено к запросу или ты его не видишь, ответь ровно одним словом без пояснений: NO_IMAGE"
    }
}
