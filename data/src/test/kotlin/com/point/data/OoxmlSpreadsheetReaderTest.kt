package com.point.data

import com.point.core.flow.OoxmlSpreadsheetWriter
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OoxmlSpreadsheetReaderTest {

    private val reader = OoxmlSpreadsheetReader()

    private val store = object : com.point.core.flow.ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private fun xlsxOf(vararg parts: Pair<String, String>): PointObject {
        val file = File.createTempFile("point-", ".xlsx").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zos ->
            parts.forEach { (name, xml) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(xml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return PointObject("id", XLSX_MIME, ScratchRef(file.absolutePath), ObjectState(ObjectKind.OFFICE))
    }

    private fun sheet(body: String) =
        "xl/worksheets/sheet1.xml" to
            """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$body</sheetData></worksheet>"""

    @Test
    fun `round-trips the writer's own inline-string xlsx (the failing case)`() = runBlocking {

        val ref = OoxmlSpreadsheetWriter(store).write(
            listOf(listOf("Имя", "Сумма"), listOf("Приказ", "42")),
        )
        val obj = PointObject("id", XLSX_MIME, ref, ObjectState(ObjectKind.OFFICE))
        assertEquals(listOf(listOf("Имя", "Сумма"), listOf("Приказ", "42")), reader.readRows(obj))
    }

    @Test
    fun `reads the shared-strings table (Excel-style cells)`() = runTest {
        val obj = xlsxOf(
            "xl/sharedStrings.xml" to
                """<sst><si><t>Alpha</t></si><si><t>Beta</t></si></sst>""",
            sheet("""<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>"""),
        )
        assertEquals(listOf(listOf("Alpha", "Beta")), reader.readRows(obj))
    }

    @Test
    fun `reads bare numeric cells`() = runTest {
        val obj = xlsxOf(sheet("""<row r="1"><c r="A1"><v>42</v></c><c r="B1"><v>3.14</v></c></row>"""))
        assertEquals(listOf(listOf("42", "3.14")), reader.readRows(obj))
    }

    @Test
    fun `pads gaps from sparse column refs`() = runTest {

        val obj = xlsxOf(sheet("""<row r="1"><c r="C1" t="inlineStr"><is><t>x</t></is></c></row>"""))
        assertEquals(listOf(listOf("", "", "x")), reader.readRows(obj))
    }

    @Test
    fun `a non-spreadsheet zip yields no rows`() = runTest {
        val obj = xlsxOf("word/document.xml" to "<w:document/>")
        assertEquals(emptyList<List<String>>(), reader.readRows(obj))
    }

    private companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
