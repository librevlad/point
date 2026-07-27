package com.point.data

import com.point.core.flow.ObjectStore
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Pure-JVM: the hand-rolled OOXML writer produces a valid, well-formed .docx. */
class OoxmlDocxWriterTest {

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

    private fun documentOf(ref: ScratchRef): String =
        ZipFile(File(ref.value)).use { zip ->
            zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().decodeToString()
        }

    @Test
    fun `writes the OOXML parts, one paragraph per line`() = runBlocking {
        val ref = OoxmlDocxWriter(store).write(listOf("Первый абзац", "Второй абзац"))
        val entries = ZipFile(File(ref.value)).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue(entries.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")))
        val doc = documentOf(ref)
        assertTrue(doc.contains("<w:t xml:space=\"preserve\">Первый абзац</w:t>"))
        assertTrue(doc.contains("<w:t xml:space=\"preserve\">Второй абзац</w:t>"))
    }

    @Test
    fun `escapes xml-special characters`() = runBlocking {
        val ref = OoxmlDocxWriter(store).write(listOf("a & b < c"))
        assertTrue(documentOf(ref).contains("a &amp; b &lt; c"))
    }

    @Test
    fun `an empty list still yields a valid one-paragraph document`() = runBlocking {
        val ref = OoxmlDocxWriter(store).write(emptyList())
        assertTrue(documentOf(ref).contains("<w:body>"))
    }

    @Test
    fun `styled blocks carry real formatting - bold sizes and bullet markers (#128)`() = runBlocking {
        val ref = OoxmlDocxWriter(store).writeStyled(
            listOf(
                com.point.core.flow.DocBlock("Отчёт", com.point.core.flow.DocStyle.TITLE),
                com.point.core.flow.DocBlock("Расходы", com.point.core.flow.DocStyle.HEADING),
                com.point.core.flow.DocBlock("Такси — 540", com.point.core.flow.DocStyle.BULLET),
                com.point.core.flow.DocBlock("Обычный абзац.", com.point.core.flow.DocStyle.NORMAL),
            ),
        )
        val doc = documentOf(ref)
        assertTrue(doc.contains("<w:b/><w:sz w:val=\"48\"/>")) // title: bold, 24pt
        assertTrue(doc.contains("<w:b/><w:sz w:val=\"32\"/>")) // heading: bold, 16pt
        assertTrue(doc.contains("<w:ind w:left=\"720\"/>"))     // bullet indent
        assertTrue(doc.contains(">• Такси — 540<"))
        assertTrue(doc.contains(">Обычный абзац.<"))
    }
}
