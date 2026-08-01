package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.LlmClient
import com.point.core.flow.Latency
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The target language when the user gives none: Russian text → English, everything else →
 * Russian. Without this a one-tap "Перевести" always went to Russian, so Russian input came back
 * unchanged and looked like "nothing happened". A manual amendment still overrides it.
 */
internal fun translateDefaultTarget(text: String): String {
    val cyrillic = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
    val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
    return if (cyrillic > latin) "английский" else "русский"
}

/** text / pdf -> translated text via the LLM (PDF text extracted first). */
class TranslateCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "translate"
    override val meta = CapabilityMeta(latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "Перевести"
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.TEXT, ObjectKind.PDF)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    // A photo's text is one OCR away — surface translate as "почти доступно" (#97).
    override fun missing(state: ObjectState) =
        if (state.kind == ObjectKind.IMAGE) "сначала распознайте текст" else null

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
                    // Извлечение текста из большого PDF — секунды до всякой сети (#288).
                    ObjectKind.PDF -> {
                        reportStage("Читаю текст PDF")
                        pdfText.extractText(input)
                    }
                    else -> ""
                }
                if (text.isBlank()) {
                    ActionResult.Failure("Нет текста для перевода", recoverable = true)
                } else {
                    val target = amendment?.takeIf { it.isNotBlank() } ?: translateDefaultTarget(text)
                    val prompt = "Переведи текст на $target. Верни только перевод, без пояснений.\n\n$text"
                    // Язык в стадии — не украшение (#288): при одном тапе его выбрал код
                    // ([translateDefaultTarget]), и человек узнаёт этот выбор ДО того, как получит
                    // результат не на том языке, которого ждал.
                    reportStage("Перевожу на $target")
                    ActionResult.Success(llm.run(input, prompt))
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка перевода", recoverable = true) }
        }
}
