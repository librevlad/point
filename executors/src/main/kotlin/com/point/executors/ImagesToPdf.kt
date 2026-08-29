package com.point.executors

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import com.point.core.flow.ObjectStore
import com.point.core.flow.META_WHOLE_FRAME
import com.point.core.flow.collectionOrder
import com.point.core.flow.inCollectionOrder
import com.point.core.flow.reportStage
import com.point.core.flow.wholeFrameNote
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import java.io.File

/**
 * Файлы набора в порядке страниц (#1207): как велит знание набора (`collection.order`), а
 * те, о которых знания нет, — за ними по имени. Одна функция для «Сканировать в PDF» и
 * «Объединить в PDF»: порядок читается из самого объекта-набора, а не из имён.
 */
internal fun pagesOf(collection: PointObject): List<File> =
    inCollectionOrder(
        File(collection.uri.value).walkTopDown().filter { it.isFile }.toList(),
        collectionOrder(collection.metadata),
    ) { it.name }

internal suspend fun imagesToPdf(
    store: ObjectStore,
    collection: PointObject,
    name: String,
    op: String,

    /** Выпрямленная страница или `null` — страницы на снимке не нашли (#1333). */
    straighten: (Bitmap) -> Bitmap? = { it },

    /**
     * Кадр целиком — когда страницы не нашли (#1333).
     *
     * Работа при этом не пропадает: снимок уже обрезанного листа страницей не считается, а
     * выбелить и свести к чёрно-белому его всё равно стоит. Но за выпрямленную страницу
     * такой кадр не выдаётся — о нём говорит пометка результата.
     */
    wholeFrame: (Bitmap) -> Bitmap = { it },
): ActionResult {
    val files = pagesOf(collection)

    val document = PdfDocument()
    var pages = 0
    var wholeFrames = 0
    for (file in files) {
        val src = Bitmaps.decodeUpright(file.absolutePath) ?: continue
        reportStage("Страница ${pages + 1}")
        val straight = straighten(src)
        if (straight == null) wholeFrames++
        val bitmap = straight ?: wholeFrame(src)
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

            // Кадры, на которых страницы не нашли, названы прямо на результате (#1333):
            // прежде они уезжали страницами скана молча, и человеку это было неотличимо от
            // выпрямленных страниц.
            mapOf("op" to op, "pages" to pages.toString(), "name" to name) + listOfNotNull(
                wholeFrameNote(wholeFrames, pages)?.let { META_WHOLE_FRAME to it },
            ),
        ),
    )
}

/**
 * Страница скана из снимка (#1333): `null` — страницы на снимке не нашли.
 *
 * Правило то же, что у «Скана» поодиночке: ненайденная страница исходным кадром не
 * подменяется. Прежде здесь стояло `detectDocument(...) ?: rgba`, и неисправленный снимок
 * уезжал страницей PDF как настоящая выпрямленная страница.
 */
internal fun scanPage(src: Bitmap): Bitmap? =
    if (OpenCvScan.available) {
        runCatching { OpenCvScan.process(src) }.getOrElse { scanFilterPage(src) }
    } else {

        // Своим зрением страницу здесь никто не искал, и сказать «не нашли» было бы
        // неправдой: «не исследовано» ≠ «не найдено». Кадр сводится к чёрно-белому, как и был.
        scanFilterPage(src)
    }

/** Кадр целиком, когда страницы не нашли (#1333): бумагу всё равно видно лучше. */
internal fun wholeFramePage(src: Bitmap): Bitmap =
    if (OpenCvScan.available) {
        runCatching { OpenCvScan.processAsIs(src) }.getOrNull() ?: scanFilterPage(src)
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
