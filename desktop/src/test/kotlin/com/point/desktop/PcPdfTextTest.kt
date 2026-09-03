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

    /** Многостраничный документ: у каждой страницы свой текст. */
    private fun pdfOfPages(name: String, pages: List<String>): File {
        val file = temp.newFile(name)
        PDDocument().use { doc ->
            pages.forEach { text ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { out ->
                    out.beginText()
                    out.setFont(PDType1Font.HELVETICA, 12f)
                    out.newLineAtOffset(50f, 700f)
                    out.showText(text)
                    out.endText()
                }
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

    /** Текст ложится знанием на сам документ — второго объекта не появляется (#995). */
    @Test
    fun `компьютер отдаёт текст самому документу — тот же результат, что на телефоне`() = runTest {
        val file = pdfWithText("Invoice 4512 total 320")

        val result = PcPdfTextRealizer(PdfBoxText()).perform(pdfObject(file), null)

        assertTrue("ожидалось знание документу, вышло: $result", result is ActionResult.Done)
        val found = (result as ActionResult.Done).findings
        assertTrue(Feature.HAS_TEXT in found!!.features)
        val kept = found.metadata[com.point.core.flow.META_OCR_TEXT_REF]!!
        assertTrue(File(kept).readText().contains("Invoice 4512 total 320"))
    }

    /**
     * Слой, который нельзя прочитать, текстовым слоем не является (#933, #995): такой PDF
     * метится сканом при приёме, и человеку рисуется дверь «Прочитать документ».
     */
    @Test
    fun `PDF с подменённой раскладкой шрифта тоже метится сканом`() {
        val inbox = Inbox(temp.newFolder("раскладка"))

        val mangled = inbox.addFile(pdfWithText(GARBLED).absolutePath)

        assertTrue("мусор из слоя снова сойдёт за текст", mangled.obj.state.has(Feature.IS_IMAGE_PDF))
    }

    /** Совет называет шаг, который у документа есть (#1257) — и слова те же, что на телефоне. */
    @Test
    fun `отказ зовёт чтение документа, а не два действия`() {
        val said = com.point.core.flow.capabilities.NO_READABLE_PDF_LAYER

        assertTrue(said, pcReadDocument().label(ObjectState(ObjectKind.PDF)) in said)
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

    /**
     * Отказ зовёт дверь, которая у документа есть на самом деле (#995, #1257).
     *
     * Приём судил документ по первым трём страницам, а исполнитель — по всему файлу: у счёта
     * с читаемым началом и подменённой раскладкой дальше приём отвечал «не скан», рисовалась
     * быстрая дверь, «Извлечь текст» по нажатию отказывало и звало «Прочитать документ» —
     * а этого действия рядом не было. Улика теперь одна: весь документ, как и на телефоне.
     */
    @Test
    fun `у документа с читаемым началом и подменённой раскладкой дальше дверь чтения на месте`() {
        val trap = pdfOfPages("счёт.pdf", List(3) { READABLE_PAGE } + List(4) { GARBLED })

        val item = Inbox(temp.newFolder("ловушка")).addFile(trap.absolutePath)

        assertTrue(
            "документ, из которого текст файлом не достаётся, таким не назван",
            item.obj.state.has(Feature.IS_IMAGE_PDF),
        )
        assertFalse(
            "быстрая дверь обещает текст, которого в файле нет",
            PcPdfTextRealizer(PdfBoxText()).accepts(item.obj.state),
        )
        assertTrue(
            "дверь, которую называет отказ, у документа отсутствует",
            pcReadDocument().accepts(item.obj.state),
        )
    }

    @Test
    fun `нечитаемый PDF — честный отказ, а не пустой текст`() = runTest {
        val broken = temp.newFile("битый.pdf").apply { writeText("не pdf вовсе") }

        val result = PcPdfTextRealizer(PdfBoxText()).perform(pdfObject(broken), null)

        assertTrue(result is ActionResult.Failure)
    }

    private companion object {

        /** Страница, которая читается: первые страницы счёта из корпуса владельца в порядке. */
        const val READABLE_PAGE = "Invoice for repair works total amount"

        /** Слой украинского бухгалтерского PDF с подменённой раскладкой шрифта (#933). */
        const val GARBLED =
            "ToeapucrBo 3 o6MexeHop eignoeiganbHicrlo BaxraxoorpxMyBaq cKnaAaHHR " +
                "flocraqanbHHK e.qPnov Eniqgxtp 3aMoBHHK PaxyHok-cbakrypa"
    }
}
