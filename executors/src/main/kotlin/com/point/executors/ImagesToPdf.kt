package com.point.executors

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import com.point.core.flow.ObjectStore
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ResultObject
import java.io.File

/**
 * Shared assembly for the two "collection of images → one PDF" actions: every
 * image file under [dir] becomes one page, in name order. [process] optionally
 * transforms each decoded page before it is drawn — identity for "Объединить в PDF"
 * (raw), and the OpenCV/Otsu [scanPage] for "Сканировать в PDF" (deskew + clean).
 * Non-images are skipped; an empty result is a recoverable failure.
 *
 * Meant to run inside a Dispatchers.IO context (both realizers switch to it).
 */
internal suspend fun imagesToPdf(
    store: ObjectStore,
    dir: File,
    name: String,
    op: String,
    process: (Bitmap) -> Bitmap = { it },
): ActionResult {
    val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.name.lowercase() }.toList()

    val document = PdfDocument()
    var pages = 0
    for (file in files) {
        val src = Bitmaps.decodeUpright(file.absolutePath) ?: continue // skip non-images
        val bitmap = process(src)
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pages + 1).create(),
        )
        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
        document.finishPage(page)
        bitmap.recycle()
        if (bitmap !== src) src.recycle() // process produced a new bitmap → free the source too
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

/**
 * Per-page transform for "Сканировать в PDF": the OpenCV document scan (detect → perspective →
 * adaptive threshold) when the pack is available — so a batch of angled photos comes out
 * deskewed, exactly like the single "Скан" (#45) — otherwise the pure Otsu [ScanFilter]. A
 * per-page OpenCV failure falls back to the filter, so one hard photo never fails the whole PDF.
 */
internal fun scanPage(src: Bitmap): Bitmap =
    if (OpenCvScan.available) {
        runCatching { OpenCvScan.process(src) }.getOrElse { scanFilterPage(src) }
    } else {
        scanFilterPage(src)
    }

/** Grayscale + Otsu on the raw pixels — the local, dependency-free scan (same size in, same out). */
private fun scanFilterPage(src: Bitmap): Bitmap {
    val width = src.width
    val height = src.height
    val pixels = IntArray(width * height)
    src.getPixels(pixels, 0, width, 0, 0, width, height)
    return Bitmap.createBitmap(ScanFilter.apply(pixels), width, height, Bitmap.Config.ARGB_8888)
}
