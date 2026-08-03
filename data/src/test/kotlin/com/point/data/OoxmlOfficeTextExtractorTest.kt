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

/** Pure JVM: build a minimal docx (a zip) in memory and extract its text. */
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

    /** Презентация — тот же OOXML-zip, только текст живёт в слайдах (#403). */
    private fun pptx(vararg slideXml: String): PointObject {
        val file = File.createTempFile("point-", ".pptx").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zos ->
            slideXml.forEachIndexed { index, xml ->
                zos.putNextEntry(ZipEntry("ppt/slides/slide${index + 1}.xml"))
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
            writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())) // OLE header, not a zip
            deleteOnExit()
        }
        val obj = PointObject("id", "application/msword", ScratchRef(file.absolutePath), ObjectState(ObjectKind.OFFICE))
        assertEquals("", extractor.extractText(obj))
    }

    /** Жалоба владельца «word в pdf даёт кракозябры» (#289): Word пишет кириллицу числовыми
     *  ссылками, когда документ прошёл через чужой редактор, — пять именованных сущностей их
     *  не знали, и «&#1053;&#1077;…» доезжало до экрана и до PDF дословно. */
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
        // «&amp;#1053;» — это текст про сущность, а не сама сущность: порядок разворачивания
        // обязан оставить его текстом, иначе мы подменим содержимое документа.
        val obj = docx("<w:body><w:t>&amp;#1053;</w:t></w:body>")

        assertEquals("&#1053;", extractor.extractText(obj))
    }

    @Test
    fun `an impossible code point is left as written`() = runTest {
        val obj = docx("<w:body><w:t>&#99999999;</w:t></w:body>")

        assertEquals("&#99999999;", extractor.extractText(obj))
    }
}
