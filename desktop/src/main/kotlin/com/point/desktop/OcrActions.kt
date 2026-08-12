package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.JsonValue
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.flow.array
import com.point.core.flow.bool
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
import java.net.URLEncoder
import java.util.Base64

class PcCloudOcrRealizer(
    private val config: () -> OcrConfig,
    private val extractor: com.point.core.flow.EntityExtractor = com.point.core.flow.RegexEntityExtractor(),
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,

    /** Поход к сервису — за швом: то, что уходит наружу, проверяется тестом без сети (#592). */
    private val readOutside: ((OcrConfig, File, String) -> String)? = null,
) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.OcrCapability.ID

    override val meta = com.point.core.flow.RealizerMeta(kind = com.point.core.flow.RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(input.uri.value).takeIf(File::isFile)
                    ?: return@withContext ActionResult.Failure("Файла картинки нет на диске", recoverable = false)

                // Снимок тяжелее предела сервиса Point укладывает сам (#592): прежде он отказывал
                // и советовал сначала нажать «Сделать легче» — два тапа там, где человек хотел
                // один. Второго объекта на конвейере не появляется: копия живёт ровно один поход.
                val fitted = if (file.length() > MAX_BYTES) ImageFit.toFit(file, MAX_BYTES) else null
                if (file.length() > MAX_BYTES && fitted == null) {
                    return@withContext ActionResult.Failure(
                        "Снимок " + String.format(java.util.Locale.ROOT, "%.1f", file.length() / (1024.0 * 1024)) +
                            " МБ — сервис принимает до 1 МБ, а уменьшить этот снимок не вышло.",
                        recoverable = false,
                    )
                }
                val cfg = config()
                val toRead = fitted?.file ?: file
                val text = (readOutside ?: ::read)(cfg, toRead, if (fitted != null) "image/jpeg" else input.mime)
                if (text.isBlank()) {

                    // «Не нашлось» — знание, а не сбой (Конституция §13).
                    return@withContext ActionResult.Done(
                        "На снимке не нашлось текста" + if (fitted == null) "" else " · " + shrunkNote(fitted),
                        com.point.core.model.Findings(
                            metadata = mapOf(
                                com.point.core.flow.investigationKey(capabilityId) to
                                    com.point.core.flow.InvestigationState.NOT_FOUND.wire,
                            ),
                        ),
                    )
                }

                // Прочитанное — знание об этом же снимке (Конституция §4): текст остаётся
                // слоем исходника, сущности — его фактами; нового объекта не рождается.
                val ref = File.createTempFile("pc-ocr-", ".txt").apply { writeText(text) }
                val found = com.point.core.flow.plausibleEntities(extractor.extract(text), text)
                val entities = entityKnowledge(found, com.point.core.flow.KnownCapabilities.ENTITIES)
                ActionResult.Done(
                    "Прочитал снимок" +
                        (if (fitted == null) "" else " · " + shrunkNote(fitted)) +
                        if (found.isEmpty()) "" else ". Нашёл: " + entitySummary(found),
                    com.point.core.model.Findings(
                        features = entities.features + com.point.core.model.Feature.HAS_TEXT,
                        metadata = entities.metadata + mapOf(
                            com.point.core.flow.META_OCR_TEXT_REF to ref.absolutePath,
                            com.point.core.flow.investigationKey(capabilityId) to
                                com.point.core.flow.InvestigationState.FOUND.wire,
                        ),
                    ),
                )
            }.getOrElse {
                ActionResult.Failure("Сервис чтения не ответил — попробуйте позже", recoverable = true)
            }
        }

    private fun read(cfg: OcrConfig, file: File, mime: String): String {
        val key = cfg.key.ifBlank { DEMO_KEY }
        val body = form(
            "apikey" to key,
            "OCREngine" to "2",
            "language" to "rus",
            "isTable" to "true",
            "base64Image" to "data:$mime;base64," + Base64.getEncoder().encodeToString(file.readBytes()),
        )
        val connection = (URL(cfg.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val reply = try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            val safe = if (key.isBlank()) raw else raw.replace(key, "…")
            require(code in 200..299) { refusal(code) }
            safe
        } finally {
            connection.disconnect()
        }
        return textOf(reply)
    }

    private fun textOf(json: String): String {
        val answer = parseJson(json)
        if (answer.bool("IsErroredOnProcessing") == true) error("Сервис не прочитал снимок")
        val pages = answer.array("ParsedResults")
        require(pages.isNotEmpty()) { "Сервис вернул ответ без страниц" }
        return pages.mapNotNull { page ->
            (page as? JsonValue.Obj)?.let { it.str("ParsedText") }?.trim()?.ifEmpty { null }
        }.joinToString("\n\n")
    }

    private fun refusal(code: Int): String = when (code) {
        401, 403 -> "Ключ чтения не подошёл — проверьте ocr.key в ~/.point-pc/config"
        404 -> "Сервис чтения не отвечает по этому адресу — проверьте ocr.url"
        429 -> "Бесплатная квота сервиса на сегодня кончилась — попробуйте позже"
        in 500..599 -> "Сервис чтения сейчас не отвечает"
        else -> "Сервис чтения отказал ($code)"
    }

    private fun form(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }

    private companion object {

        const val DEMO_KEY = "helloworld"

        const val MAX_BYTES = 1024L * 1024
    }
}

data class OcrConfig(
    val key: String = "",
    val url: String = DEFAULT_URL,
) {
    companion object {
        const val DEFAULT_URL = "https://api.ocr.space/parse/image"
    }
}
