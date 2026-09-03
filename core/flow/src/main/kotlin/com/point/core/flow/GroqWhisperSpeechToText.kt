package com.point.core.flow

import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Whisper по ключу Groq — один клиент на телефон и компьютер (#1379).
 *
 * Жил в `:data`, хотя ничего от Android не брал: сеть за швом [HttpFiles], ответ — обычный
 * JSON. Компьютер тем временем держал собственный рукописный клиент того же сервиса внутри
 * своего исполнителя — два клиента одного сервиса расходились в словах отказа и в проверках
 * до сети. Теперь клиент один, а стороны подставляют в него свою сеть и свой ключ.
 *
 * Ключ спрашивается на каждый запрос: введённый минуту назад работает сразу.
 */
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
                FormPart.Binary("file", "voice.${audioExtensionFor(mime)}", mime, file.readBytes()),
                FormPart.Field("model", model),
                FormPart.Field("response_format", "json"),
            ),
        )
        if (res.code !in 200..299) error(refusal(res.code))
        heardOf(res.body)
    }

    private fun heardOf(body: String): Transcription {
        val text = runCatching { parseJson(body).str("text").orEmpty() }
            .getOrElse { error(UNREADABLE_ANSWER) }
            .trim()
        return if (text.isEmpty()) Transcription.Silence else Transcription.Heard(text)
    }

    // Не пустили по ключу — названы и сервисы, которые умеют расшифровку, и куда идти за
    // ключом: так говорил компьютер, а телефон — только про настройки. Слова общие (#1379).
    private fun refusal(code: Int): String =
        serviceRefusal(code, hint = if (code in KEY_CODES) "$WHO_TRANSCRIBES. $KEY_SETTINGS_CALL" else null)

    private companion object {
        /** Коды, на которые человеку есть что сделать: ключ. */
        val KEY_CODES = setOf(401, 403, 404)

        const val WHO_TRANSCRIBES = "расшифровку умеют Groq и OpenAI, но не OpenRouter"

        const val PATH = "/audio/transcriptions"

        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"

        val MAX_WHISPER_BYTES = MAX_SPEECH_BYTES
    }
}
