package com.point.desktop

import com.point.core.flow.Cost
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.ReadDocumentCapability
import com.point.core.flow.ReadDocumentRealizer
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.TextRecognizer
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
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
    promise = "текст всех страниц · страницы уйдут в сервис",
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
 */
class PcPdfRasterizer(private val dpi: Float = PAGE_DPI) : PdfRasterizer {

    override suspend fun rasterize(obj: PointObject): ScratchRef {
        val source = File(obj.uri.value).takeIf(File::isFile)
            ?: error("Файла документа нет на диске")
        val dir = Files.createTempDirectory("pages-").toFile().apply { deleteOnExit() }
        org.apache.pdfbox.pdmodel.PDDocument.load(source).use { document ->
            val renderer = org.apache.pdfbox.rendering.PDFRenderer(document)
            for (index in 0 until document.numberOfPages) {
                val page = File(dir, "page-%04d.png".format(index + 1)).apply { deleteOnExit() }
                javax.imageio.ImageIO.write(renderer.renderImageWithDPI(index, dpi), "png", page)
            }
        }
        return ScratchRef(dir.absolutePath)
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
