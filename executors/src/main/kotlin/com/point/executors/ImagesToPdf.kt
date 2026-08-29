package com.point.executors

import android.graphics.Bitmap
import android.graphics.RectF
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
        document.addPage(bitmap, pages + 1)
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
 * Снимок страницей документа: на листе, а не на матрице камеры, и сжатый по тому, что на
 * нём (#1047).
 *
 * Одно место на все PDF из снимков — и на набор страниц, и на одиночный снимок: человек
 * отправляет один и тот же документ, каким бы действием он его ни собрал.
 */
internal fun PdfDocument.addPage(bitmap: Bitmap, number: Int) {
    val sheet = sheetFor(bitmap.width, bitmap.height)
    val fitted = fittedToSheet(bitmap, sheet)
    val box = sheet.boxFor(fitted.width, fitted.height)
    val page = startPage(PdfDocument.PageInfo.Builder(sheet.width, sheet.height, number).create())
    page.canvas.drawBitmap(fitted, null, RectF(box.left, box.top, box.right, box.bottom), null)
    finishPage(page)
    if (fitted !== bitmap) fitted.recycle()
}

/**
 * Страница под лист: чёткость и оттенки — по тому, что на ней самой (#1047).
 *
 * Чёрно-белую страницу отдаём как есть, и это замер, а не догадка: на снимке документа
 * ужатие бинаризованной страницы до предела листа меняет её вес на −2 %…+3 % и добавляет
 * буквам серые края. Цветную ужимаем до чёткости листа (150 dpi) и округляем ей оттенки —
 * снимок с шумом матрицы иначе едет в PDF почти несжатым.
 *
 * Лишней памяти страница при этом не берёт: оттенки округляются на месте, в том же массиве
 * пикселей, — второй такой же стоил бы ещё ~9 МБ на страницу, а `PdfDocument` держит все
 * собранные страницы разом.
 */
private fun fittedToSheet(bitmap: Bitmap, sheet: Sheet): Bitmap {
    if (inkOnPaper(rowSample(bitmap))) return bitmap

    val longEdge = maxOf(bitmap.width, bitmap.height)
    val maxPx = sheet.pageMaxPx()
    val scaled = if (longEdge <= maxPx) {
        bitmap
    } else {
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width.toLong() * maxPx / longEdge).toInt().coerceAtLeast(1),
            (bitmap.height.toLong() * maxPx / longEdge).toInt().coerceAtLeast(1),
            true,
        )
    }

    val pixels = IntArray(scaled.width * scaled.height)
    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    fewerTones(pixels)
    val toned = Bitmap.createBitmap(pixels, scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
    if (scaled !== bitmap) scaled.recycle()
    return toned
}

/**
 * Несколько строк страницы поперёк всего снимка: по ним видно, краска это на бумаге или
 * цветная печать. Читать ради этого весь снимок незачем — разные цвета встретятся сразу.
 */
private fun rowSample(bitmap: Bitmap): IntArray {
    val rows = minOf(SAMPLE_ROWS, bitmap.height)
    val step = bitmap.height / rows
    val sample = IntArray(rows * bitmap.width)
    for (row in 0 until rows) {
        bitmap.getPixels(sample, row * bitmap.width, bitmap.width, 0, row * step, bitmap.width, 1)
    }
    return sample
}

private const val SAMPLE_ROWS = 32

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
