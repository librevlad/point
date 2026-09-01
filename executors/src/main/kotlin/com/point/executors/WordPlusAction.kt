package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Capability
import com.point.core.flow.labelNeedingKey
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.DocBlock
import com.point.core.flow.uncertainInExport
import com.point.core.flow.withCropEvidence
import com.point.core.flow.withReadingNote
import com.point.core.flow.readingModeOf
import com.point.core.flow.readingModeOfFrame
import com.point.core.flow.ReadingMode
import com.point.core.flow.DocStyle
import com.point.core.flow.DocxWriter
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.flow.TextRecognizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val WORD_PLUS_PROMPT =
    "Преврати этот сырой текст в аккуратный структурированный документ. Отвечай ТОЛЬКО строками " +
        "вида ПРЕФИКС=текст, по одной на строку, без пояснений. Префиксы: T= заголовок документа " +
        "(один, первым), H= заголовок раздела, B= пункт списка, P= обычный абзац. Сохраняй все " +
        "факты и числа дословно, ничего не выдумывай.\n\nТекст:\n"

internal const val WORD_PLUS_HANDWRITING_PROMPT =
    "На снимке — страница, написанная от руки (возможно, поверх печатного бланка, снятая под " +
        "углом или повёрнутая) — читай внимательно в любой ориентации. Прочитай её по снимку и " +
        "собери аккуратный структурированный документ. Отвечай ТОЛЬКО строками вида ПРЕФИКС=текст, " +
        "по одной на строку, без пояснений. Префиксы: T= заголовок документа (один, первым), " +
        "H= заголовок раздела, B= пункт списка, P= обычный абзац. " +
        "Сохраняй порядок и вложенность записей, числа переноси дословно. " +
        "Не выдумывай: чего на снимке нет, того нет и в ответе. " +
        "Если фрагмент видно, но ты не уверен в прочтении — добавь символ ⚠ в конец этой строки: " +
        "её подсветят для проверки. Совсем неразборчивое место оставь пропуском «…⚠», а не " +
        "правдоподобной догадкой. " +
        "Зачёркнутое не выбрасывай: пиши «~~было~~ стало» (исправлено) или «~~было~~» (просто " +
        "зачёркнуто) — какая версия актуальна, решит человек."

internal fun parseDocBlocks(
    answer: String,
    mode: ReadingMode = ReadingMode.UNKNOWN,
): List<DocBlock> =
    answer.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        if (line.length < 3 || line[1] != '=') return@mapNotNull null
        val style = when (line[0]) {
            'T' -> DocStyle.TITLE
            'H' -> DocStyle.HEADING
            'B' -> DocStyle.BULLET
            'P' -> DocStyle.NORMAL
            else -> return@mapNotNull null
        }
        val text = line.substring(2).trim()

        if (text.isEmpty()) null else DocBlock(text, style, uncertainInExport(text, mode))
    }.toList()

class WordPlusCapability @Inject constructor(
    private val keys: AiReadiness,
) : Capability {
    override val id = ID
    override val icon = "office"
    override val meta = CapabilityMeta(cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)

    override fun label(state: ObjectState) = labelNeedingKey("В Word+", keys.keySet())
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.PDF || state.kind == ObjectKind.TEXT || state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.OFFICE)

    override fun yields(state: ObjectState) = ActionYield.New(
        ObjectKind.OFFICE,
        "документ Word · ${if (state.kind == ObjectKind.IMAGE) "снимок" else "текст"} уйдёт в сервис",
    )

    companion object { val ID = com.point.core.flow.KnownCapabilities.WORD_PLUS }
}

class WordPlusRealizer @Inject constructor(
    private val llm: LlmClient,
    private val known: com.point.core.flow.CurrentKnowledge,
    private val docx: DocxWriter,
    private val recognizer: TextRecognizer,
) : Realizer {
    override val capabilityId = WordPlusCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {

                // Сначала то, что Point уже знает (#1031, #1138): снятый слой слов и
                // прочитанный текст живут в графе, и читать кадр заново незачем.
                var layer: AtomLayer? = known.layerOf(input)
                val photo = input.state.kind == ObjectKind.IMAGE
                val text = (
                    layer?.text?.takeIf { it.isNotBlank() }
                        ?: known.textOf(input)
                        ?: when (input.state.kind) {
                            ObjectKind.IMAGE -> {
                                reportStage("Распознаю текст на фото")

                                layer = (recognizer as? AtomRecognizer)?.read(input)
                                layer?.text ?: recognizer.recognize(input)
                            }
                            else -> ""
                        }
                    ).take(MAX_TEXT)

                val mode = readingModeOf(input.metadata).takeIf { it != ReadingMode.UNKNOWN }
                    ?: if (photo) readingModeOfFrame(layer, text) else readingModeOf(layer)

                val modelReads = photo && mode == ReadingMode.HANDWRITTEN
                if (text.isBlank() && !modelReads) {
                    return@withContext ActionResult.Failure("Нет текста (возможно, это скан — сначала распознайте текст)", recoverable = true)
                }
                reportStage(if (modelReads) "Читаю страницу" else "Размечаю документ")
                val answer =
                    if (modelReads) llm.run(input, WORD_PLUS_HANDWRITING_PROMPT)
                    else llm.run(com.point.core.flow.textStandIn(input), WORD_PLUS_PROMPT + text)
                val read = parseDocBlocks(File(answer.uri.value).readText(), mode)
                if (read.isEmpty()) {
                    ActionResult.Failure(
                        if (modelReads) "Не удалось прочитать страницу" else "Не удалось разметить документ",
                        recoverable = true,
                    )
                } else {
                    reportStage("Собираю документ")
                    val blocks = read

                        .withCropEvidence(layer, input.uri.value)

                        .withReadingNote(mode)
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.OFFICE, DOCX_MIME, docx.writeStyled(blocks),
                            mapOf("op" to "to-word-plus", "name" to "документ.docx"),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось оформить документ", recoverable = true) }
        }

    private companion object {
        const val MAX_TEXT = 20_000
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
