package com.point.data

import com.point.core.flow.ReaderPrivacy
import com.point.core.flow.ReaderPromise
import com.point.core.flow.withoutKey
import com.point.core.model.PointObject
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

class OcrSpaceReader(
    private val http: HttpJson,
    private val frames: OutboundFrames,
    private val apiKey: () -> String,
    private val baseUrl: String,
) : CloudTextReader {

    override val reader = READER

    override val privacy = ReaderPrivacy(
        where = "OCR.space (a9t9 software), Германия (ЕС)",
        promise = ReaderPromise.UNKNOWN,
    )

    override val configured = true

    private val root: String get() = baseUrl.ifBlank { DEFAULT_URL }.trim()

    override suspend fun read(obj: PointObject): String {
        val key = apiKey().ifBlank { DEMO_KEY }
        val frame = frames.of(obj) ?: error("$READER: кадр не подготовлен — нечего отправлять")
        val form = form(
            "apikey" to key,
            "OCREngine" to ENGINE,
            "language" to LANGUAGE,
            "isTable" to "true",
            "base64Image" to "data:${frame.mime};base64,${base64(frame.bytes)}",
        )

        val res = http.post(root, mapOf("Content-Type" to FORM_TYPE), form)

        val body = withoutKey(res.body, key)
        if (res.code !in 200..299) error(refusal(res.code))
        return textOf(body)
    }

    private fun textOf(json: String): String {

        val answer = runCatching { JSONObject(json) }.getOrElse {
            error("$READER: ответ не разобран — пробуем следующий")
        }
        if (answer.optBoolean("IsErroredOnProcessing")) error("$READER: ${errorMessage(answer)}")
        val results = answer.optJSONArray("ParsedResults")
            ?: error("$READER: в ответе нет страниц — ${errorMessage(answer)}")
        return (0 until results.length())
            .mapNotNull { results.optJSONObject(it)?.optString("ParsedText")?.trim()?.ifEmpty { null } }
            .joinToString("\n\n")
    }

    private fun errorMessage(answer: JSONObject): String {
        val message = when (val raw = answer.opt("ErrorMessage")) {
            is JSONArray -> (0 until raw.length()).joinToString("; ") { raw.optString(it) }
            is String -> raw
            else -> ""
        }.trim()
        return message.ifBlank { "чтение не удалось" }.take(300)
    }

    private fun refusal(code: Int): String = when (code) {
        402 -> "$READER: бесплатный лимит исчерпан (402) — покупать не идём, пробуем следующий"

        429 -> "$READER: слишком часто (429) — пробуем следующий"
        401, 403 -> "$READER: ключ не принят ($code)"
        else -> "$READER: сервис отказал (код $code) — пробуем следующий"
    }

    private fun form(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (name, value) -> "$name=${encode(value)}" }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val READER = "ocr-space"
        const val DEFAULT_URL = "https://api.ocr.space/parse/image"
        const val FORM_TYPE = "application/x-www-form-urlencoded; charset=utf-8"

        const val DEMO_KEY = "helloworld"

        const val ENGINE = "3"
        const val LANGUAGE = "rus"
    }
}
