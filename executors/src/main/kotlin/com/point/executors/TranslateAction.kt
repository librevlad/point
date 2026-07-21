package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.LlmClient
import com.point.core.flow.Latency
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** text / pdf -> translated text via the LLM (PDF text extracted first). */
class TranslateCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "translate"
    override val meta = CapabilityMeta(latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "Перевести"
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.TEXT, ObjectKind.PDF)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("translate") }
}

class TranslateRealizer @Inject constructor(
    private val llm: LlmClient,
    private val pdfText: PdfTextExtractor,
) : Realizer {
    override val capabilityId = TranslateCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = when (input.state.kind) {
                    ObjectKind.TEXT -> File(input.uri.value).readText()
                    ObjectKind.PDF -> pdfText.extractText(input)
                    else -> ""
                }
                if (text.isBlank()) {
                    ActionResult.Failure("Нет текста для перевода", recoverable = true)
                } else {
                    val target = amendment?.takeIf { it.isNotBlank() } ?: "русский"
                    val prompt = "Переведи текст на $target. Верни только перевод, без пояснений.\n\n$text"
                    ActionResult.Success(llm.run(input, prompt))
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка перевода", recoverable = true) }
        }
}
