package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
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

class OcrRealizer @Inject constructor(
    private val store: ObjectStore,
    private val recognizer: TextRecognizer,
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = OcrCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val onDevice = runCatching { recognizer.recognize(input) }.getOrDefault("")
                if (onDevice.isNotBlank()) {
                    val ref = store.newScratchFile("txt")
                    File(ref.value).writeText(onDevice)
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.TEXT,
                            "text/plain",
                            ref,
                            mapOf("op" to "ocr", "engine" to "on-device"),
                        ),
                    )
                } else {
                    // On-device recognised nothing — fall back to the cloud LLM.
                    ActionResult.Success(llm.run(input, PROMPT))
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка распознавания", recoverable = true) }
        }

    private companion object {
        const val PROMPT =
            "Извлеки весь текст с изображения дословно, сохраняя порядок строк. " +
                "Таблицы оформи в Markdown. Верни только текст, без комментариев."
    }
}
