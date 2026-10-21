package com.point.desktop

import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.boxFor
import com.point.core.flow.fewerTones
import com.point.core.flow.inkOnPaper
import com.point.core.flow.pageMaxPx
import com.point.core.flow.reportStage
import com.point.core.flow.sheetFor
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * «Объединить в PDF» на компьютере (#1379, волна 2): картинки набора — страницами PDF.
 *
 * Телефон собирает их Android `PdfDocument`, здесь — pdfbox. Лист и место страницы на нём те
 * же ([sheetFor], [boxFor]): документ, собранный на любом устройстве, печатается одинаково.
 * Сжатие — тем же правилом: краска на бумаге остаётся как есть, цветная страница ужимается
 * до чёткости листа и теряет шум оттенков.
 *
 * [children] — файлы набора: у компьютера набор — манифест путей, а не папка (#1099).
 */
class PcMergePdfRealizer(
    private val store: ObjectStore,
    private val children: (PointObject) -> List<String> = ::collectionChildren,
) : Realizer {
    override val capabilityId = com.point.core.flow.MergePdfCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching { merge(input) }
                .getOrElse { ActionResult.Failure("Собрать PDF не вышло — попробуйте ещё раз или соберите на телефоне", recoverable = true) }
        }

    private suspend fun merge(input: PointObject): ActionResult {
        val files = com.point.core.flow.inCollectionOrder(
            children(input).map(::File).filter(File::isFile),
            com.point.core.flow.collectionOrder(input.metadata),
        ) { it.name }

        var pages = 0
        return PDDocument().use { document ->
            for (file in files) {
                val image = runCatching { ImageIO.read(file) }.getOrNull() ?: continue
                reportStage("Страница ${pages + 1}")
                document.addPicture(image)
                pages++
            }
            if (pages == 0) return ActionResult.Failure(NO_PICTURES, recoverable = true)

            reportStage("Собираю PDF")
            val ref = store.newScratchFile("pdf")
            document.save(File(ref.value))
            ActionResult.Success(
                ResultObject(
                    ObjectKind.PDF,
                    "application/pdf",
                    ref,
                    mapOf("op" to "merge-pdf", "pages" to pages.toString(), "name" to com.point.core.flow.MERGED_PDF_NAME),
                ),
            )
        }
    }

    private fun PDDocument.addPicture(source: BufferedImage) {
        val sheet = sheetFor(source.width, source.height)
        val fitted = fitted(source, sheet.pageMaxPx())
        val box = sheet.boxFor(fitted.width, fitted.height)
        val page = PDPage(PDRectangle(sheet.width.toFloat(), sheet.height.toFloat()))
        addPage(page)
        val picture = LosslessFactory.createFromImage(this, fitted)

        // У PDF начало координат внизу листа, у [boxFor] — вверху, как у снимка.
        PDPageContentStream(this, page).use { content ->
            content.drawImage(
                picture,
                box.left,
                sheet.height - box.bottom,
                box.right - box.left,
                box.bottom - box.top,
            )
        }
    }

    /** Страница под лист — то же правило, что у телефона (#1047): краску не трогаем. */
    private fun fitted(source: BufferedImage, maxPx: Int): BufferedImage {
        val rgb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            color = java.awt.Color.WHITE
            fillRect(0, 0, source.width, source.height)
            drawImage(source, 0, 0, null)
            dispose()
        }
        val sample = rgb.getRGB(0, 0, rgb.width, rgb.height, null, 0, rgb.width)
        if (inkOnPaper(sample)) return rgb

        val scale = minOf(1.0, maxPx.toDouble() / maxOf(rgb.width, rgb.height))
        val width = (rgb.width * scale).toInt().coerceAtLeast(1)
        val height = (rgb.height * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(rgb, 0, 0, width, height, null)
            dispose()
        }
        val pixels = out.getRGB(0, 0, width, height, null, 0, width)
        fewerTones(pixels)
        out.setRGB(0, 0, width, height, pixels, 0, width)
        return out
    }

    private companion object {

        const val NO_PICTURES = "В наборе нет картинок — добавьте снимки, и PDF соберётся"
    }
}
