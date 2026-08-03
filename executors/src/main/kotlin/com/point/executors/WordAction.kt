package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.DocxWriter
import com.point.core.flow.Latency
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.Realizer
import com.point.core.flow.TextRecognizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** One paragraph per line (blank lines kept for spacing) — good enough for an editable document. */
internal fun toParagraphs(text: String): List<String> =
    text.replace("\r\n", "\n").replace("\r", "\n").split("\n")

/** pdf / text → an editable .docx (#61). PDF text is extracted first; a scanned PDF (no text layer)
 *  fails with a hint to OCR. The result is an OFFICE object → share / save / open / to-PDF. */
class WordCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "office"

    /**
     * [Latency.SLOW], а не FAST (#288): над фото «В Word» запускает `TextRecognizer` — тот самый
     * движок и тот самый трёхминутный бюджет, из-за которого «Распознать текст» уже объявлено
     * долгим. Одна работа не может быть объявлена двумя способами: пузырьки стоят рядом над одним
     * снимком, и «этот быстрый» читалось бы как обещание, которого никто не давал.
     *
     * Цена признана и названа: у текстового входа работы почти нет, и экран ожидания там мелькнёт.
     * Это меньшее зло, чем оставить самый долгий вход без экрана — а значит **без кнопки отмены**:
     * передумать через минуту ожидания человек должен уметь всегда, а мигание длится мгновение.
     */
    override val meta = CapabilityMeta(latency = Latency.SLOW)
    override fun label(state: ObjectState) = "В Word"
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.PDF || state.kind == ObjectKind.TEXT || state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.OFFICE)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("word") }
}

class WordRealizer @Inject constructor(
    private val pdfText: PdfTextExtractor,
    private val docx: DocxWriter,
    private val recognizer: TextRecognizer,
) : Realizer {
    override val capabilityId = WordCapability.ID

    /**
     * Те же слова, что у «В Word+» (#288), потому что работа перед ними та же: у PDF — извлечение
     * текста, у фото — Tesseract по всему кадру (десятки секунд, тот же движок, что в «Распознать»).
     * Два соседних пузырька над одним объектом, где первый рассказывает о себе, а второй молчит,
     * читаются как «второй завис»; молчит здесь только текстовый вход — там ждать нечего.
     */
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = when (input.state.kind) {
                    ObjectKind.PDF -> {
                        reportStage("Читаю текст PDF")
                        pdfText.extractText(input)
                    }
                    ObjectKind.IMAGE -> {
                        reportStage("Распознаю текст на фото") // OCR the photo first (#61)
                        recognizer.recognize(input)
                    }
                    ObjectKind.TEXT -> File(input.uri.value).takeIf { it.isFile }?.readText().orEmpty()
                    else -> ""
                }
                if (text.isBlank()) {
                    ActionResult.Failure("Нет текста (возможно, это скан — сначала распознайте текст)", recoverable = true)
                } else {
                    reportStage("Собираю документ")
                    ActionResult.Success(
                        ResultObject(ObjectKind.OFFICE, DOCX_MIME, docx.write(toParagraphs(text)), mapOf("op" to "to-word")),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось создать документ", recoverable = true) }
        }

    private companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
