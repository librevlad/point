package com.point.data

import com.point.core.flow.ReaderPrivacy
import com.point.core.model.PointObject
import org.json.JSONObject
import java.util.Base64

/**
 * Mistral OCR — специальная ручка чтения страницы, а не зрячий чат (#280).
 *
 * **Почему он первый в очереди.** Замер 04.08.2026 (`docs/VISION-MODELS.md`) на ведомости с точно
 * известным содержимым: 24 строки из 24 **дословно** — на чистом скане, под углом, в тени от руки и
 * при плохом свете; 1,3–5 с на страницу. На настоящих кадрах владельца, где телефонный движок не
 * дал текста вовсе (водомер, две накладные), он прочитал всё. Это не «лучше в среднем», это
 * единственный измеренный способ прочитать печатную кириллицу с фотографии.
 *
 * **Контракт сверен с рабочим замером** (`tools/vision/run.py`, `providers.json`), а не с памятью:
 * `POST {base}/ocr`, заголовок `Authorization: Bearer …`, тело
 * ```json
 * {"model":"mistral-ocr-latest",
 *  "document":{"type":"image_url","image_url":"data:image/jpeg;base64,…"}}
 * ```
 * ответ — `{"pages":[{"index":0,"markdown":"…"}]}`, текст страницы лежит в `markdown`.
 *
 * **Промпта здесь нет и быть не может.** Ручка не отвечает на вопрос, она разбирает страницу.
 * Отсюда же и отдельный контракт [CloudTextReader]: попади такой читатель в общую цепочку моделей,
 * «Понять» и «Перевести» на снимке молча возвращали бы расшифровку вместо ответа.
 *
 * **Модель — серверный алиас** `mistral-ocr-latest`, а не пин версии: то же правило, что у Gemini
 * (`GEMINI_MODELS`) — пин переживает ровно до следующего отключения модели.
 *
 * Ключ и адрес инжектируются (из `BuildConfig`, в `DataModule`), а не читаются здесь: сборка
 * запроса и разбор ответа обязаны проверяться подделками независимо от того, что лежит в
 * `local.properties`.
 */
class MistralOcrReader(
    private val http: HttpJson,
    private val frames: OutboundFrames,
    private val apiKey: String,
    private val baseUrl: String,
) : CloudTextReader {

    override val reader = READER

    /**
     * Франция, ЕС. Ключ бесплатного тарифа, запрос не идёт на обучение — поэтому [ReaderPrivacy.europe]
     * поднят, и на уровне «только Европа» этот глаз остаётся единственным работающим.
     */
    override val privacy = ReaderPrivacy(where = "Mistral, Франция (ЕС)", europe = true, logsRequests = false)

    override val configured: Boolean get() = apiKey.isNotBlank()

    private val root: String get() = baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')

    override suspend fun read(obj: PointObject): String {
        require(configured) { "$READER: ключ не задан" }
        val frame = frames.of(obj) ?: error("$READER: кадр не подготовлен — нечего отправлять")
        val body = JSONObject()
            .put("model", MODEL)
            .put(
                "document",
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", "data:${frame.mime};base64,${base64(frame.bytes)}"),
            )
            // Картинки страницы обратно не нужны — нам нужен её текст, а не её вес.
            .put("include_image_base64", false)
            .toString()
        val res = http.post("$root/ocr", mapOf("Authorization" to "Bearer $apiKey"), body)
        if (res.code !in 200..299) error(refusal(res.code, res.body))
        return textOf(res.body)
    }

    /**
     * Страницы → текст.
     *
     * Страницы склеиваются по порядку, как в замере: разбор по страницам — устройство ответа, а не
     * смысл документа, и человеку нужен документ.
     */
    private fun textOf(json: String): String {
        val answer = runCatching { JSONObject(json) }.getOrElse {
            error("$READER: ответ не разобран — ${json.take(200)}")
        }
        val pages = answer.optJSONArray("pages") ?: error("$READER: в ответе нет страниц — ${json.take(200)}")
        return (0 until pages.length())
            .mapNotNull { pages.optJSONObject(it)?.optString("markdown")?.trim()?.ifEmpty { null } }
            .joinToString("\n\n")
    }

    /** Отказ человеческими словами: 402/429 — «бесплатное кончилось», а не «сломалось». */
    private fun refusal(code: Int, body: String): String = when (code) {
        402 -> "$READER: бесплатный лимит исчерпан (402) — покупать не идём, пробуем следующий"
        429 -> "$READER: слишком часто (429) — пробуем следующий"
        401, 403 -> "$READER: ключ не принят ($code)"
        else -> "$READER HTTP $code: ${body.take(300)}"
    }

    /**
     * `java.util.Base64`, а не `android.util.Base64`: тот же результат, но читатель остаётся
     * проверяемым на JVM без единого пикселя (minSdk 26 — API есть везде, где есть Point).
     */
    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val READER = "mistral-ocr"

        /** Серверный алиас: переживает отключение конкретной версии, пин — нет. */
        const val MODEL = "mistral-ocr-latest"
        const val DEFAULT_BASE_URL = "https://api.mistral.ai/v1"
    }
}
