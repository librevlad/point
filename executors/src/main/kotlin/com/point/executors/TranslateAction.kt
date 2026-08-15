package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.LlmClient
import com.point.core.flow.Latency
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.flow.labelNeedingKey
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

internal fun translateDefaultTarget(text: String): String {
    val cyrillic = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
    val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
    return if (cyrillic > latin) "английский" else "русский"
}

class TranslateCapability @Inject constructor(
    private val keys: AiReadiness,
) : Capability {
    override val id = ID
    override val icon = "translate"
    // Переводить нечего, пока текст не добыт (#996): действие ждёт текста, а не опережает
    // того, кто его даст.
    override val meta =
        CapabilityMeta(latency = Latency.SLOW, network = true, auth = true, needsText = true)
    override fun label(state: ObjectState) = labelNeedingKey("Перевести", keys.keySet())
    // Переводить есть что там, где есть текст, — а не там, где объект нужного вида (#792):
    // прочитанный снимок это объект с текстом, и требовать распознать его второй раз незачем.
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.TEXT, ObjectKind.PDF) || state.has(Feature.HAS_TEXT)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    override fun missing(state: ObjectState) =
        if (state.kind == ObjectKind.IMAGE && !state.has(Feature.HAS_TEXT)) {
            "сначала распознайте текст"
        } else {
            null
        }

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

                    ObjectKind.PDF -> {
                        reportStage("Читаю текст PDF")
                        pdfText.extractText(input)
                    }

                    // Прочитанное с кадра — знание объекта, и перевод берёт его оттуда же,
                    // откуда берут все (#1030). Дверь открыта именно потому, что текст уже
                    // прочитан (`HAS_TEXT`), а за дверью Point отвечал «Нет текста для
                    // перевода» — утверждение о мире, которое сам же только что опроверг.
                    else -> entitySourceText(input)
                }
                if (text.isBlank()) {
                    ActionResult.Failure("Нет текста для перевода", recoverable = true)
                } else {
                    val target = amendment?.takeIf { it.isNotBlank() } ?: translateDefaultTarget(text)
                    val prompt = "Переведи текст на $target. Верни только перевод, без пояснений.\n\n$text"

                    reportStage("Перевожу на $target")
                    ActionResult.Success(llm.run(input, prompt))
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка перевода", recoverable = true) }
        }
}
