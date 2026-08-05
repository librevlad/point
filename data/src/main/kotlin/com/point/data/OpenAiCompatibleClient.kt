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

/** Endpoint config for one OpenAI-compatible provider (OpenRouter, Groq, Cerebras, Mistral, OpenAI, …). */
data class OpenAiProvider(
    val label: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** Does this model accept image input? Keeps photos out of text-only models (#60). */
    val vision: Boolean = false,
    /** Замерена ли модель как читающая страницу дословно — см. [isMeasuredStrongVision]. */
    val strongVision: Boolean = false,
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
        .map {
            OpenAiProvider(
                label, baseUrl, apiKey, it,
                vision = isVisionModel(it),
                strongVision = isMeasuredStrongVision(it),
            )
        }

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
    "mistral-small", "mistral-medium", "ministral",
    // Ещё одно имя без намёка на картинку. `qwen3.6-27b` берёт 15/15 на мятом фото под углом
    // (перемер #490) — и без этой строки уехал бы в цепочку объявленным сильным читателем, к
    // которому снимок не попадает НИКОГДА: `canHandle` не пустил бы. Поймано тестом «сильное
    // зрение не заменяет зрения», а не глазами.
    "qwen3.6",
)

/**
 * Читает ли эта модель страницу **дословно** — по замеру, а не по названию (#490/#493).
 *
 * `strongVision` был объявлен ровно у трёх клиентов — Gemini, Claude и ключ человека, — то есть у
 * тех, кого когда-то признали сильными на глаз. Из-за этого бесплатная цепочка на снимке шла в
 * произвольном порядке, а лучшие читатели, найденные перемером, в неё не попадали вовсе: файл,
 * собранный из плохого чтения, и был жалобой владельца в #493.
 *
 * Список — из таблицы `docs/VISION-MODELS.md` (04.08.2026, 132 замера, три повтора на пару
 * «кандидат + картинка», счёт по 15 контрольным кускам на чистом скане и на мятом фото):
 * - `gemma-4-*` — 14–15/15 у трёх разных поставщиков (OpenRouter 6/6, SambaNova 6/6, Cerebras 5/6);
 * - `qwen3.6-*` — 15/15 на обеих картинках (Groq, OVH);
 * - `qwen2.5-vl` / `qwen2-vl` — 15/15 шесть попыток из шести (OVH).
 *
 * **Чего здесь намеренно нет.** Чаты Mistral (12–13/15) — их же собственная OCR-ручка берёт 15/15,
 * и объявить чат сильным значило бы поставить его вровень с тем, кто читает лучше. `glm-4.6v-flash`
 * (13/15, два ответа из шести) и `nemotron-*` (пустой ответ на плохом фото) — тем более.
 *
 * **Надёжность здесь не судится.** Groq берёт 15/15 и отвечает 2 раза из 6 (8000 токенов в минуту,
 * картинка ≈4400) — это про порядок в цепочке, а не про качество чтения: 429 переводит очередь
 * дальше сам. Смешать одно с другим значило бы понизить того, кто читает лучше всех, когда доходит.
 */
fun isMeasuredStrongVision(model: String): Boolean {
    val m = model.lowercase()
    return STRONG_VISION_MEASURED.any { it in m }
}

private val STRONG_VISION_MEASURED = listOf(
    "gemma-4", "gemma4", "qwen3.6", "qwen2.5-vl", "qwen2-vl",
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

    /**
     * Сильное зрение — свойство модели, а не поставщика, и берётся оно из замера ([isMeasuredStrongVision]).
     *
     * Отсюда цепочка на снимке ведёт с тех, кто прочитал ведомость дословно, а не с того, кто
     * оказался первым в списке ключей.
     */
    override val strongVision: Boolean = provider.strongVision

    override fun canHandle(obj: PointObject): Boolean = when {
        isImage(obj) -> provider.vision            // never send a photo to a text-only model
        obj.mime == "application/pdf" -> false     // OpenAI-compat has no PDF input → Gemini/Claude
        // Запись голоса (#223): в Chat Completions вложения звука нет. До правки этот клиент
        // отвечал «да» и получал ОДИН промпт без файла — модель уверенно рассказывала про
        // запись, которой не слышала. Это худший вид отказа: неотличимый от успеха.
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
            if (res.code !in 200..299) error(refusal(res.code))
            val answer = parseAnswer(res.body)
            // Deterministic contract, not phrase-guessing: an image request tells the model to
            // reply with exactly the marker if it can't see the image, so a text-only model that
            // slipped the routing is caught and the chain moves to a real vision model (#60).
            if (isImage(obj) && answer.trimStart().startsWith(NO_IMAGE_MARKER)) {
                error("${provider.label}: модель не увидела изображение")
            }
            val ref = store.newScratchFile("md")
            // Обращение к человеку («Вот вариант рецепта…») — не содержимое документа (#501).
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
            message.put("content", prompt) // plain string — maximal compatibility
        }
        return JSONObject()
            .put("model", provider.model)
            .put("messages", JSONArray().put(message))
            .toString()
    }

    /** Кадр и его предел — за [inlineAttachment] (общее место на все клиенты); data-URL несёт
     *  mime вложения, потому что ужатый кадр перекодирован. */
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

    /**
     * Отказ сервиса — словами человека: без кода ответа и без чужого JSON.
     *
     * Здесь стояло `"${'$'}{provider.label} HTTP ${'$'}{res.code}: ${'$'}{res.body.take(300)}"`, и в раздаваемой
     * сборке, где весь AI — это ключ человека с меткой «свой ключ», под объектом вырастало
     * `AI недоступен — свой ключ HTTP 429: {"error":{"message":"Rate limit reached for model …`.
     * Оно не отвечает ни на «что случилось», ни на «что теперь делать», зато показывает кусок
     * чужого ответа — а сервисы возвращают в теле ошибки и присланный запрос, то есть иногда ключ.
     * Тело поэтому не доходит до строки отказа вовсе: вычёркивать секрет из того, что уже собрано,
     * дороже, чем не собирать.
     *
     * **401/403 несут [AI_KEY_HINT] не для красоты.** По этой марке экран узнаёт отказ, который
     * человек может починить сам, и ставит рядом дорогу в настройки ([refusalNeedsKey]). Марка
     * появлялась, только когда ключа не было ВОВСЕ, — заданный, но неверный ключ оставлял человека
     * в тупике с JSON и без выхода.
     *
     * **Цена, названная вслух:** 403 назван «ключ не принят» вместе с 401, хотя у части сервисов
     * так же выглядит отказ пустить запрос вообще. Отправить чинить исправный ключ — цена меньшая,
     * чем не сказать, куда идти; исход проверяется живой проверкой ключа на самом экране.
     */
    private fun refusal(code: Int): String = when (code) {
        401, 403 -> "${provider.label}: ключ не принят — $AI_KEY_HINT в настройках"
        402 -> "${provider.label}: сервис просит оплату — у этого ключа нет бесплатного доступа"
        // Не из списка блокера, но из той же ямы: неверно набранное имя модели — вторая по частоте
        // ошибка настройки, а «сервис отказал» не ведёт никуда.
        404 -> "${provider.label}: сервис не знает такой модели"
        429 -> "${provider.label}: $FREE_LIMIT_SPENT — вернитесь позже, платить не идём"
        in 500..599 -> "${provider.label}: сервис сейчас не отвечает"
        else -> "${provider.label}: сервис отказал"
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
        const val NO_IMAGE_MARKER = "NO_IMAGE"
        const val NO_IMAGE_DIRECTIVE =
            "Если изображение не приложено к запросу или ты его не видишь, ответь ровно одним словом без пояснений: NO_IMAGE"
    }
}
