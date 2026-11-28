package com.point.desktop

import com.point.core.flow.META_WHOLE_FRAME
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Скан страницы на компьютере — тем же зрением, что на телефоне (#1379, #1333).
 *
 * На телефоне нативную OpenCV в JVM-тестах не поднять, а здесь она своя: конвейер исполняется
 * по-настоящему, на кадре, где лист лежит наискось на тёмном столе.
 */
class ScanOnPcTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun openCv() = assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)

    /** Лист со строками, повёрнутый на 8°, на тёмном столе. */
    private fun sheetOnDesk(): File {
        val image = BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(60, 50, 40)
            fillRect(0, 0, 1200, 900)
            transform = AffineTransform.getRotateInstance(Math.toRadians(8.0), 600.0, 450.0)
            color = Color(235, 235, 230)
            fillRect(300, 120, 600, 700)
            color = Color(20, 20, 20)
            for (i in 0 until 20) fillRect(340, 160 + i * 30, 400 + (i % 3) * 50, 8)
            dispose()
        }
        return tmp.newFile("лист-${System.nanoTime()}.png").also { ImageIO.write(image, "png", it) }
    }

    /** Ровная серая заливка: листа на ней нет. */
    private fun blank(): File {
        val image = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = Color(128, 128, 128); fillRect(0, 0, 400, 300); dispose() }
        return tmp.newFile("пусто.png").also { ImageIO.write(image, "png", it) }
    }

    private fun photo(file: File) = PointObject("p", "image/png", ScratchRef(file.absolutePath), ObjectState(ObjectKind.IMAGE))

    private fun scan() = PcPageScanRealizer(com.point.core.flow.ScanCapability.ID, "scan", PcScratchStore(tmp.newFolder()))

    @Test fun `лист наискось выпрямляется в страницу, а не отдаётся кадром`() = runBlocking {
        val result = scan().perform(photo(sheetOnDesk()), null)

        val made = (result as ActionResult.Success).result
        assertNull("выпрямленная страница помечена как кадр целиком", made.metadata[META_WHOLE_FRAME])
        val page = ImageIO.read(File(made.uri.value))
        assertTrue("лист стоит, а вышла ${page.width}×${page.height}", page.height > page.width)
    }

    @Test fun `листа не нашли — кадр обработан целиком и так и назван`() = runBlocking {
        val result = scan().perform(photo(blank()), null)

        val made = (result as ActionResult.Success).result
        assertTrue("кадр целиком выдан за страницу", made.metadata[META_WHOLE_FRAME] != null)
    }

    /** Лист во весь кадр: рамка таблицы на нём, а под рамкой — строка подписи. */
    private fun sheetWithPrintedFrame(): BufferedImage = BufferedImage(1200, 1400, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            color = Color(240, 240, 236)
            fillRect(0, 0, 1200, 1400)
            color = Color.BLACK
            stroke = java.awt.BasicStroke(4f)
            drawRect(150, 180, 900, 1000)
            font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 24)
            for (i in 0 until 22) drawString("Received in full, invoice 4521, 18.07.2026", 190, 260 + i * 40)
            drawString("Подпись получателя ________  18.07.2026", 190, 1310)
            dispose()
        }
    }

    @Test fun `страница PDF не обрезается по рамке таблицы на листе`() {
        // Скан в PDF ищет лист той же дорогой, что «Скан» (DOC-1, 28.11.2026): прежде он шёл
        // урезанной, и лист во весь кадр уходил в PDF обрезанным по рамке таблицы — без подписи.
        val page = onFrame(sheetWithPrintedFrame()) { rgba, scratch ->
            com.point.vision.PageVision.process(rgba, scratch)?.let { it.cols() to it.rows() }
        }

        if (page != null) {
            val (w, h) = page
            assertTrue("страница PDF обрезана по рамке таблицы: $w×$h", minOf(w, h) >= 1150)
        }
    }

    @Test fun `снимки набора — страницами одного PDF`() = runBlocking {
        val pages = listOf(sheetOnDesk(), sheetOnDesk()).map { it.absolutePath }
        val set = PointObject("set", "application/x-collection", ScratchRef(tmp.root.absolutePath), ObjectState(ObjectKind.COLLECTION))

        val result = PcScanPdfRealizer(PcScratchStore(tmp.newFolder()), children = { pages }).perform(set, null)

        val made = (result as ActionResult.Success).result
        assertEquals(ObjectKind.PDF, made.type)
        assertEquals("2", made.metadata["pages"])
    }
}
