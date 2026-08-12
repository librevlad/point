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
            .getOrElse { error(com.point.core.flow.UNREADABLE_ANSWER) }
            .trim()
        return if (text.isEmpty()) Transcription.Silence else Transcription.Heard(text)
    }

    private fun refusal(code: Int): String =
        com.point.core.flow.serviceRefusal(code, hint = if (code in KEY_CODES) KEY_SETTINGS_CALL else null)

    private fun extensionOf(mime: String): String = com.point.core.flow.audioExtensionFor(mime)

    private companion object {

        /** Коды, на которые человеку есть что сделать: ключ. */
        val KEY_CODES = setOf(401, 403)

        const val PATH = "/audio/transcriptions"

        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"

        val MAX_WHISPER_BYTES = com.point.core.flow.MAX_SPEECH_BYTES
    }
}
