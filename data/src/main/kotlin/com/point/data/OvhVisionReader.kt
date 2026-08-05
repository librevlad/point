package com.point.data

import com.point.core.flow.ReaderPrivacy
import com.point.core.flow.ReaderPromise
import com.point.core.model.PointObject
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * OVH — зрячая модель, которую отдают **бесплатно и без регистрации** (#280).
 *
 * Замер (04.08.2026, `docs/VISION-MODELS.md`, перемер #490): 15 строк из 15 на кириллице **шесть
 * попыток из шести** — и на чистом скане, и на мятом фото под углом; Qwen2.5-VL-72B, сервер во
 * Франции, два запроса в минуту. Третий по счёту (после Mistral OCR и OCR.space) — медленнее их
 * обоих, но единственный, кто работает у любого человека сразу, и единственный, кто остаётся на
 * строгом уровне «Не учатся на моём».
 *
 * **Ключ необязателен, и это его смысл.** Endpoint отдаёт модель анонимно; ключ, если человек его
 * завёл, поднимает лимиты. Поэтому [configured] здесь `true` всегда: читатель без ключа — не
 * «ненастроенный», он просто медленный. Не ответит — цепочка идёт дальше, и человек это увидит.
 *
 * В отличие от Mistral OCR это обычный OpenAI-совместимый чат, поэтому дословность приходится
 * просить словами. Промпт — тот же, что у облачного чтения снимка в `:executors`, и держать его
 * ровно один нельзя (модуль ниже по стрелке зависимостей), поэтому он повторён здесь дословно с
 * этой пометкой.
 */
class OvhVisionReader(
    private val http: HttpJson,
    private val frames: OutboundFrames,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
) : CloudTextReader {

    override val reader = READER

    /**
     * Единственный в цепочке, кто обещает обе вещи разом: «Your data will never be used to train or
     * improve our AI models» и нулевое хранение. Поэтому на строгом уровне («Не учатся на моём») он
     * остаётся работающим — и работает там без всякого ключа.
     */
    override val privacy = ReaderPrivacy(
        where = "OVH, Франция (ЕС)",
        promise = ReaderPromise.NO_TRAINING,
    )

    /** Анонимный доступ — читатель есть всегда; ключ лишь поднимает лимиты. */
    override val configured = true

    private val root: String get() = baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')

    override suspend fun read(obj: PointObject): String {
        val frame = frames.of(obj) ?: error("$READER: кадр не подготовлен — нечего отправлять")
        val image = JSONObject()
            .put("type", "image_url")
            .put(
                "image_url",
                JSONObject().put("url", "data:${frame.mime};base64,${base64(frame.bytes)}"),
            )
        val body = JSONObject()
            .put("model", model.ifBlank { DEFAULT_MODEL })
            // Дословность — это температура ноль: «творческий» пересказ документа человеку вреден.
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", PROMPT))
                                .put(image),
                        ),
                ),
            )
            .toString()
        // Ключа нет — заголовка нет: пустой Bearer сервисы читают как «плохой ключ», а не как «без ключа».
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
        val res = http.post("$root/chat/completions", headers, body)
        if (res.code !in 200..299) error(refusal(res.code))
        return textOf(res.body)
    }

    private fun textOf(json: String): String {
        // #541: обрезанный по символам ответ сервиса из отказа убран — он доезжал до экрана.
        val answer = runCatching { JSONObject(json) }.getOrElse {
            error("$READER: ответ не разобран — пробуем следующий")
        }
        return answer.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
    }

    /** Тело ответа в отказ не идёт (#541): человеку оно ничего не объясняет, а на экран доезжает. */
    private fun refusal(code: Int): String = when (code) {
        402 -> "$READER: бесплатный лимит исчерпан (402) — покупать не идём, пробуем следующий"
        429 -> "$READER: слишком часто (429) — пробуем следующий"
        401, 403 -> "$READER: доступ не дан ($code)"
        else -> "$READER: сервис отказал (код $code) — пробуем следующий"
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val READER = "ovh-qwen-vl"
        const val DEFAULT_BASE_URL = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1"
        const val DEFAULT_MODEL = "Qwen2.5-VL-72B-Instruct"

        /** Дословно повторяет `OCR_CLOUD_PROMPT` из `:executors` — см. примечание в шапке класса. */
        const val PROMPT =
            "Извлеки весь текст с изображения дословно, сохраняя порядок строк. " +
                "Таблицы оформи в Markdown. Верни только текст, без комментариев."
    }
}
