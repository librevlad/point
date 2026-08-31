package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.flow.modelReadableAudio
import com.point.core.flow.parseJson
import com.point.core.flow.str
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
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

    // Расшифровка — знание той же записи, а не новый объект (#1097): решение #1157 было
    // сделано только на телефоне, и компьютер продолжал рождать «Расшифровку» отдельной
    // вещью — а через связку этот узел приезжал на телефон вместо знания.
    override fun produces(state: ObjectState) = state.with(com.point.core.model.Feature.HAS_TEXT)

    // Обещание человеку — из общего словаря (#1254): ту же работу делает телефон, и до тапа
    // она обязана обещать одно и то же. Литерал стоял в обоих файлах, и сверял их никто.
    override fun yields(state: ObjectState) =
        com.point.core.model.ActionYield.Same(com.point.core.flow.SPEECH_PROMISE)

    override fun intents(state: ObjectState) = setOf(com.point.core.model.Intent.UNDERSTAND)
}

class PcTranscribeRealizer(
    private val config: () -> SpeechConfig,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 180_000,

    /** Поход к сервису — за швом: что он ответил, проверяется тестом без сети (как в OcrActions). */
    private val askOutside: ((SpeechConfig, File, String) -> String)? = null,

    /** Компьютер слушает запись сам — тем же правилом, что и телефон (#1053, #1312). */
    private val level: com.point.core.flow.AudioLevel = JvmAudioLevel(),
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

                // Слушаем сами — прежде сервиса (#1053, #1312). Правило одно на оба
                // устройства: на пустой записи движок не молчит, а выдумывает фразу, и
                // выдумка ложится знанием об объекте. Пустоту компьютер слышит сам и
                // бесплатно. Не измерили — не «тихо»: незнакомый формат идёт дальше обычным
                // путём, и расшифровку у человека не отнимает.
                if (com.point.core.flow.nothingToHear(runCatching { level.peak(input) }.getOrNull())) {
                    return@withContext ActionResult.Done(
                        com.point.core.flow.NO_SPEECH_HEARD,
                        com.point.core.model.Findings(
                            metadata = mapOf(
                                com.point.core.flow.investigationKey(capabilityId) to
                                    com.point.core.flow.InvestigationState.NOT_FOUND.wire,
                                com.point.core.flow.META_AUDIO_SILENT to "true",
                            ),
                        ),
                    )
                }

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
                val text = (askOutside ?: ::post)(cfg, file, mime)
                if (text.isBlank()) {

                    // «Не нашлось» — знание, а не сбой (Конституция §13). Сервис ответил на
                    // вопрос: речи в записи нет. Тот же исход, что у телефона (#1274), и теми
                    // же словами — иначе один ответ на двух устройствах звучал бы по-разному.
                    return@withContext ActionResult.Done(
                        com.point.core.flow.NO_SPEECH_HEARD,
                        com.point.core.model.Findings(
                            metadata = mapOf(
                                com.point.core.flow.investigationKey(capabilityId) to
                                    com.point.core.flow.InvestigationState.NOT_FOUND.wire,
                            ),
                        ),
                    )
                }
                // Расшифровка — знание той же записи, а не новый объект (#1097, GRF-006).
                // Решение #1157 доехало только до телефона: компьютер рождал «Расшифровку»
                // отдельной вещью, у самой записи не появлялось ни текста, ни закрытого
                // вопроса, а телефон через связку получал чужой узел вместо своего знания.
                // Ключи те же, что кладёт телефон, — иначе одно знание звалось бы двумя
                // именами и на той стороне читалось бы как неисследованное.
                val out = File.createTempFile("pc-voice-", ".txt").apply { writeText(text) }
                ActionResult.Done(
                    com.point.core.flow.SPEECH_IS_KNOWLEDGE,
                    com.point.core.model.Findings(
                        features = setOf(com.point.core.model.Feature.HAS_TEXT),
                        metadata = mapOf(
                            com.point.core.flow.META_OCR_TEXT_REF to out.absolutePath,
                            com.point.core.flow.investigationKey(capabilityId) to
                                com.point.core.flow.InvestigationState.FOUND.wire,
                        ),
                    ),
                )

                // Отказ, названный по существу, наружу как есть (#1255): «нужен ключ Groq, а не
                // OpenRouter» и «бесплатное на сегодня кончилось» накрывались общим «попробуйте
                // позже» — человек жал снова и снова там, где сегодня уже не заработает.
            }.getOrElse {
                ActionResult.Failure(
                    com.point.core.flow.ownWordsOf(it) ?: "Сервис расшифровки не ответил — попробуйте позже",
                    recoverable = true,
                )
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

            // Слово этого слоя объявлено им самим (#1225/#1255): `require` бросал обычное
            // исключение, и общий перехват выше подменял названную причину своим «не ответил».
            if (code !in 200..299) com.point.core.flow.ownWords(refusal(code))
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
