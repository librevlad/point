package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.flow.modelReadableAudio
import com.point.core.flow.parseJson
import com.point.core.flow.str
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Тот же идентификатор, что у расшифровки на телефоне (`:executors`, TranscribeCapability). */
private val TRANSCRIBE = com.point.core.flow.KnownCapabilities.TRANSCRIBE

class PcTranscribeCapability : Capability {

    // Не своё умение компьютера, а общая способность: расшифровка одинакова, где бы её ни
    // сделали, — телефон узнаёт её по тому же id и ставит одной строкой, а компьютер
    // становится вторым исполнителем (#628).
    override val id = TRANSCRIBE
    override val icon = "transcribe"
    override val meta = CapabilityMeta(priority = 21, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "Расшифровать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.AUDIO
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
}

class PcTranscribeRealizer(
    private val config: () -> SpeechConfig,
    private val outbox: Outbox,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 180_000,
) : Realizer {
    override val capabilityId = TRANSCRIBE

    override val meta = com.point.core.flow.RealizerMeta(kind = com.point.core.flow.RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val cfg = config()
                if (cfg.key.isBlank()) {
                    return@withContext ActionResult.Failure(
                        "Расшифровке нужен ключ " + com.point.core.flow.speechProviderNames() +
                            " — впишите его в «Ключи AI», там же, где остальные",
                        recoverable = false,
                    )
                }
                val file = File(input.uri.value).takeIf(File::isFile)
                    ?: return@withContext ActionResult.Failure("Файла записи нет на диске", recoverable = false)

                val mime = modelReadableAudio(input.mime, input.metadata["name"])
                    ?: return@withContext ActionResult.Failure(
                        "Этот формат записи движок не читает — подойдут ogg, mp3, m4a, wav, flac",
                        recoverable = false,
                    )
                if (file.length() > MAX_BYTES) {
                    return@withContext ActionResult.Failure(
                        "Запись " + file.length() / (1024 * 1024) + " МБ — движок принимает до 25 МБ",
                        recoverable = false,
                    )
                }
                val text = post(cfg, file, mime)
                if (text.isBlank()) {

                    return@withContext ActionResult.Failure("В записи не разобрано ни слова", recoverable = false)
                }
                val out = File.createTempFile("pc-voice-", ".txt").apply { writeText(text) }
                ActionResult.Success(
                    com.point.core.model.ResultObject(
                        type = ObjectKind.TEXT,
                        mime = "text/plain",
                        uri = ScratchRef(out.absolutePath),
                        metadata = mapOf("name" to "Расшифровка"),
                    ),
                )
            }.getOrElse {
                ActionResult.Failure("Сервис расшифровки не ответил — попробуйте позже", recoverable = true)
            }
        }

    private fun post(cfg: SpeechConfig, file: File, mime: String): String {
        val boundary = "----point" + System.nanoTime()
        val connection = (URL(cfg.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer " + cfg.key)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            connection.outputStream.buffered().use { out ->
                fun line(s: String) = out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
                line("--$boundary")

                line("""Content-Disposition: form-data; name="file"; filename="voice.${extensionOf(mime)}"""")
                line("Content-Type: $mime")
                line("")
                file.inputStream().use { it.copyTo(out) }
                line("")
                line("--$boundary")
                line("""Content-Disposition: form-data; name="model"""")
                line("")
                line(cfg.model)
                line("--$boundary")
                line("""Content-Disposition: form-data; name="response_format"""")
                line("")
                line("json")
                line("--$boundary--")
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(code in 200..299) { refusal(code) }
            parseJson(body).str("text").orEmpty().trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun refusal(code: Int): String = com.point.core.flow.serviceRefusal(
        code,
        hint = when (code) {
            401, 403, 404 -> "расшифровку умеют Groq и OpenAI, но не OpenRouter"
            else -> null
        },
    )

    private fun extensionOf(mime: String): String = com.point.core.flow.audioExtensionFor(mime)

    private companion object {

        val MAX_BYTES = com.point.core.flow.MAX_SPEECH_BYTES
    }
}

data class SpeechConfig(
    val key: String = "",
    val url: String = DEFAULT_URL,
    val model: String = DEFAULT_MODEL,
) {
    companion object {

        const val DEFAULT_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val DEFAULT_MODEL = "whisper-large-v3"
    }
}
