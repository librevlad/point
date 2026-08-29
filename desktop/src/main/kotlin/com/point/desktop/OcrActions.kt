package com.point.desktop

import com.point.core.flow.OcrSpaceTalk
import com.point.core.flow.Realizer
import com.point.core.flow.withoutKey
import com.point.core.model.ActionResult
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Слово компьютера о дороге чтения (#1021): своего движка у него нет, снимок уходит в сервис.
 * Прежде компьютер показывал телефонное «сначала на телефоне» как своё — шаг, которого здесь
 * не будет.
 */
internal const val OCR_ON_PC_PROMISE = "текст · уйдёт в сервис"

class PcCloudOcrRealizer(
    private val config: () -> OcrConfig,
    private val extractor: com.point.core.flow.EntityExtractor = com.point.core.flow.RegexEntityExtractor(),
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,

    /** Поход к сервису — за швом: то, что уходит наружу, проверяется тестом без сети (#592). */
    private val readOutside: ((OcrConfig, File, String) -> String)? = null,
) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.OcrCapability.ID

    // Прочитанное подписано тем, кто читал (#1273): снимок уходит в ocr.space — туда же, куда
    // его отправляет телефон. Сервис один, значит и имя одно, а не «компьютер».
    override val meta = com.point.core.flow.RealizerMeta(
        kind = com.point.core.flow.RealizerKind.CLOUD,
        actor = com.point.core.flow.OCR_SPACE_ACTOR,
    )

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
                if (com.point.core.flow.noTextAnswer(text)) {

                    // «Не нашлось» — знание, а не сбой (Конституция §13).
                    //
                    // Сервис здесь тот же, что у телефона, и отвечает он так же: на кадре без
                    // единой надписи — не пустотой, а служебной пометкой «*[No text detected]*»
                    // (#1054). Пустой лист и пометка — один ответ, и правило одно на обе
                    // поверхности: пометка текстом снимка не становится.
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

                // Та же воронка, что у телефона (#1139, #1144); прочитанное с кадра
                // называет себя чтением — путь знания, не путь объекта (#990).
                val entities = com.point.core.flow.entityDelta(
                    input, found, text,
                    com.point.core.model.Provenance.OCR,
                )
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

                // Слой, который знает, что произошло, уже сказал это своими словами (#1225):
                // «Сервис не принял ключ» накрывалось общим «не ответил», и человек шёл ждать
                // вместо того, чтобы поправить ключ.
                ActionResult.Failure(
                    com.point.core.flow.ownWordsOf(it) ?: "Сервис чтения не ответил — попробуйте позже",
                    recoverable = true,
                )
            }
        }

    /** Тот же путь чтения для страницы документа (#1014): сцепка, не копия. */
    internal fun readFrame(file: File, mime: String): String = read(config(), file, mime)

    /**
     * Компьютерная половина разговора с OCR.space (#1255): доставка байтов и http.
     *
     * Что говорится сервису и как читается его ответ, знает [OcrSpaceTalk] — одно место на оба
     * устройства. Здесь эта половина была написана заново и уже разъехалась с телефонной:
     * компьютер слал движок «2» — неизмеренный, без комментария и без теста, — а телефон «3»,
     * выбранный замером. Один снимок на двух устройствах читался разными движками.
     */
    private fun read(cfg: OcrConfig, file: File, mime: String): String {
        val key = OcrSpaceTalk.keyOrDemo(cfg.key)
        val body = OcrSpaceTalk.form(key, mime, file.readBytes())
        val connection = (URL(cfg.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", OcrSpaceTalk.FORM_TYPE)
        }
        val reply = try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            // Ключ не возвращается на экран, даже если сервис вернул его в тексте отказа.
            val safe = withoutKey(raw, key)

            // Слово этого слоя объявлено им самим (#1225): иначе общий перехват ниже накрывал
            // названный отказ сервиса собственным «не ответил» — причина ложная, шаг ложный.
            // Где лежит ключ, знает только эта сторона — подсказку она и добавляет.
            if (code !in 200..299) OcrSpaceTalk.refuse(code, hintFor(code))
            safe
        } finally {
            connection.disconnect()
        }
        return OcrSpaceTalk.textOf(reply)
    }

    private fun hintFor(code: Int): String? = when (code) {
        401, 403 -> "проверьте ocr.key в ~/.point-pc/config"
        404 -> "проверьте ocr.url там же"
        else -> null
    }

    private companion object {

        /** Предел сервиса знают оба устройства из одного места (#1255). */
        val MAX_BYTES = OcrSpaceTalk.MAX_BYTES
    }
}

data class OcrConfig(
    val key: String = "",
    val url: String = DEFAULT_URL,
) {
    companion object {

        /** Ручка сервиса — одна на оба устройства (#1255). */
        const val DEFAULT_URL = OcrSpaceTalk.DEFAULT_URL
    }
}
