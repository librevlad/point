package com.point.data

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OoxmlOfficeTextExtractorTest {

    private val extractor = OoxmlOfficeTextExtractor()

    private fun docx(documentXml: String): PointObject {
        val file = File.createTempFile("point-", ".docx").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(documentXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return PointObject(
            id = "id",
            mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.OFFICE),
        )
    }

    private fun pptx(vararg slideXml: String): PointObject =
        pptxOf(*slideXml.mapIndexed { index, xml -> (index + 1) to xml }.toTypedArray())

    /** Презентация, у которой номер слайда задан отдельно от места в архиве. */
    private fun pptxOf(vararg slides: Pair<Int, String>): PointObject {
        val file = File.createTempFile("point-", ".pptx").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zos ->
            slides.forEach { (number, xml) ->
                zos.putNextEntry(ZipEntry("ppt/slides/slide$number.xml"))
                zos.write(xml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return PointObject(
            id = "id",
            mime = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.OFFICE),
        )
    }

    private fun slide(text: String) = "<p:sld><p:cSld><p:spTree><a:t>$text</a:t></p:spTree></p:cSld></p:sld>"

    @Test
    fun `текст презентации читается со слайдов, а не теряется`() = runTest {
        val obj = pptx(
            "<p:sld><p:cSld><p:spTree><a:t>Квартальный отчёт</a:t></p:spTree></p:cSld></p:sld>",
            "<p:sld><p:cSld><p:spTree><a:t>Выручка выросла</a:t><a:t>на 20%</a:t></p:spTree></p:cSld></p:sld>",
        )

        val text = extractor.extractText(obj)

        assertTrue(text, text.contains("Квартальный отчёт"))
        assertTrue(text, text.contains("Выручка выросла"))
        assertTrue(text, text.contains("на 20%"))
    }

    /**
     * Слайды приходят по отдельности, а не одной кучей (#1105): читатель у документа один, и
     * тот, кого зовёт телефон, обязан отдавать части, а не пустоту. Номер идёт вместе с
     * текстом слайда — он принадлежит самому слайду, а не месту в списке.
     */
    @Test
    fun `слайды презентации приходят частями, каждая со своим номером и текстом`() = runTest {
        val obj = pptx(slide("Сумма 12 500 грн"), slide("Олена Ковальчук"))

        val slides = extractor.slides(obj)

        assertEquals(listOf(1, 2), slides.map { it.first })
        assertTrue(slides.toString(), "12 500" in slides[0].second && "12 500" !in slides[1].second)
        assertTrue(slides.toString(), "Ковальчук" in slides[1].second && "Ковальчук" !in slides[0].second)
    }

    /**
     * Список слайдов собирается из тех, что в файле нашлись (#1105).
     *
     * Point принимает произвольный чужой файл из «Поделиться». Номер слайда брался из имени
     * части архива, и список строился сплошным рядом до самого большого номера: крошечный
     * файл с единственной частью `slide2000000000.xml` по одному тапу человека выкладывал
     * два миллиарда строк — памяти телефону не хватало раньше, чем он что-нибудь показывал.
     */
    @Test
    fun `у слайда с огромным номером находится один слайд, а не ряд до него`() = runTest {
        val obj = pptxOf(2_000_000_000 to slide("единственный"))

        assertEquals(listOf(2_000_000_000 to "единственный"), extractor.slides(obj))
    }

    /**
     * Слайд — часть со своим номером, а не место в архиве (#1105): `slide10.xml` лежит там
     * раньше `slide2.xml`, и текст презентации выходил вперемешку.
     */
    @Test
    fun `текст презентации идёт по номерам слайдов, а не по порядку частей архива`() = runTest {
        val obj = pptxOf(10 to slide("десятый"), 2 to slide("второй"), 1 to slide("первый"))

        val text = extractor.extractText(obj)

        assertEquals(listOf("первый", "второй", "десятый"), text.lines())
    }

    /** У документа, который на части не раскладывается, их и нет — пустой список, не текст. */
    @Test
    fun `у обычного документа слайдов нет`() = runTest {
        val obj = docx("<w:body><w:t>Акт выполненных работ</w:t></w:body>")

        assertEquals(emptyList<Pair<Int, String>>(), extractor.slides(obj))
    }

    @Test
    fun `extracts text from a single run`() = runTest {
        val obj = docx("<w:document><w:body><w:p><w:r><w:t>Привет из документа</w:t></w:r></w:p></w:body></w:document>")
        assertEquals("Привет из документа", extractor.extractText(obj))
    }

    @Test
    fun `joins multiple runs and unescapes entities`() = runTest {
        val obj = docx(
            "<w:body>" +
                "<w:t>Раз</w:t>" +
                "<w:t xml:space=\"preserve\">Два &amp; три</w:t>" +
                "</w:body>",
        )
        val text = extractor.extractText(obj)
        assertTrue(text.contains("Раз"))
        assertTrue(text.contains("Два & три"))
    }

    @Test
    fun `non-ooxml (legacy binary) yields empty`() = runTest {
        val file = File.createTempFile("point-", ".doc").apply {
            writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()))
            deleteOnExit()
        }
        val obj = PointObject("id", "application/msword", ScratchRef(file.absolutePath), ObjectState(ObjectKind.OFFICE))
        assertEquals("", extractor.extractText(obj))
    }

    @Test
    fun `numeric entities become real letters, not mojibake`() = runTest {
        val obj = docx(
            "<w:body>" +
                "<w:t>&#1053;&#1072;&#1082;&#1083;&#1072;&#1076;&#1085;&#1072;&#1103;</w:t>" +
                "<w:t>&#x41F;&#x43E;&#x447;&#x442;&#x430;</w:t>" +
                "</w:body>",
        )

        val text = extractor.extractText(obj)

        assertTrue(text.contains("Накладная"))
        assertTrue(text.contains("Почта"))
        assertTrue("сырых ссылок остаться не должно", !text.contains("&#"))
    }

    @Test
    fun `an escaped ampersand does not turn into a letter`() = runTest {

        val obj = docx("<w:body><w:t>&amp;#1053;</w:t></w:body>")

        assertEquals("&#1053;", extractor.extractText(obj))
    }

    @Test
    fun `an impossible code point is left as written`() = runTest {
        val obj = docx("<w:body><w:t>&#99999999;</w:t></w:body>")

        assertEquals("&#99999999;", extractor.extractText(obj))
    }
}
