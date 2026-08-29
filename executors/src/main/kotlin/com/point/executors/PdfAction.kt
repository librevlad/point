package com.point.executors

import com.point.core.flow.capabilities.PdfCapability
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.Findings
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
 * Телефонного пути «офис → PDF» здесь нет намеренно (#403, решение владельца 05.08.2026).
 *
 * Он существовал пересказом: из документа вынимался текст и печатался заново — слайд терял
 * картинки, разметку и порядок и переставал быть слайдом. Настоящую конвертацию делает
 * компьютер с офисом, и он же объявляет это умение телефону обычным исполнителем той же
 * способности «В PDF».
 */
class PdfRealizer @Inject constructor(
    private val store: ObjectStore,
    private val pdfText: PdfTextExtractor,
) : Realizer {
    override val capabilityId = PdfCapability.ID

    override fun accepts(state: ObjectState) = state.kind != ObjectKind.OFFICE

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                when (input.state.kind) {
                    ObjectKind.IMAGE -> imageToPdf(input)
                    ObjectKind.TEXT -> renderTextToPdf(File(input.uri.value).readText())
                    ObjectKind.PDF -> pdfToText(input)
                    else -> ActionResult.Failure(NOT_THIS_OBJECT, recoverable = false)
                }
            }.getOrElse { ActionResult.Failure(it.message ?: PDF_FAILED, recoverable = true) }
        }

    private suspend fun imageToPdf(input: PointObject): ActionResult {
        reportStage("Читаю изображение")
        val bitmap = Bitmaps.decodeUpright(input.uri.value) ?: error("Не удалось прочитать изображение")
        val document = PdfDocument()
        // Одиночный снимок становится страницей по тем же правилам, что и набор (#1047).
        document.addPage(bitmap, 1)
        val ref = write(document)
        bitmap.recycle()
        return ActionResult.Success(ResultObject(ObjectKind.PDF, "application/pdf", ref))
    }

    private suspend fun renderTextToPdf(text: String, mono: Boolean = false): ActionResult {
        val paint = Paint().apply {
            textSize = if (mono) 10f else 12f
            if (mono) typeface = Typeface.MONOSPACE
        }
        reportStage("Раскладываю по страницам")
        val lines = wrap(text, paint, A4.width - 2 * MARGIN)

        val document = PdfDocument()
        var index = 0
        var pageNumber = 1
        do {
            reportStage("Страница $pageNumber")
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(A4.width, A4.height, pageNumber++).create(),
            )
            var y = MARGIN + LINE_HEIGHT
            while (index < lines.size && y < A4.height - MARGIN) {
                page.canvas.drawText(lines[index], MARGIN, y, paint)
                y += LINE_HEIGHT
                index++
            }
            document.finishPage(page)
        } while (index < lines.size)

        val ref = write(document)
        return ActionResult.Success(ResultObject(ObjectKind.PDF, "application/pdf", ref))
    }

    /**
     * Текст PDF — знание самого документа (#995, решение владельца 21.08.2026).
     *
     * Второго объекта здесь больше не рождается: раньше на запасном пути папка отрисованных
     * страниц выдавалась за одиночную картинку — объект указывал на каталог и не открывался
     * ни у кого, а настоящая страница лежала внутри и была недостижима.
     *
     * Из файла достаётся только то, что в файле есть. Слоя нет или он нечитаем (своя
     * раскладка шрифта, #933) — страницы читает «Прочитать документ» (#1014): там это
     * объявлено долгой работой, а здесь обещано «текст документа · без сети». Делать долгую
     * работу за быстрым обещанием — врать человеку, поэтому отказ называет тот шаг, который
     * у документа есть (#1257), а не второй раз делает его чужими руками.
     */
    private suspend fun pdfToText(input: PointObject): ActionResult {
        reportStage("Извлекаю текст из PDF")
        val text = pdfText.extractText(input)
        if (com.point.core.flow.pdfLayerUnusable(text)) {
            return ActionResult.Failure(com.point.core.flow.capabilities.NO_READABLE_PDF_LAYER, recoverable = false)
        }

        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(text)
        return ActionResult.Done(
            com.point.core.flow.capabilities.TEXT_IS_WITH_DOCUMENT,
            Findings(
                features = setOf(Feature.HAS_TEXT),
                metadata = mapOf(com.point.core.flow.META_OCR_TEXT_REF to ref.value),
            ),
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
        const val MARGIN = 32f
        const val LINE_HEIGHT = 16f

        const val NOT_THIS_OBJECT = "В PDF превращаются снимок, текст и документ — этот объект не из них"

        const val PDF_FAILED = "PDF не собрался — попробуйте ещё раз"
    }
}
