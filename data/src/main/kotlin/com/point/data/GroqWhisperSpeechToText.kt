package com.point.data

import com.point.core.flow.FirstHeardSpeechToText
import com.point.core.flow.GROQ_PROVIDER_ID
import com.point.core.flow.KEY_SETTINGS_CALL
import com.point.core.flow.SpeechKeyNeed
import com.point.core.flow.SpeechToText
import com.point.core.flow.Transcription
import com.point.core.flow.modelReadableAudio
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import com.point.core.flow.HttpFiles
import com.point.core.flow.FormPart

class GroqWhisperSpeechToText(
    private val http: HttpFiles,
    private val apiKey: () -> String,
    private val baseUrl: String,
    private val model: String,
) : SpeechToText {

    val configured: Boolean get() = apiKey().isNotBlank()

    override fun missingKey(): SpeechKeyNeed? =
        if (configured) null else SpeechKeyNeed("Whisper слушает по ключу Groq", GROQ_PROVIDER_ID)

    override suspend fun transcribe(obj: PointObject): Transcription = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isBlank()) error("Whisper не настроен — нужен ключ Groq. $KEY_SETTINGS_CALL")

        val mime = modelReadableAudio(obj.mime, obj.metadata["name"])
            ?: error("Этот формат записи не читается — подойдут ogg, mp3, m4a, wav, flac")

        val file = File(obj.uri.value)
        val bytes = file.length()
        if (bytes > MAX_WHISPER_BYTES) {
            error("Запись слишком большая (${bytes / (1024 * 1024)} МБ) — Whisper принимает до 25 МБ")
        }

        val res = http.postMultipart(
            url = baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/') + PATH,

            headers = mapOf("Authorization" to "Bearer $key"),
            parts = listOf(

                FormPart.Binary("file", "voice.${extensionOf(mime)}", mime, file.readBytes()),
                FormPart.Field("model", model),
                FormPart.Field("response_format", "json"),

            ),
        )
        if (res.code !in 200..299) error(refusal(res.code))
        heardOf(res.body)
    }

    private fun heardOf(body: String): Transcription {

        val text = runCatching { JSONObject(body).optString("text") }
            .getOrElse { error("Whisper - ответ не разобран, пробуем следующий движок") }
            .trim()
        return if (text.isEmpty()) Transcription.Silence else Transcription.Heard(text)
    }

    private fun refusal(code: Int): String = when (code) {
        401 -> "Whisper - ключ Groq не принят (401)"

        403 -> "Whisper - Groq не пустил запрос (403): ключ не принят или отказал сам сервис"
        413 -> "Whisper - запись слишком большая для бесплатного лимита"
        429 -> "Whisper - слишком часто (429), пробуем следующий движок"
        else -> "Whisper - сервис отказал (код $code), пробуем следующий движок"
    }

    private fun extensionOf(mime: String): String = EXTENSIONS[mime] ?: "ogg"

    private companion object {
        const val PATH = "/audio/transcriptions"

        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"

        const val MAX_WHISPER_BYTES = 25L * 1024 * 1024

        val EXTENSIONS: Map<String, String> = mapOf(
            "audio/ogg" to "ogg",
            "audio/mpeg" to "mp3",
            "audio/wav" to "wav",
            "audio/flac" to "flac",
            "audio/mp4" to "m4a",
            "audio/aac" to "aac",

            "audio/aiff" to "aiff",
        )
    }
}
