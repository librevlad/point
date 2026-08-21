package com.point.executors

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import com.point.core.flow.ObjectStore
import com.point.core.flow.inCollectionOrder
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ResultObject
import java.io.File

internal suspend fun imagesToPdf(
    store: ObjectStore,
    dir: File,
    name: String,
    op: String,
    process: (Bitmap) -> Bitmap = { it },

    // Порядок страниц — знание набора, имя файла — запасной порядок (#1207).
    order: List<String> = emptyList(),
): ActionResult {
    val files = inCollectionOrder(dir.walkTopDown().filter { it.isFile }.toList(), order) { it.name }

    val document = PdfDocument()
    var pages = 0
    for (file in files) {
        val src = Bitmaps.decodeUpright(file.absolutePath) ?: continue
        reportStage("Страница ${pages + 1}")
        val bitmap = process(src)
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pages + 1).create(),
        )
        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
        document.finishPage(page)
        bitmap.recycle()
        if (bitmap !== src) src.recycle()
        pages++
    }

    if (pages == 0) {
        document.close()
        return ActionResult.Failure("В коллекции нет изображений для PDF", recoverable = true)
    }

    reportStage("Собираю PDF")
    val ref = store.newScratchFile("pdf")
    File(ref.value).outputStream().use { document.writeTo(it) }
    document.close()
    return ActionResult.Success(
        ResultObject(
            ObjectKind.PDF,
            "application/pdf",
            ref,
            mapOf("op" to op, "pages" to pages.toString(), "name" to name),
        ),
    )
}

internal fun scanPage(src: Bitmap): Bitmap =
    if (OpenCvScan.available) {
        runCatching { OpenCvScan.process(src) }.getOrElse { scanFilterPage(src) }
    } else {
        scanFilterPage(src)
    }

private fun scanFilterPage(src: Bitmap): Bitmap {
    val width = src.width
    val height = src.height
    val pixels = IntArray(width * height)
    src.getPixels(pixels, 0, width, 0, 0, width, height)
    return Bitmap.createBitmap(ScanFilter.apply(pixels), width, height, Bitmap.Config.ARGB_8888)
}
