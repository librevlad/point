package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.ObjectStore
import com.point.core.flow.looksLikeOcrGarbage
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.TextRecognizer
import com.point.core.model.ActionResult
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

/** The one prompt for cloud (vision) OCR — shared by the automatic fallback realizer and the
 *  explicit "Распознать в облаке" escalation. */
internal const val OCR_CLOUD_PROMPT =
    "Извлеки весь текст с изображения дословно, сохраняя порядок строк. " +
        "Таблицы оформи в Markdown. Верни только текст, без комментариев."

/**
 * photo -> recognised text. Tries on-device OCR first (Tesseract, rus+eng — free,
 * offline, no key or quota), and only falls back to the cloud LLM vision path
 * when on-device finds nothing (hard scans) or the user wants tables as Markdown.
 * The result is a TEXT object, so it chains into translate / to-PDF / save.
 */
class OcrCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "ocr"
    // No network/auth required by default — on-device handles the common case.
    override val meta = CapabilityMeta(cost = Cost.FREE, latency = Latency.FAST)
    override fun label(state: ObjectState) = "Распознать текст"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("ocr") }
}

/**
 * On-device OCR — the chain's **preferred** realizer (local, priority 10, always
 * selectable). Runs Tesseract (rus+eng): free, offline, no key or quota. A blank
 * recognition (hard scan) or an engine failure is a **recoverable** Failure, which the
 * Resolver's fallback chain hands off to [CloudOcrRealizer]. A hit is a TEXT object, so
 * it chains into translate / to-PDF / save.
 */
class DeviceOcrRealizer @Inject constructor(
    private val store: ObjectStore,
    private val recognizer: TextRecognizer,
) : Realizer {
    override val capabilityId = OcrCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.LOCAL)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            // The OCR enricher may have read this image already (#64) — reuse its sidecar
            // instead of running the engine a second time on the same pixels.
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
            val text = runCatching { recognizer.recognize(input) }.getOrDefault("")
            if (text.isBlank() || looksLikeOcrGarbage(text)) {
                // Blank OR gibberish (Tesseract on a photographed document) → hand off to the
                // cloud vision OCR, which reads real-world photos far better.
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

/**
 * Cloud OCR — the chain's **fallback** realizer (network vision via [LlmClient],
 * priority 90). Reached only after on-device recognised nothing. A missing key /
 * provider failure surfaces as a recoverable Failure — as the last link in the chain,
 * this is what the user sees when nothing could read the image.
 */
class CloudOcrRealizer @Inject constructor(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = OcrCapability.ID
    override val meta = RealizerMeta(priority = 90, kind = RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching { ActionResult.Success(llm.run(input, OCR_CLOUD_PROMPT)) }
                .getOrElse { ActionResult.Failure(it.message ?: "Ошибка распознавания в облаке", recoverable = true) }
        }
}

/**
 * Explicit cloud OCR — the escalation for a hard document photo where on-device Tesseract returns
 * gibberish (phone photos: low contrast, shadows, skew, handwriting). A separate, always-cloud
 * bubble the user taps when "Распознать текст" produced junk; the vision LLM reads such photos far
 * better. Distinct capability (not the fallback realizer) so it shows as its own choice.
 */
class CloudOcrCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "ocr-cloud"
    override val meta = CapabilityMeta(cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "Распознать в облаке"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("ocr-cloud") }
}

class CloudOcrDirectRealizer @Inject constructor(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = CloudOcrCapability.ID
    override val meta = RealizerMeta(priority = 10, kind = RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching { ActionResult.Success(llm.run(input, OCR_CLOUD_PROMPT)) }
                .getOrElse { ActionResult.Failure(it.message ?: "Ошибка распознавания в облаке", recoverable = true) }
        }
}

/**
 * Метаданные результата распознавания. Режим чтения (#263) **переносится на текстовый объект**:
 * его наблюдал движок на картинке, а действует он дальше по цепочке — «В Word+» решает по нему,
 * помечать ли цифры (#267). Без переноса пометка не срабатывала именно там, где нужна: на
 * рукописи, распознанной отдельным шагом (ревью).
 */
private fun ocrMeta(input: PointObject): Map<String, String> = buildMap {
    put("op", "ocr")
    put("engine", "on-device")
    input.metadata[META_READING_MODE]?.let { put(META_READING_MODE, it) }
}
