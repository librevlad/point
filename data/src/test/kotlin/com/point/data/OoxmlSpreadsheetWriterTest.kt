package com.point.data

import com.point.core.flow.ObjectStore
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Pure-JVM: the hand-rolled OOXML writer produces a valid, well-formed .xlsx. */
class OoxmlSpreadsheetWriterTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private fun sheetOf(ref: ScratchRef): String =
        ZipFile(File(ref.value)).use { zip ->
            zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readBytes().decodeToString()
        }

    @Test
    fun `writes the five OOXML parts with cell values at the right refs`() = runBlocking {
        val ref = OoxmlSpreadsheetWriter(store).write(
            listOf(listOf("Имя", "Сумма"), listOf("Приказ", "42")),
        )
        val entries = ZipFile(File(ref.value)).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue(
            entries.containsAll(
                listOf(
                    "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                    "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml",
                ),
            ),
        )
        val sheet = sheetOf(ref)
        assertTrue(sheet.contains("<t xml:space=\"preserve\">Имя</t>"))
        assertTrue(sheet.contains("r=\"B2\"")) // second column, second row
        assertTrue(sheet.contains("<t xml:space=\"preserve\">42</t>"))
    }

    @Test
    fun `escapes xml-special characters`() = runBlocking {
        val ref = OoxmlSpreadsheetWriter(store).write(listOf(listOf("a & b < c")))
        assertTrue(sheetOf(ref).contains("a &amp; b &lt; c"))
    }

    @Test
    fun `renders header, corrections and flags with styles (#200 ocr++)`() = runBlocking {
        val ref = OoxmlSpreadsheetWriter(store).write(
            listOf(
                listOf("Дата", "Результат"),
                listOf("16.07", "~~53~~ 40⚠"),
                listOf("18.07", "Гречка⚠"),
            ),
        )
        val entries = ZipFile(File(ref.value)).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue("styles.xml part is present", entries.contains("xl/styles.xml"))
        val sheet = sheetOf(ref)
        // A correction stores only the NEW value; every marker is stripped from the stored text.
        assertTrue(sheet.contains("<t xml:space=\"preserve\">40</t>"))
        assertTrue(sheet.contains("<t xml:space=\"preserve\">Гречка</t>"))
        assertFalse("strike markers stripped", sheet.contains("~~"))
        assertFalse("warning marker stripped", sheet.contains("⚠"))
        // The header row carries the header style id.
        assertTrue("header cell styled", sheet.contains("r=\"A1\" s=\"1\""))
    }
}
