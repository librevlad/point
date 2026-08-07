package com.point.executors

import com.point.core.flow.capabilities.OFFICE_PDF_SUBSTANCE
import com.point.core.flow.capabilities.PdfCapability
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

internal fun retoldFromOffice(result: ActionResult): ActionResult =
    if (result is ActionResult.Success) {
        ActionResult.Success(
            result.result.copy(metadata = result.result.metadata + (META_YIELD_NOUN to OFFICE_PDF_SUBSTANCE)),
        )
    } else {
        result
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

        reportStage(OFFICE_READ_STAGE)

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
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 32f
        const val LINE_HEIGHT = 16f

        const val NOT_THIS_OBJECT = "В PDF превращаются снимок, текст и документ — этот объект не из них"

        const val PDF_FAILED = "PDF не собрался — попробуйте ещё раз"

        const val NO_TEXT_LAYER =
            "В этом PDF нет текста — страницы сняты картинкой. Разложите его действием «Страницы», " +
                "потом «Распознать текст»"
    }
}
