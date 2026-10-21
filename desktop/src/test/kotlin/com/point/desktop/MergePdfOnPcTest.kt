package com.point.desktop

import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** «Объединить в PDF» есть и на компьютере (#1379, волна 2): тот же лист, что у телефона. */
class MergePdfOnPcTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun picture(name: String, width: Int, height: Int): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, (x * 7 + y * 13) and 0xFFFFFF)
        return tmp.newFile(name).also { ImageIO.write(image, "png", it) }
    }

    private fun set(vararg files: File): PointObject {
        val manifest = tmp.newFile("Набор.list").apply { writeText(files.joinToString("\n") { it.absolutePath }) }
        return PointObject("set", COLLECTION_MIME, ScratchRef(manifest.absolutePath), ObjectState(ObjectKind.COLLECTION))
    }

    @Test
    fun `картинки набора становятся страницами A4`() = runBlocking {
        val tall = picture("a.png", 300, 400)
        val wide = picture("b.png", 400, 300)
        val text = tmp.newFile("c.txt").apply { writeText("не картинка") }

        val result = PcMergePdfRealizer(PcScratchStore(tmp.newFolder())).perform(set(tall, wide, text), null)

        val made = (result as ActionResult.Success).result
        assertEquals(ObjectKind.PDF, made.type)
        assertEquals("2", made.metadata["pages"])
        PDDocument.load(File(made.uri.value)).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val portrait = pdf.getPage(0).mediaBox
            val landscape = pdf.getPage(1).mediaBox
            assertEquals(com.point.core.flow.A4.width.toFloat(), portrait.width, 0.5f)
            assertEquals(com.point.core.flow.A4.height.toFloat(), portrait.height, 0.5f)
            assertEquals(com.point.core.flow.A4.height.toFloat(), landscape.width, 0.5f)
        }
    }

    @Test
    fun `набор без картинок — честный отказ`() = runBlocking {
        val text = tmp.newFile("c.txt").apply { writeText("не картинка") }
        val other = tmp.newFile("d.txt").apply { writeText("тоже нет") }

        val result = PcMergePdfRealizer(PcScratchStore(tmp.newFolder())).perform(set(text, other), null)

        assertTrue(result is ActionResult.Failure)
    }
}
