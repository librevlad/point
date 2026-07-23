package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

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
                        mapOf("op" to "ocr", "engine" to "on-device"),
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
            runCatching { ActionResult.Success(llm.run(input, PROMPT)) }
                .getOrElse { ActionResult.Failure(it.message ?: "Ошибка распознавания в облаке", recoverable = true) }
        }

    private companion object {
        const val PROMPT =
            "Извлеки весь текст с изображения дословно, сохраняя порядок строк. " +
                "Таблицы оформи в Markdown. Верни только текст, без комментариев."
    }
}

/**
 * Tesseract on a photographed document often returns *gibberish* (symbols and isolated 1-2 char
 * fragments) rather than empty — so the blank-check alone never falls back. This flags that
 * gibberish by two cheap signals: too few letters among the non-space characters, or almost no real
 * (4+ letter) words. A false positive just means we use the cloud OCR — better anyway — so this errs
 * toward flagging.
 */
internal fun looksLikeOcrGarbage(text: String): Boolean {
    val nonSpace = text.count { !it.isWhitespace() }
    if (nonSpace < 30) return false // too short to judge — let it through
    val letters = text.count { it.isLetter() }
    val words = Regex("""\p{L}{4,}""").findAll(text).count()
    return letters.toDouble() / nonSpace < 0.6 || words < 3
}
