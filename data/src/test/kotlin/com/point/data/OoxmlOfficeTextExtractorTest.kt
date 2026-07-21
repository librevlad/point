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
}
