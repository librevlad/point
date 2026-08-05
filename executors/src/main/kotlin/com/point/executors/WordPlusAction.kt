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
import com.point.core.flow.PdfTextExtractor
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

/**
 * Тот же строгий контракт блоков, но читателем работает **сама модель** (#247, #263).
 *
 * Промпт разметки сюда не годится ни одним словом: он начинается с «преврати этот сырой текст», а
 * текста нет — движок этот почерк не прочитал. Отдать ему кашу движка вместо страницы значит
 * попросить пересказать шум; отдать снимок со старым промптом — попросить разметить текст,
 * которого в запросе нет.
 *
 * Поэтому здесь два добавления к контракту, и оба — про честность, а не про качество: у модели
 * есть чем сказать «не разобрал» (⚠, его переносит в документ `uncertainInExport`) и чем показать
 * правку, не решая за человека, какая версия актуальна («~~было~~ стало»).
 */
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

/** Parse the strict block contract: known prefixes only, garbage lines dropped. */
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
        // Неуверенность не сглаживается, а переносится в документ (#267).
        if (text.isEmpty()) null else DocBlock(text, style, uncertainInExport(text, mode))
    }.toList()

/**
 * «В Word+» (#128): the AI twin of «В Word». The local twin lays the text out verbatim;
 * this one asks the LLM to STRUCTURE it (title, headings, bullets) through a strict line
 * contract, and the styled docx writer materialises real formatting. The plus-variant
 * pattern: same accepts as the local twin, "<id>-plus", label "<Label>+", paid/network.
 *
 * **Читателей двое, и выбирает между ними страница** (#247). Печатную страницу читает движок, а
 * модель её только размечает — цифру она не видит и подменить не может. Рукописную движок не
 * читает вовсе, и читателем становится модель: тогда ей едет снимок, а не наш пересказ, и весь
 * прочитанный ею документ помечен как прочитанный ею (#263, #267).
 */
class WordPlusCapability @Inject constructor(
    private val keys: AiReadiness,
) : Capability {
    override val id = ID
    override val icon = "office"
    override val meta = CapabilityMeta(cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)

    /** Плюс остался: здесь он и значит «AI-двойник соседнего действия» — единственное, что он
     *  теперь значит во всём наборе (#527, см. «Скан с цветом»). Без ключа имя договаривает
     *  цену, а не молчит до отказа (#529). */
    override fun label(state: ObjectState) = labelNeedingKey("В Word+", keys.keySet())
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.PDF || state.kind == ObjectKind.TEXT || state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.OFFICE)

    /**
     * #491: вид `OFFICE` сам по себе слишком широк — сказано, что это документ Word.
     *
     * #527: и сказано, чем он отличается от соседнего «В Word». Оба обещали «вернёт документ
     * Word», а разница между ними — та самая, ради которой человек и читает строку: местный
     * двойник раскладывает текст, ничего никуда не отправляя, этот отдаёт объект чужому сервису
     * за деньги. Слово выбирается по входу: с фотографии уезжает снимок, с PDF и текста — текст;
     * назвать не то значило бы соврать ровно в той строке, которая заводилась против вранья.
     */
    override fun yields(state: ObjectState) = ActionYield.New(
        ObjectKind.OFFICE,
        "документ Word · ${if (state.kind == ObjectKind.IMAGE) "снимок" else "текст"} уйдёт в сервис",
    )

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
                // Стадии (#288): у «В Word+» перед сетью стоит своя работа — распознать фото или
                // вытащить текст PDF, — и она идёт секунды. Названо то, что происходит сейчас;
                // у текстового входа этого шага нет вовсе, поэтому и стадии про него нет.
                //
                // Слой атомов — единственный адрес, по которому к спорному фрагменту можно
                // приложить кроп-улику (#267). Ридер без геометрии — законная конфигурация:
                // тогда улик просто нет, и это не ошибка.
                var layer: AtomLayer? = null
                val photo = input.state.kind == ObjectKind.IMAGE
                val text = when (input.state.kind) {
                    ObjectKind.PDF -> {
                        reportStage("Читаю текст PDF")
                        pdfText.extractText(input)
                    }
                    ObjectKind.IMAGE -> {
                        reportStage("Распознаю текст на фото") // OCR the photo first (#128)
                        // read(), а не recognize(): у геометрического ридера плоский текст —
                        // производное того же прохода, поэтому второго распознавания не будет.
                        layer = (recognizer as? AtomRecognizer)?.read(input)
                        layer?.text ?: recognizer.recognize(input)
                    }
                    else -> File(input.uri.value).takeIf { it.isFile }?.readText().orEmpty()
                }.take(MAX_TEXT)
                // Режим из метаданных, но если энричер до объекта не дошёл — из того, что мы
                // прочитали сами: иначе рукопись уедет в документ неотмеченной просто потому,
                // что фоновая волна не успела (#263, #267). У кадра пустота и каша больше не
                // молчат — их видно и без геометрии ([readingModeOfFrame]).
                val mode = readingModeOf(input.metadata).takeIf { it != ReadingMode.UNKNOWN }
                    ?: if (photo) readingModeOfFrame(layer, text) else readingModeOf(layer)
                // #247: движок этот почерк не прочитал, и читателем становится зрячая модель —
                // ровно то, что говорит контракт рукописи (#263). Читает она СНИМОК: пересказ
                // каши движка («3}3/9I=I…», дословный вывод устройства на ведомости) она бы
                // разметила в аккуратный документ, собранный из шума, — самый дорогой вид тихой
                // лжи, потому что выглядит он как прочитанная страница. Каши в промпте нет и
                // намеренно: подсунутый «уже прочитанный» текст модель чинит, а не отбрасывает.
                val modelReads = photo && mode == ReadingMode.HANDWRITTEN
                if (text.isBlank() && !modelReads) {
                    return@withContext ActionResult.Failure("Нет текста (возможно, это скан — сначала распознайте текст)", recoverable = true)
                }
                reportStage(if (modelReads) "Модель читает страницу" else "Модель размечает документ")
                val answer =
                    if (modelReads) llm.run(input, WORD_PLUS_HANDWRITING_PROMPT)
                    else llm.run(textStandIn(input), WORD_PLUS_PROMPT + text)
                val read = parseDocBlocks(File(answer.uri.value).readText(), mode)
                if (read.isEmpty()) {
                    ActionResult.Failure(
                        if (modelReads) "Не удалось прочитать страницу" else "Не удалось разметить документ",
                        recoverable = true,
                    )
                } else {
                    reportStage("Собираю документ")
                    val blocks = read
                        // Улика к помеченному, если у фрагмента есть адрес на странице (#267).
                        .withCropEvidence(layer, input.uri.value)
                        // Происхождение всего документа — строкой сверху (#247).
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

    /** The LLM must lay out the TEXT we extracted, not re-read a PDF binary. */
    private fun textStandIn(input: PointObject) =
        if (input.state.kind == ObjectKind.TEXT) input else input.copy(mime = "text/plain")

    private companion object {
        const val MAX_TEXT = 20_000
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
