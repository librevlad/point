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

/**
 * Чтение текста с картинки на компьютере (#585).
 *
 * Tesseract — Android-библиотека, и на ПК её нет: там, где телефон читает снимок сам и бесплатно,
 * компьютер не умел ничего. При этом снимок экрана он теперь делает сам — и не мог прочитать даже
 * то, что сам же и снял.
 *
 * Читает чужой сервис, и человек об этом знает: строка «снимок уйдёт в сервис» стоит прямо под
 * действием, как и на телефоне. Работает без регистрации — у OCR.space есть демо-ключ; свой ключ
 * лишь поднимает потолок и вписывается в `~/.point-pc/config` строкой `ocr.key=…`.
 *
 * Локального чтения на ПК по-прежнему нет, и делать вид, что есть, Point не станет: действие
 * названо «Прочитать в облаке», а не «Распознать текст».
 */
class PcCloudOcrCapability : Capability {
    override val id = CapabilityId("pc-ocr")
    override val icon = "cloud"
    override val meta = CapabilityMeta(priority = 20, latency = Latency.SLOW, network = true)
    override fun label(state: ObjectState) = "Прочитать в облаке"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
}

class PcCloudOcrRealizer(
    private val config: () -> OcrConfig,
    private val outbox: Outbox,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,
) : Realizer {
    override val capabilityId = CapabilityId("pc-ocr")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(input.uri.value).takeIf(File::isFile)
                    ?: return@withContext ActionResult.Failure("Файла картинки нет на диске", recoverable = false)
                if (file.length() > MAX_BYTES) {
                    return@withContext ActionResult.Failure(
                        "Снимок " + file.length() / (1024 * 1024) + " МБ — сервис принимает до 1 МБ. " +
                            "Сначала «Сделать легче».",
                        recoverable = false,
                    )
                }
                val cfg = config()
                val text = read(cfg, file, input.mime)
                if (text.isBlank()) {
                    return@withContext ActionResult.Failure("На снимке не нашлось текста", recoverable = false)
                }
                val out = File.createTempFile("pc-ocr-", ".txt").apply { writeText(text) }
                ActionResult.Success(
                    com.point.core.model.ResultObject(
                        type = ObjectKind.TEXT,
                        mime = "text/plain",
                        uri = ScratchRef(out.absolutePath),
                        metadata = mapOf("name" to "Текст со снимка"),
                    ),
                )
            }.getOrElse {
                ActionResult.Failure(it.message ?: "Прочитать не вышло", recoverable = true)
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
            // Ключ вычёркивается из ВСЕГО, что вернул сервис: текст отказа доходит до человека, и
            // заботиться о нашем секрете сервис не обязан.
            val safe = if (key.isBlank()) raw else raw.replace(key, "…")
            require(code in 200..299) { refusal(code) }
            safe
        } finally {
            connection.disconnect()
        }
        return textOf(reply)
    }

    /**
     * Ответ → текст страницы.
     *
     * Сервис умеет отказать с кодом 200 (`IsErroredOnProcessing`), и пропустить это значит выдать
     * человеку пустую страницу вместо «не прочитал».
     */
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
        /** Демо-ключ из примеров самого сервиса: читатель работает сразу, без регистрации. */
        const val DEMO_KEY = "helloworld"

        /** Столько принимает бесплатный уровень; больше — сначала «Сделать легче». */
        const val MAX_BYTES = 1024L * 1024
    }
}

/**
 * Чем компьютер читает снимки. Ключ необязателен: без него работает демо-уровень сервиса.
 */
data class OcrConfig(
    val key: String = "",
    val url: String = DEFAULT_URL,
) {
    companion object {
        const val DEFAULT_URL = "https://api.ocr.space/parse/image"
    }
}
