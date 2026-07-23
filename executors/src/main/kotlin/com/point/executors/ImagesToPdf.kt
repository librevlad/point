package com.point.executors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import com.point.core.flow.ObjectStore
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ResultObject
import java.io.File

/**
 * Shared assembly for the two "collection of images → one PDF" actions: every
 * image file under [dir] becomes one page, in name order. [clean] optionally
 * rewrites each page's ARGB pixels (the scan filter) before it is drawn — that is
 * the *only* difference between "Объединить в PDF" (raw) and "Сканировать в PDF"
 * (cleaned). Non-images are skipped; an empty result is a recoverable failure.
 *
 * Meant to run inside a Dispatchers.IO context (both realizers switch to it).
 */
internal suspend fun imagesToPdf(
    store: ObjectStore,
    dir: File,
    name: String,
    op: String,
    clean: ((IntArray) -> IntArray)? = null,
): ActionResult {
    val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.name.lowercase() }.toList()

    val document = PdfDocument()
    var pages = 0
    for (file in files) {
        val src = BitmapFactory.decodeFile(file.absolutePath) ?: continue // skip non-images
        val bitmap = if (clean == null) {
            src
        } else {
            val width = src.width
            val height = src.height
            val pixels = IntArray(width * height)
            src.getPixels(pixels, 0, width, 0, 0, width, height)
            Bitmap.createBitmap(clean(pixels), width, height, Bitmap.Config.ARGB_8888)
                .also { src.recycle() }
        }
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pages + 1).create(),
        )
        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
        document.finishPage(page)
        bitmap.recycle()
        pages++
    }

    if (pages == 0) {
        document.close()
        return ActionResult.Failure("В коллекции нет изображений для PDF", recoverable = true)
    }

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
