package com.point.data

import com.point.core.flow.Box
import com.point.core.flow.CropEvidence
import com.point.core.flow.DocBlock
import com.point.core.flow.DocStyle
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.EvidenceImage
import com.point.core.flow.MAX_EVIDENCE_CROPS
import com.point.core.flow.ObjectStore
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

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

    /** Резак за контрактом (#267): Android-битмапа в JVM-тесте нет, а писателю нужны только
     *  байты и размер. `null` — «вырезать не удалось», тот же путь, что у битого файла. */
    private class FakeCropper(private val image: EvidenceImage? = EvidenceImage(ByteArray(64) { 7 }, 800, 120)) :
        EvidenceCropper {
        val asked = mutableListOf<CropEvidence>()
        override suspend fun crop(evidence: CropEvidence): EvidenceImage? {
            asked += evidence
            return image
        }
    }

    private val noCrops = FakeCropper(null)

    private fun writer(cropper: EvidenceCropper = noCrops) = OoxmlDocxWriter(store, cropper)

    private fun entriesOf(ref: ScratchRef): List<String> =
        ZipFile(File(ref.value)).use { zip -> zip.entries().toList().map { it.name } }

    private fun partOf(ref: ScratchRef, name: String): String =
        ZipFile(File(ref.value)).use { zip ->
            zip.getInputStream(zip.getEntry(name)).readBytes().decodeToString()
        }

    private fun documentOf(ref: ScratchRef): String = partOf(ref, "word/document.xml")

    /** Разметку рукописного OOXML судит настоящий парсер с учётом пространств имён — незакрытый
     *  тег или необъявленный префикс обязаны падать здесь, а не у человека в Word. */
    private fun assertWellFormed(xml: String) {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
    }

    private fun evidence(text: String) =
        DocBlock(text, DocStyle.NORMAL, uncertain = true, evidence = CropEvidence("/tmp/23.jpg", Box(0f, 0f, 100f, 40f)))

    @Test
    fun `writes the OOXML parts, one paragraph per line`() = runBlocking {
        val ref = writer().write(listOf("Первый абзац", "Второй абзац"))
        val entries = entriesOf(ref)
        assertTrue(entries.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")))
        val doc = documentOf(ref)
        assertTrue(doc.contains("<w:t xml:space=\"preserve\">Первый абзац</w:t>"))
        assertTrue(doc.contains("<w:t xml:space=\"preserve\">Второй абзац</w:t>"))
    }

    @Test
    fun `escapes xml-special characters`() = runBlocking {
        val ref = writer().write(listOf("a & b < c"))
        assertTrue(documentOf(ref).contains("a &amp; b &lt; c"))
    }

    @Test
    fun `an empty list still yields a valid one-paragraph document`() = runBlocking {
        val ref = writer().write(emptyList())
        assertTrue(documentOf(ref).contains("<w:body>"))
    }

    @Test
    fun `styled blocks carry real formatting - bold sizes and bullet markers (#128)`() = runBlocking {
        val ref = writer().writeStyled(
            listOf(
                DocBlock("Отчёт", DocStyle.TITLE),
                DocBlock("Расходы", DocStyle.HEADING),
                DocBlock("Такси — 540", DocStyle.BULLET),
                DocBlock("Обычный абзац.", DocStyle.NORMAL),
            ),
        )
        val doc = documentOf(ref)
        assertTrue(doc.contains("<w:b/><w:sz w:val=\"48\"/>")) // title: bold, 24pt
        assertTrue(doc.contains("<w:b/><w:sz w:val=\"32\"/>")) // heading: bold, 16pt
        assertTrue(doc.contains("<w:ind w:left=\"720\"/>"))     // bullet indent
        assertTrue(doc.contains(">• Такси — 540<"))
        assertTrue(doc.contains(">Обычный абзац.<"))
    }

    // -- #267: улика приезжает картинкой прямо в документ --

    @Test
    fun `улика едет в документ картинкой — часть media, отношение и разметка`() = runBlocking {
        val cropper = FakeCropper()

        val ref = writer(cropper).writeStyled(
            listOf(DocBlock("Ведомость", DocStyle.TITLE), evidence("11004 Гречка 50")),
        )

        val entries = entriesOf(ref)
        assertTrue("картинка лежит в пакете", entries.contains("word/media/evidence-1.jpg"))
        assertTrue("и на неё есть отношение", entries.contains("word/_rels/document.xml.rels"))
        assertEquals("резака спросили ровно про помеченный фрагмент", 1, cropper.asked.size)
        assertEquals("/tmp/23.jpg", cropper.asked.single().imagePath)

        val doc = documentOf(ref)
        assertTrue(doc.contains("<w:drawing>"))
        assertTrue("картинка ссылается на своё отношение", doc.contains("""r:embed="rId1""""))
        assertTrue("и объявлены пространства имён рисунка", doc.contains("xmlns:pic="))
        assertTrue("улика стоит сразу за своим фрагментом", doc.indexOf("11004") < doc.indexOf("<w:drawing>"))

        val rels = partOf(ref, "word/_rels/document.xml.rels")
        assertTrue(rels.contains("""Id="rId1""""))
        assertTrue(rels.contains("""Target="media/evidence-1.jpg""""))
        val types = partOf(ref, "[Content_Types].xml")
        assertTrue(types.contains("""<Default Extension="jpg" ContentType="image/jpeg"/>"""))
        listOf(doc, rels, types).forEach(::assertWellFormed)
    }

    @Test
    fun `координат нет — файл прежний, без media и без отношений`() = runBlocking {
        val ref = writer().writeStyled(
            listOf(DocBlock("Ведомость", DocStyle.TITLE), DocBlock("11004 Гречка 50", DocStyle.NORMAL, uncertain = true)),
        )

        val entries = entriesOf(ref)
        assertEquals(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"), entries)
        val doc = documentOf(ref)
        assertWellFormed(doc)
        assertFalse(doc.contains("<w:drawing>"))
        assertFalse("лишние пространства имён не объявляются", doc.contains("xmlns:pic="))
        assertTrue("подсветка при этом остаётся", doc.contains("""<w:highlight w:val="yellow"/>"""))
    }

    @Test
    fun `кроп не удался — документ всё равно валиден и без ссылок в никуда`() = runBlocking {
        val ref = writer(noCrops).writeStyled(listOf(evidence("11004 Гречка 50")))

        assertFalse(entriesOf(ref).any { it.startsWith("word/media/") })
        assertFalse(documentOf(ref).contains("r:embed"))
    }

    @Test
    fun `уверенному фрагменту улику не режут, даже если адрес приложили`() = runBlocking {
        val cropper = FakeCropper()

        val ref = writer(cropper).writeStyled(
            listOf(
                DocBlock(
                    "11004 Гречка 50", DocStyle.NORMAL,
                    evidence = CropEvidence("/tmp/23.jpg", Box(0f, 0f, 100f, 40f)),
                ),
            ),
        )

        assertTrue("резака не звали вовсе", cropper.asked.isEmpty())
        assertFalse(entriesOf(ref).any { it.startsWith("word/media/") })
    }

    @Test
    fun `предел на документ соблюдается — файл не раздувается`() = runBlocking {
        val cropper = FakeCropper()

        val ref = writer(cropper).writeStyled((0 until 30).map { evidence("1100$it Гречка 50") })

        assertEquals(MAX_EVIDENCE_CROPS, entriesOf(ref).count { it.startsWith("word/media/") })
        assertEquals("лишние куски даже не режутся", MAX_EVIDENCE_CROPS, cropper.asked.size)
        assertEquals(MAX_EVIDENCE_CROPS, Regex("<w:drawing>").findAll(documentOf(ref)).count())
    }
}
