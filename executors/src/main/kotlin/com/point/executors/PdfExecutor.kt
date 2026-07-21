package com.point.executors

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.point.core.flow.Executor
import com.point.core.flow.ObjectStore
import com.point.core.model.ExecutorId
import com.point.core.model.ExecutorResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Bidirectional: image/text -> PDF, and PDF -> extracted text. */
class PdfExecutor @Inject constructor(
    private val store: ObjectStore,
) : Executor {
    override val id = ExecutorId("pdf")
    override val icon = "pdf"

    override fun title(state: ObjectState) =
        if (state.kind == ObjectKind.PDF) "Извлечь текст" else "В PDF"

    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.IMAGE, ObjectKind.TEXT, ObjectKind.PDF)

    override fun produces(state: ObjectState) =
        if (state.kind == ObjectKind.PDF) ObjectState(ObjectKind.TEXT) else ObjectState(ObjectKind.PDF)

    override suspend fun execute(input: PointObject, amendment: String?): ExecutorResult =
        withContext(Dispatchers.IO) {
            runCatching {
                when (input.state.kind) {
                    ObjectKind.IMAGE -> imageToPdf(input)
                    ObjectKind.TEXT -> textToPdf(input)
                    ObjectKind.PDF -> ExecutorResult.Failure(
                        "Извлечение текста из PDF требует библиотеки-парсера — следующий шаг",
                        recoverable = true,
                    )
                    else -> ExecutorResult.Failure("PDF: неподдерживаемый вход", recoverable = false)
                }
            }.getOrElse { ExecutorResult.Failure(it.message ?: "Ошибка PDF", recoverable = true) }
        }

    private suspend fun imageToPdf(input: PointObject): ExecutorResult {
        val bitmap = BitmapFactory.decodeFile(input.uri.value) ?: error("Не удалось прочитать изображение")
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create())
        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
        document.finishPage(page)
        val ref = write(document)
        bitmap.recycle()
        return ExecutorResult.Success(ResultObject(ObjectKind.PDF, "application/pdf", ref))
    }

    private suspend fun textToPdf(input: PointObject): ExecutorResult {
        val text = File(input.uri.value).readText()
        val paint = Paint().apply { textSize = 12f }
        val lines = wrap(text, paint, PAGE_WIDTH - 2 * MARGIN)

        val document = PdfDocument()
        var index = 0
        var pageNumber = 1
        do {
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
        return ExecutorResult.Success(ResultObject(ObjectKind.PDF, "application/pdf", ref))
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

    private companion object {
        const val PAGE_WIDTH = 595   // A4 @72dpi
        const val PAGE_HEIGHT = 842
        const val MARGIN = 32f
        const val LINE_HEIGHT = 16f
    }
}
