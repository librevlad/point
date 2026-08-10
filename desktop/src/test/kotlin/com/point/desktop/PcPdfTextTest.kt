package com.point.desktop

import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Телефон читает текст из PDF, компьютер — нет: объект, приехавший на компьютер, давал
 * человеку меньше, чем он имел в руке (#631). PDF собирается настоящий, тем же PDFBox, —
 * выдуманные байты ничего не доказали бы.
 */
class PcPdfTextTest {

    @get:Rule val temp = TemporaryFolder()

    private fun pdfWithText(text: String): File {
        val file = temp.newFile("документ.pdf")
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { out ->
                out.beginText()
                out.setFont(PDType1Font.HELVETICA, 12f)
                out.newLineAtOffset(50f, 700f)
                out.showText(text)
                out.endText()
            }
            doc.save(file)
        }
        return file
    }

    private fun pdfWithoutText(): File {
        val file = temp.newFile("скан.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.save(file)
        }
        return file
    }

    private fun pdfObject(file: File, state: ObjectState = ObjectState(ObjectKind.PDF)) =
        PointObject("pdf", "application/pdf", ScratchRef(file.absolutePath), state)

    @Test
    fun `компьютер извлекает текст из PDF — тот же результат, что на телефоне`() = runTest {
        val file = pdfWithText("Invoice 4512 total 320")

        val result = PcPdfTextRealizer(PdfBoxText()).perform(pdfObject(file), null)

        assertTrue("ожидался объект с текстом, вышло: $result", result is ActionResult.Success)
        val produced = (result as ActionResult.Success).result
        assertEquals(ObjectKind.TEXT, produced.type)
        assertTrue(File(produced.uri.value).readText().contains("Invoice 4512 total 320"))
    }

    @Test
    fun `скан не доходит до действия — дверь ему не рисуется`() {
        val realizer = PcPdfTextRealizer(PdfBoxText())

        assertTrue(realizer.accepts(ObjectState(ObjectKind.PDF)))
        assertFalse(
            "у скана текста нет — предлагать «Извлечь текст» нечестно",
            realizer.accepts(ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF))),
        )
        assertFalse(realizer.accepts(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `PDF без текстового слоя узнаётся при приёме и метится сканом`() {
        val inbox = Inbox(temp.newFolder("inbox"))

        val scan = inbox.addFile(pdfWithoutText().absolutePath)
        val real = inbox.addFile(pdfWithText("Lease agreement 2026").absolutePath)

        assertTrue("скан обязан быть назван сканом", scan.obj.state.has(Feature.IS_IMAGE_PDF))
        assertFalse("документ с текстом сканом не является", real.obj.state.has(Feature.IS_IMAGE_PDF))
    }

    @Test
    fun `нечитаемый PDF — честный отказ, а не пустой текст`() = runTest {
        val broken = temp.newFile("битый.pdf").apply { writeText("не pdf вовсе") }

        val result = PcPdfTextRealizer(PdfBoxText()).perform(pdfObject(broken), null)

        assertTrue(result is ActionResult.Failure)
    }
}
