package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.DocBlock
import com.point.core.flow.DocStyle
import com.point.core.flow.DocxWriter
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.Realizer
import com.point.core.flow.TextRecognizer
import com.point.core.model.ActionResult
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

/** Parse the strict block contract: known prefixes only, garbage lines dropped. */
internal fun parseDocBlocks(answer: String): List<DocBlock> =
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
        if (text.isEmpty()) null else DocBlock(text, style)
    }.toList()

/**
 * «В Word+» (#128): the AI twin of «В Word». The local twin lays the text out verbatim;
 * this one asks the LLM to STRUCTURE it (title, headings, bullets) through a strict line
 * contract, and the styled docx writer materialises real formatting. The plus-variant
 * pattern: same accepts as the local twin, "<id>-plus", label "<Label>+", paid/network.
 */
class WordPlusCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "office"
    override val meta = CapabilityMeta(cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "В Word+"
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.PDF || state.kind == ObjectKind.TEXT || state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.OFFICE)

    companion object { val ID = CapabilityId("word-plus") }
}

class WordPlusRealizer @Inject constructor(
    private val llm: LlmClient,
    private val pdfText: PdfTextExtractor,
    private val docx: DocxWriter,
    private val recognizer: TextRecognizer,
) : Realizer {
    override val capabilityId = WordPlusCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = when (input.state.kind) {
                    ObjectKind.PDF -> pdfText.extractText(input)
                    ObjectKind.IMAGE -> recognizer.recognize(input) // OCR the photo first (#128)
                    else -> File(input.uri.value).takeIf { it.isFile }?.readText().orEmpty()
                }.take(MAX_TEXT)
                if (text.isBlank()) {
                    return@withContext ActionResult.Failure("Нет текста (возможно, это скан — сначала распознайте текст)", recoverable = true)
                }
                val answer = llm.run(textStandIn(input), WORD_PLUS_PROMPT + text)
                val blocks = parseDocBlocks(File(answer.uri.value).readText())
                if (blocks.isEmpty()) {
                    ActionResult.Failure("Не удалось разметить документ", recoverable = true)
                } else {
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.OFFICE, DOCX_MIME, docx.writeStyled(blocks),
                            mapOf("op" to "to-word-plus", "name" to "документ.docx"),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось оформить документ", recoverable = true) }
        }

    /** The LLM must lay out the TEXT we extracted, not re-read a PDF binary. */
    private fun textStandIn(input: PointObject) =
        if (input.state.kind == ObjectKind.TEXT) input else input.copy(mime = "text/plain")

    private companion object {
        const val MAX_TEXT = 20_000
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
