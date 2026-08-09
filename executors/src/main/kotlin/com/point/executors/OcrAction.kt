package com.point.executors

import com.point.core.flow.AI_CHAIN_PRIVACY
import com.point.core.flow.Capability
import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.Cost
import com.point.core.flow.ExternalEye
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READ_UPSCALE
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.ObjectStore
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.allowedAt
import com.point.core.flow.META_READING_DOUBT
import com.point.core.flow.degeneratedReading
import com.point.core.flow.readingDoubts
import com.point.core.flow.looksLikeOcrGarbage
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.TextRecognizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

internal const val OCR_CLOUD_PROMPT =
    "Извлеки весь текст с изображения дословно, сохраняя порядок строк. " +
        "Таблицы оформи в Markdown. Верни только текст, без комментариев."

internal const val OCR_CLOUD_STAGE = "Читаю снимок в облаке"

class DeviceOcrRealizer @Inject constructor(
    private val store: ObjectStore,
    private val recognizer: TextRecognizer,
) : Realizer {
    override val capabilityId = OcrCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.LOCAL)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {

            val cached = input.metadata[META_OCR_TEXT_REF]
                ?.let { path -> runCatching { File(path).takeIf(File::isFile)?.readText() }.getOrNull() }
            if (!cached.isNullOrBlank()) {
                return@withContext ActionResult.Success(
                    ResultObject(
                        ObjectKind.TEXT,
                        "text/plain",
                        ScratchRef(input.metadata.getValue(META_OCR_TEXT_REF)),
                        ocrMeta(input),
                    ),
                )
            }

            reportStage("Читаю текст на устройстве")
            val text = runCatching { recognizer.recognize(input) }.getOrDefault("")
            if (text.isBlank() || looksLikeOcrGarbage(text)) {

                return@withContext ActionResult.Failure("На устройстве текст не распознан", recoverable = true)
            }
            runCatching {
                val ref = store.newScratchFile("txt")
                File(ref.value).writeText(text)
                ActionResult.Success(
                    ResultObject(
                        ObjectKind.TEXT,
                        "text/plain",
                        ref,
                        ocrMeta(input),
                    ),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка записи результата", recoverable = true) }
        }
}

class ExternalEyeOcrRealizer @Inject constructor(
    private val eye: ExternalEye,
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = OcrCapability.ID
    override val meta = RealizerMeta(priority = 50, kind = RealizerKind.CLOUD)

    override fun isAvailable(): Boolean = eye.available()

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) { readWithExternalEye(eye, store, input) }
}

class CloudOcrRealizer @Inject constructor(
    private val llm: LlmClient,
    private val privacy: CloudPrivacySettings,
) : Realizer {
    override val capabilityId = OcrCapability.ID
    override val meta = RealizerMeta(priority = 90, kind = RealizerKind.CLOUD)

    override fun isAvailable(): Boolean = allowedAt(privacy.level(), AI_CHAIN_PRIVACY)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) return@withContext ActionResult.Failure(chainClosed(privacy.level()), recoverable = true)

            reportStage(OCR_CLOUD_STAGE)
            runCatching { guarded(llm.run(input, OCR_CLOUD_PROMPT), "модель") }
                .getOrElse { ActionResult.Failure(it.message ?: "Ошибка распознавания в облаке", recoverable = true) }
        }
}

class CloudOcrCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "ocr-cloud"
    override val meta = CapabilityMeta(cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)

    override fun label(state: ObjectState) = "Прочитать сильнее"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    override fun yields(state: ObjectState) = ActionYield.New(ObjectKind.TEXT, "текст · снимок уйдёт в сервис")

    companion object { val ID = CapabilityId("ocr-cloud") }
}

class ExternalEyeCloudOcrRealizer @Inject constructor(
    private val eye: ExternalEye,
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = CloudOcrCapability.ID
    override val meta = RealizerMeta(priority = 5, kind = RealizerKind.CLOUD)

    override fun isAvailable(): Boolean = eye.available()

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) { readWithExternalEye(eye, store, input) }
}

class CloudOcrDirectRealizer @Inject constructor(
    private val llm: LlmClient,
    private val privacy: CloudPrivacySettings,
) : Realizer {
    override val capabilityId = CloudOcrCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.CLOUD)

    override fun isAvailable(): Boolean = allowedAt(privacy.level(), AI_CHAIN_PRIVACY)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) return@withContext ActionResult.Failure(chainClosed(privacy.level()), recoverable = true)
            reportStage(OCR_CLOUD_STAGE)
            runCatching { guarded(llm.run(input, OCR_CLOUD_PROMPT), "модель") }
                .getOrElse { ActionResult.Failure(it.message ?: "Ошибка распознавания в облаке", recoverable = true) }
        }
}

private suspend fun readWithExternalEye(
    eye: ExternalEye,
    store: ObjectStore,
    input: PointObject,
): ActionResult {
    reportStage(OCR_CLOUD_STAGE)
    val reading = runCatching { eye.read(input) }.getOrElse {
        return ActionResult.Failure(it.message ?: "Прочитать снаружи не удалось", recoverable = true)
    }
    degeneratedReading(reading.text)?.let { why ->
        return ActionResult.Failure(unreadable(reading.reader, why), recoverable = true)
    }

    // Markdown-обёртка провайдера — не текст страницы (#661): «![img-0.jpeg](…)» и
    // «# Відділення 1» уходили в объект и дальше в знание (живой прогон 2026-08-09).
    val page = com.point.core.flow.stripMarkdownChrome(reading.text)
    return runCatching {
        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(page)
        ActionResult.Success(
            ResultObject(
                ObjectKind.TEXT,
                "text/plain",
                ref,
                buildMap {
                    put("op", "ocr")

                    put("engine", reading.reader)
                    put("where", reading.where)

                    reading.promise.takeIf { it.isNotBlank() }?.let { put("promise", it) }
                    input.metadata[META_READING_MODE]?.let { put(META_READING_MODE, it) }

                    readingDoubts(page).takeIf { it.isNotEmpty() }?.let { doubts ->
                        put(META_READING_DOUBT, doubts.joinToString("; ") { it.what })
                    }

                    input.metadata[META_READ_UPSCALE]?.let { put(META_READ_UPSCALE, it) }
                },
            ),
        )
    }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка записи результата", recoverable = true) }
}

private fun guarded(result: ResultObject, who: String): ActionResult {
    val text = runCatching { File(result.uri.value).readText() }.getOrNull() ?: return ActionResult.Success(result)
    val why = degeneratedReading(text) ?: return ActionResult.Success(result)
    return ActionResult.Failure(unreadable(who, why), recoverable = true)
}

private fun unreadable(who: String, why: String): String =
    "Не смог прочитать этот снимок: $who отдала бессмыслицу ($why). " +
        "Лучше переснять при ровном свете и поближе."

private fun chainClosed(level: PrivacyLevel): String = when (level) {
    PrivacyLevel.DEVICE_ONLY -> "Наружу ничего не отправляется — в настройках выбрано «Только на телефоне»"
    else -> "Читают только те, кто обещал не учиться на присланном, — так выбрано в настройке " +
        "«Куда можно отправлять»"
}

private fun ocrMeta(input: PointObject): Map<String, String> = buildMap {
    put("op", "ocr")
    put("engine", "on-device")
    input.metadata[META_READING_MODE]?.let { put(META_READING_MODE, it) }
    input.metadata[META_READ_UPSCALE]?.let { put(META_READ_UPSCALE, it) }
}
