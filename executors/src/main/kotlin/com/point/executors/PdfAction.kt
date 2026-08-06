package com.point.executors

import com.point.core.flow.capabilities.OFFICE_PDF_SUBSTANCE
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.META_YIELD_NOUN
import com.point.core.flow.ObjectStore
import com.point.core.flow.OfficeTextExtractor
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.SpreadsheetReader
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Чем офисный файл оказывается после «В PDF» — одним словом и в одном месте (#558).
 *
 * Слово одно и то же в двух руках: подпись обещает им до тапа ([PdfCapability.yields]), а
 * реализатор ставит его на результат ([META_YIELD_NOUN]), и сторож сверяет обещание с вышедшим.
 * Разъедутся — человеку скажут словами; а разъехаться, не заметив, нельзя: константа одна.
 */

/**
 * Пересказ, названный пересказом (#558).
 *
 * Обе ветки офисного пути — таблица и проза — печатают заново вынутый текст, а не переносят
 * документ. Слово, которым это обещано до тапа, ставится на результат: разъедутся обещание и
 * выход — [com.point.core.flow.yieldSurprise] скажет об этом человеку, а не оставит его открывать
 * файл через час.
 *
 * Отдельной функцией, а не строкой внутри реализатора: сам реализатор рисует PDF средствами
 * телефона и на JVM не запускается, а сверить обещание с помеченным — ровно то, что проверять
 * надо. Отказ проходит насквозь: помечать нечего.
 */
internal fun retoldFromOffice(result: ActionResult): ActionResult =
    if (result is ActionResult.Success) {
        ActionResult.Success(
            result.result.copy(metadata = result.result.metadata + (META_YIELD_NOUN to OFFICE_PDF_SUBSTANCE)),
        )
    } else {
        result
    }

/** image/text/office -> PDF, and PDF -> extracted text. */
class PdfCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "pdf"
    // Real rendering/extraction work — honest latency keeps it out of the «Мгновенные» tier (#114).
    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) =
        if (state.kind == ObjectKind.PDF) "Извлечь текст" else "В PDF"
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.IMAGE, ObjectKind.TEXT, ObjectKind.OFFICE) ||
            // A scan (image-only PDF) has no text layer — "Извлечь текст" would only dead-end.
            (state.kind == ObjectKind.PDF && !state.has(Feature.IS_IMAGE_PDF))
    override fun produces(state: ObjectState) =
        if (state.kind == ObjectKind.PDF) ObjectState(ObjectKind.TEXT) else ObjectState(ObjectKind.PDF)

    /**
     * Подпись говорит о результате **по существу**, а не по расширению файла (#558).
     *
     * Жалоба владельца дословно: «Word в PDF молча дал не то, что человек хотел». Разбор: офисный
     * файл на телефоне превращается в PDF **пересказом** — [PdfRealizer.officeToPdf] вынимает из
     * документа текст и печатает его заново. На выходе настоящий PDF, поэтому вид совпадал с
     * обещанным и сторож [com.point.core.flow.yieldSurprise] молчал; что внутри пересказ, а не
     * документ, человек выяснял, открыв файл.
     *
     * Пока конвертация такая (её чинит #403), обещать голое «вернёт PDF» — неправда умолчанием.
     * Здесь чинится подпись, а не поведение: тот же файл, но сказано, что в нём будет.
     */
    override fun yields(state: ObjectState) = when (state.kind) {
        ObjectKind.PDF -> ActionYield.New(ObjectKind.TEXT)
        ObjectKind.OFFICE -> ActionYield.New(ObjectKind.PDF, "$OFFICE_PDF_SUBSTANCE · без оформления")
        else -> ActionYield.New(ObjectKind.PDF)
    }

    companion object { val ID = CapabilityId("pdf") }
}

class PdfRealizer @Inject constructor(
    private val store: ObjectStore,
    private val pdfText: PdfTextExtractor,
    private val officeText: OfficeTextExtractor,
    private val spreadsheet: SpreadsheetReader,
) : Realizer {
    override val capabilityId = PdfCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                when (input.state.kind) {
                    ObjectKind.IMAGE -> imageToPdf(input)
                    ObjectKind.TEXT -> renderTextToPdf(File(input.uri.value).readText())
                    ObjectKind.PDF -> pdfToText(input)
                    ObjectKind.OFFICE -> officeToPdf(input)
                    else -> ActionResult.Failure(NOT_THIS_OBJECT, recoverable = false)
                }
            }.getOrElse { ActionResult.Failure(it.message ?: PDF_FAILED, recoverable = true) }
        }

    private suspend fun imageToPdf(input: PointObject): ActionResult {
        reportStage("Читаю изображение")
        val bitmap = Bitmaps.decodeUpright(input.uri.value) ?: error("Не удалось прочитать изображение")
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create())
        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
        document.finishPage(page)
        val ref = write(document)
        bitmap.recycle()
        return ActionResult.Success(ResultObject(ObjectKind.PDF, "application/pdf", ref))
    }

    private suspend fun officeToPdf(input: PointObject): ActionResult {
        // #288: разбор docx/xlsx — секунды до всякой отрисовки. Слова общие с «Извлечь текст»
        // ([OFFICE_READ_STAGE]): работа буквально одна, и разъехаться им нельзя.
        reportStage(OFFICE_READ_STAGE)
        // A spreadsheet is a grid, not prose: read its rows and render a monospace table. Our own
        // «В Excel» writes inline strings (no sharedStrings.xml), which the text extractor can't
        // read — so without this branch converting Point's own xlsx dead-ends on empty text.
        if (isSpreadsheet(input)) {
            val rows = spreadsheet.readRows(input)
            if (rows.any { row -> row.any { it.isNotBlank() } }) {
                return retoldFromOffice(renderTextToPdf(formatSpreadsheet(rows), mono = true))
            }
        }
        val text = officeText.extractText(input)
        return if (text.isBlank()) {
            ActionResult.Failure(
                "В документе не нашлось текста — если это старый формат (.doc/.xls/.ppt), " +
                    "пересохраните его в новом и попробуйте снова",
                recoverable = true,
            )
        } else {
            retoldFromOffice(renderTextToPdf(text))
        }
    }

    private fun isSpreadsheet(input: PointObject): Boolean {
        val mime = input.mime.lowercase()
        val path = input.uri.value.lowercase()
        return "spreadsheet" in mime || mime == "application/vnd.ms-excel" ||
            path.endsWith(".xlsx") || path.endsWith(".xls")
    }

    /**
     * Стадии многостраничного «В PDF» (#288): длинный текст сперва целиком меряется по словам
     * ([wrap] — реальные секунды на книге), потом страницы рисуются одна за другой. Номер страницы
     * называется без «из N»: сколько их получится, знает только сам этот цикл — он и решает, когда
     * страница кончилась. Обещать общее число значило бы посчитать его вторым способом и однажды
     * разойтись с первым.
     */
    private suspend fun renderTextToPdf(text: String, mono: Boolean = false): ActionResult {
        val paint = Paint().apply {
            textSize = if (mono) 10f else 12f
            if (mono) typeface = Typeface.MONOSPACE
        }
        reportStage("Раскладываю по страницам")
        val lines = wrap(text, paint, PAGE_WIDTH - 2 * MARGIN)

        val document = PdfDocument()
        var index = 0
        var pageNumber = 1
        do {
            reportStage("Страница $pageNumber")
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create(),
            )
            var y = MARGIN + LINE_HEIGHT
            while (index < lines.size && y < PAGE_HEIGHT - MARGIN) {
                page.canvas.drawText(lines[index], MARGIN, y, paint)
                y += LINE_HEIGHT
                index++
            }
            document.finishPage(page)
        } while (index < lines.size)

        val ref = write(document)
        return ActionResult.Success(ResultObject(ObjectKind.PDF, "application/pdf", ref))
    }

    private suspend fun pdfToText(input: PointObject): ActionResult {
        reportStage("Извлекаю текст из PDF")
        val text = pdfText.extractText(input)
        if (text.isBlank()) {
            return ActionResult.Failure(NO_TEXT_LAYER, recoverable = true)
        }
        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(text)
        return ActionResult.Success(
            ResultObject(ObjectKind.TEXT, "text/plain", ref, mapOf("op" to "pdf-extract")),
        )
    }

    private suspend fun write(document: PdfDocument): ScratchRef {
        val ref = store.newScratchFile("pdf")
        File(ref.value).outputStream().use { document.writeTo(it) }
        document.close()
        return ref
    }

    /** Greedy word-wrap that also respects existing newlines. */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        for (raw in text.split('\n')) {
            if (raw.isEmpty()) {
                result += ""
                continue
            }
            var line = StringBuilder()
            for (word in raw.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    line = StringBuilder(candidate)
                } else {
                    if (line.isNotEmpty()) result += line.toString()
                    line = StringBuilder(word)
                }
            }
            result += line.toString()
        }
        return result
    }

    internal companion object {
        const val PAGE_WIDTH = 595   // A4 @72dpi
        const val PAGE_HEIGHT = 842
        const val MARGIN = 32f
        const val LINE_HEIGHT = 16f

        /**
         * «Не с этим объектом», сказанное перечислением того, с чем — можно (#541).
         *
         * Прежнее «PDF: неподдерживаемый вход» было записью в лог, случайно показанной человеку:
         * формат «модуль: сообщение», слово «вход» из нашего словаря и ни слова о том, что
         * делать. Отказ **невосстановимый** — повторять тап бессмысленно, — поэтому строка
         * называет объекты, на которых действие работает, а не советует «попробуйте ещё раз».
         */
        const val NOT_THIS_OBJECT = "В PDF превращаются снимок, текст и документ — этот объект не из них"

        /** Последняя сетка на неизвестный сбой: «Ошибка PDF» называла место в коде, а не новость. */
        const val PDF_FAILED = "PDF не собрался — попробуйте ещё раз"

        /**
         * Отказ зовёт действия теми же словами, что написаны на кнопках (#541).
         *
         * Прежняя строка советовала «нужен OCR» — слово, которого в продукте нет ни на одной
         * кнопке: чтение снимка называется «Распознать текст», разбор PDF на страницы —
         * «Страницы». Совет, ведущий к несуществующей кнопке, хуже молчания: человек ищет её и
         * не находит.
         *
         * Случай снова «не с этим объектом»: извлекать нечего не потому, что Point не умеет, а
         * потому, что страницы этого PDF сняты картинкой.
         */
        const val NO_TEXT_LAYER =
            "В этом PDF нет текста — страницы сняты картинкой. Разложите его действием «Страницы», " +
                "потом «Распознать текст»"
    }
}
