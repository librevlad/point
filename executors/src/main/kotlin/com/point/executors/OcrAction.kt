package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * photo -> recognised text (incl. tables). Realized via the LLM vision path
 * (Gemini/OpenAI, with fallback), which handles Cyrillic and returns tables as
 * Markdown — where on-device OCR falls short. The result is a TEXT object, so it
 * chains into translate / to-PDF / save / share.
 */
class OcrCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "ocr"
    override val meta = CapabilityMeta(cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "Распознать текст"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("ocr") }
}

class OcrRealizer @Inject constructor(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = OcrCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                ActionResult.Success(llm.run(input, PROMPT))
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка распознавания", recoverable = true) }
        }

    private companion object {
        const val PROMPT =
            "Извлеки весь текст с изображения дословно, сохраняя порядок строк. " +
                "Таблицы оформи в Markdown. Верни только текст, без комментариев."
    }
}
