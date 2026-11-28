package com.point.desktop

import com.point.core.flow.Cost
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.RasterizedPages
import com.point.core.flow.ReadDocumentCapability
import com.point.core.flow.ReadDocumentRealizer
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.TextRecognizer
import com.point.core.flow.pagesToDraw
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.model.tr
import java.io.File
import java.nio.file.Files

/**
 * Органы компьютера для общего «Прочитать документ» (#1014, #1379).
 *
 * Способность и цикл по страницам — общие с телефоном (`ReadDocumentCapability`,
 * `ReadDocumentRealizer`). Здесь только своё: страницы рисует pdfbox, читает существующее
 * облачное чтение, текст ложится рядом с документом (#995), а слово о цене честное — страницы
 * уйдут в сервис.
 */
fun pcReadDocument(): ReadDocumentCapability = ReadDocumentCapability(
    promise = tr("текст всех страниц · страницы уйдут в сервис"),
    cost = Cost.PAID,
    network = true,
)

fun pcReadDocumentRealizer(readPage: suspend (File) -> String): Realizer = ReadDocumentRealizer(
    rasterizer = PcPdfRasterizer(),
    recognizer = object : TextRecognizer {
        override suspend fun recognize(obj: PointObject): String = readPage(File(obj.uri.value))
    },
    keeper = PcTextBesideDocument,
    meta = RealizerMeta(kind = RealizerKind.CLOUD, actor = com.point.core.flow.OCR_SPACE_ACTOR),
)

/**
 * Страницы PDF картинками — pdfbox, в временную папку. Имена с номером, чтобы порядок страниц
 * совпадал с порядком чтения; папка живёт до выхода.
 *
 * Компьютер рисует все просимые страницы — среза, как у телефона, здесь нет. Но счёт документа
 * называет растеризатор всё равно сам: правило «сколько прочитано из скольких» общее
 * (`pagesRead`), и оно не должно угадывать счёт по папке.
 *
 * Продолжение чтения просит только непрочитанные страницы (карточка «Не отвечать «Ничего не
 * нашлось» по скану, прочитанному не целиком»): страницы, на которых сервис отказал, читаются
 * заново, а прочитанные не рисуются и не уходят в сервис второй раз.
 */
class PcPdfRasterizer(private val dpi: Float = PAGE_DPI) : PdfRasterizer {

    override suspend fun rasterize(obj: PointObject): RasterizedPages = rasterize(obj, except = emptySet())

    override suspend fun rasterize(obj: PointObject, except: Set<Int>): RasterizedPages {
        val source = File(obj.uri.value).takeIf(File::isFile)
            ?: error(tr("Файла документа нет на диске"))
        val dir = Files.createTempDirectory("pages-").toFile().apply { deleteOnExit() }
        var drawn = emptyList<Int>()
        val total = org.apache.pdfbox.pdmodel.PDDocument.load(source).use { document ->
            val renderer = org.apache.pdfbox.rendering.PDFRenderer(document)
            drawn = pagesToDraw(except, document.numberOfPages)
            for (number in drawn) {

                // Имя — настоящий номер страницы в документе, а не номер в этом заходе.
                val page = File(dir, "page-%04d.png".format(number)).apply { deleteOnExit() }
                javax.imageio.ImageIO.write(renderer.renderImageWithDPI(number - 1, dpi), "png", page)
            }
            document.numberOfPages
        }
        return RasterizedPages(ScratchRef(dir.absolutePath), total, numbers = drawn)
    }

    override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? {
        val source = File(obj.uri.value).takeIf(File::isFile) ?: return null
        return runCatching {
            org.apache.pdfbox.pdmodel.PDDocument.load(source).use { document ->
                if (document.numberOfPages == 0) return null
                val page = File.createTempFile("page-", ".png").apply { deleteOnExit() }
                javax.imageio.ImageIO.write(
                    org.apache.pdfbox.rendering.PDFRenderer(document).renderImageWithDPI(0, dpi),
                    "png",
                    page,
                )
                ScratchRef(page.absolutePath)
            }
        }.getOrNull()
    }

    private companion object {
        const val PAGE_DPI = 200f
    }
}
