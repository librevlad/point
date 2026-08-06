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

/**
 * Расшифровка речи на компьютере (#585).
 *
 * Голосовое приходит с телефона — там его записали или переслали из мессенджера, — а слушать и
 * читать удобнее на большом экране. До этого компьютер с записью не умел ничего: открыть плеером и
 * всё.
 *
 * Ручка отдельная (`/audio/transcriptions`), а не «показать файл модели»: специальный движок
 * слушает лучше и стоит дешевле, и это то же решение, что на телефоне (#223). Ключ нужен от
 * сервиса, у которого такая ручка есть, — Groq или OpenAI; ключ OpenRouter здесь не работает, и
 * сказано об этом прямо, а не кодом 404.
 */
class PcTranscribeCapability : Capability {
    override val id = CapabilityId("pc-transcribe")
    override val icon = "voice"
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
    override val capabilityId = CapabilityId("pc-transcribe")

    /** Уходит к чужому сервису, и это сказано вслух: телефон спросит согласие ДО
     *  отправки — там, где человек, а не здесь (контракт, граница молчаливого выбора). */
    override val meta = com.point.core.flow.RealizerMeta(kind = com.point.core.flow.RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val cfg = config()
                if (cfg.key.isBlank()) {
                    return@withContext ActionResult.Failure(
                        "Расшифровке нужен ключ Groq или OpenAI — впишите его в ~/.point-pc/config " +
                            "строкой speech.key=…",
                        recoverable = false,
                    )
                }
                val file = File(input.uri.value).takeIf(File::isFile)
                    ?: return@withContext ActionResult.Failure("Файла записи нет на диске", recoverable = false)
                // Отказ ДО сети: формат, которого движок не читает, честнее назвать словами здесь,
                // чем получить 400 и показать человеку кусок чужого JSON. Список общий с телефоном.
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
                    // Дошли, послушали, речи не нашли — это не сбой, и говорить о нём надо иначе.
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

    /** Multipart руками: одна форма из двух полей — файла и имени модели. Библиотеки для этого не нужно. */
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
                // Имя файла синтетическое: сервис смотрит на расширение, а настоящее имя записи —
                // это имя из чужого мессенджера, и чужому сервису оно не нужно.
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

    private fun refusal(code: Int): String = when (code) {
        401, 403 -> "Ключ расшифровки не подошёл — нужен ключ Groq или OpenAI"
        404 -> "У этого сервиса нет ручки расшифровки — подойдут Groq или OpenAI, но не OpenRouter"
        413 -> "Запись слишком большая для этого сервиса"
        429 -> "Сервис просит подождать — слишком много запросов подряд"
        in 500..599 -> "Сервис расшифровки сейчас не отвечает"
        else -> "Сервис расшифровки отказал ($code)"
    }

    private fun extensionOf(mime: String): String = when {
        mime.contains("ogg") -> "ogg"
        mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
        mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
        mime.contains("wav") -> "wav"
        mime.contains("flac") -> "flac"
        else -> "audio"
    }

    private companion object {
        /** Столько принимает движок; больше он отрежет сам, но человеку это скажем заранее. */
        const val MAX_BYTES = 25L * 1024 * 1024
    }
}

/**
 * Чем компьютер слушает речь. Отдельно от [AiConfig]: ключ тут нужен от **другого** сервиса —
 * у OpenRouter, которым удобно спрашивать модели, ручки расшифровки нет вовсе.
 */
data class SpeechConfig(
    val key: String = "",
    val url: String = DEFAULT_URL,
    val model: String = DEFAULT_MODEL,
) {
    companion object {
        /** Groq: у него Whisper быстрый и с бесплатной квотой — та же цепочка, что на телефоне. */
        const val DEFAULT_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val DEFAULT_MODEL = "whisper-large-v3"
    }
}
