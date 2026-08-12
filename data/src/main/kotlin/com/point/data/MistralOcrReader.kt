package com.point.data

import com.point.core.flow.ReaderPrivacy
import com.point.core.flow.ReaderPromise
import com.point.core.model.PointObject
import org.json.JSONObject
import java.util.Base64
import com.point.core.flow.HttpJson

class MistralOcrReader(
    private val http: HttpJson,
    private val frames: OutboundFrames,
    private val apiKey: () -> String,
    private val baseUrl: String,

    private val facts: com.point.core.flow.AiFacts? = null,
) : CloudTextReader {

    override val reader = READER

    override val privacy = ReaderPrivacy(
        where = "Mistral, Франция (ЕС)",
        promise = ReaderPromise.TRAINS,
    )

    override val configured: Boolean get() = apiKey().isNotBlank()

    private val root: String get() = baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')

    override suspend fun read(obj: PointObject): String {
        val key = apiKey()
        require(key.isNotBlank()) { "$READER: ключ не задан" }
        val frame = frames.of(obj) ?: error("$READER: кадр не подготовлен — нечего отправлять")
        val body = JSONObject()
            .put("model", MODEL)
            .put(
                "document",
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", "data:${frame.mime};base64,${base64(frame.bytes)}"),
            )

            .put("include_image_base64", false)
            .toString()
        val res = runCatching { http.post("$root/ocr", mapOf("Authorization" to "Bearer $key"), body) }
            .onFailure { facts?.remember(com.point.core.flow.MISTRAL_PROVIDER_ID, com.point.core.flow.AiOutcome.SILENT) }
            .getOrThrow()

        // Исход чтения страницы — такой же настоящий факт о сервисе, как ответ
        // модели: экран ключей показывает последнее обращение, а не догадку (#699).
        facts?.remember(com.point.core.flow.MISTRAL_PROVIDER_ID, com.point.core.flow.aiOutcomeOfStatus(res.code))
        if (res.code !in 200..299) error(refusal(res.code))
        return textOf(res.body)
    }

    private fun textOf(json: String): String {

        val answer = runCatching { JSONObject(json) }.getOrElse {
            error("$READER: ответ не разобран — пробуем следующий")
        }
        val pages = answer.optJSONArray("pages") ?: error("$READER: в ответе нет страниц — пробуем следующий")
        return (0 until pages.length())
            .mapNotNull { pages.optJSONObject(it)?.optString("markdown")?.trim()?.ifEmpty { null } }
            .joinToString("\n\n")
    }

    private fun refusal(code: Int): String = when (code) {
        402 -> "$READER: бесплатный лимит исчерпан (402) — покупать не идём, пробуем следующий"
        429 -> "$READER: слишком часто (429) — пробуем следующий"
        401, 403 -> "$READER: ключ не принят ($code)"
        else -> "$READER: сервис отказал (код $code) — пробуем следующий"
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val READER = "mistral-ocr"

        const val MODEL = "mistral-ocr-latest"
        const val DEFAULT_BASE_URL = "https://api.mistral.ai/v1"
    }
}
