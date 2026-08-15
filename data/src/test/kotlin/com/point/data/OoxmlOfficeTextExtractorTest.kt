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

    private fun xlsx(vararg parts: Pair<String, String>): PointObject {
        val file = File.createTempFile("point-", ".xlsx").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zos ->
            parts.forEach { (name, xml) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(xml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return PointObject(
            id = "id",
            mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.OFFICE),
        )
    }

    private fun sheet(number: Int, body: String) =
        "xl/worksheets/sheet$number.xml" to "<worksheet><sheetData>$body</sheetData></worksheet>"

    private fun inlineCell(ref: String, text: String) = """<c r="$ref" t="inlineStr"><is><t>$text</t></is></c>"""

    private fun numberCell(ref: String, value: String) = """<c r="$ref"><v>$value</v></c>"""

    @Test
    fun `#997 смета без общей таблицы строк отдаёт и позиции, и суммы`() = runTest {
        val obj = xlsx(
            sheet(
                1,
                "<row r=\"1\">" + inlineCell("A1", "Позиция") + inlineCell("D1", "Сумма") + "</row>" +
                    "<row r=\"2\">" + inlineCell("A2", "Плитка") + numberCell("B2", "12") +
                    numberCell("C2", "250") + numberCell("D2", "3000") + "</row>" +
                    "<row r=\"3\">" + inlineCell("A3", "ИТОГО") + numberCell("D3", "3480") + "</row>",
            ),
        )

        val text = extractor.extractText(obj)

        assertTrue(text, text.contains("Позиция"))
        assertTrue(text, text.contains("Плитка"))
        assertTrue("ради этой суммы таблицу и открывают", text.contains("3480"))
        assertTrue(text, text.contains("250"))
    }

    @Test
    fun `#997 числа выходят и тогда, когда строки лежат в общей таблице`() = runTest {
        val obj = xlsx(
            sheet(
                1,
                """<row r="1"><c r="A1" t="s"><v>0</v></c>""" + numberCell("B1", "480") + "</row>",
            ),
            "xl/sharedStrings.xml" to "<sst><si><t>Клей</t></si></sst>",
        )

        val text = extractor.extractText(obj)

        assertTrue(text, text.contains("Клей"))
        assertTrue("число ячейки — такое же содержимое таблицы, как и строка", text.contains("480"))
    }

    @Test
    fun `#997 содержимое второго листа книги не теряется`() = runTest {
        val obj = xlsx(
            sheet(2, "<row r=\"1\">" + inlineCell("A1", "Второй лист") + "</row>"),
            sheet(1, "<row r=\"1\">" + inlineCell("A1", "Первый лист") + "</row>"),
        )

        val text = extractor.extractText(obj)

        assertTrue(text, text.contains("Первый лист"))
        assertTrue(text, text.contains("Второй лист"))
        assertTrue("листы идут по порядку книги", text.indexOf("Первый лист") < text.indexOf("Второй лист"))
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
