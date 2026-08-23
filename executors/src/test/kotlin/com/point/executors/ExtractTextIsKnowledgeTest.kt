package com.point.executors

import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.ObjectStore
import com.point.core.flow.OoxmlOfficeTextExtractor
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.TextRecognizer
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * «Извлечь текст» отдаёт знание самому документу (#995) и называет настоящую причину (#997).
 *
 * На PDF действие рождало второй объект, который Point тут же объявлял непригодным: папка
 * отрисованных страниц выдавалась за одиночную картинку, объект указывал на каталог и не
 * открывался ни у кого. На современной .xlsx действие падало с причиной про старые .doc и
 * .xls — к этому файлу она не относится вовсе.
 */
class ExtractTextIsKnowledgeTest {

    @get:Rule val temp = TemporaryFolder()

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(temp.newFile("scratch-${System.nanoTime()}.$extension").absolutePath)
        override suspend fun clear() = Unit
    }

    private fun layer(text: String) = object : PdfTextExtractor {
        override suspend fun extractText(obj: PointObject) = text
    }

    /** Растеризатор отдаёт папку страниц — ровно так, как он это делает у «Страниц». */
    private fun pagesFolder(vararg pages: Pair<String, String>): PdfRasterizer {
        val dir = temp.newFolder("страницы-${System.nanoTime()}")
        pages.forEach { (name, text) -> File(dir, name).writeText(text) }
        return object : PdfRasterizer {
            override suspend fun rasterize(obj: PointObject) = ScratchRef(dir.absolutePath)
            override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? = null
        }
    }

    /** Читателю можно подсунуть только файл: каталог не открывается — на этом и падал #995. */
    private val eyes = object : TextRecognizer {
        override suspend fun recognize(obj: PointObject): String {
            val page = File(obj.uri.value)
            assertTrue("читателю подсунули каталог вместо страницы: $page", page.isFile)
            return page.readText()
        }
    }

    private fun pdf() = PointObject(
        id = "pdf",
        mime = "application/pdf",
        uri = ScratchRef(temp.newFile("счёт-${System.nanoTime()}.pdf").absolutePath),
        state = ObjectState(ObjectKind.PDF),
        metadata = mapOf("name" to "счёт.pdf"),
    )

    private fun knownText(result: ActionResult): String {
        assertTrue("ожидалось знание документу, вышло: $result", result is ActionResult.Done)
        val found = (result as ActionResult.Done).findings
        assertTrue("знания при шаге нет вовсе", found != null)
        assertTrue("документ не получил признака «текст есть»", Feature.HAS_TEXT in found!!.features)
        val ref = found.metadata[META_OCR_TEXT_REF]
        assertTrue("текста у документа нет: ${found.metadata}", !ref.isNullOrBlank())
        return File(ref!!).readText()
    }

    // ——— PDF: текст остаётся у документа ———

    @Test
    fun `текст PDF ложится знанием на сам документ, второго объекта не появляется`() = runTest {
        val realizer = PdfRealizer(store, layer("Счёт № 12 на 3480 гривен"), pagesFolder(), eyes)

        val result = realizer.perform(pdf())

        assertTrue(knownText(result).contains("3480"))
    }

    @Test
    fun `нечитаемый слой не заворачивает папку страниц в картинку — Point читает страницы сам`() = runTest {
        val realizer = PdfRealizer(
            store,
            layer(GARBLED),
            pagesFolder("page-001.jpg" to "Счёт на 3480", "page-002.jpg" to "Подпись директора"),
            eyes,
        )

        val known = knownText(realizer.perform(pdf()))

        assertTrue(known, known.contains("Счёт на 3480"))
        assertTrue("вторая страница потеряна: $known", known.contains("Подпись директора"))
    }

    @Test
    fun `пустой слой не отсылает человека в два действия — страницы читаются здесь же`() = runTest {
        val realizer = PdfRealizer(
            store,
            layer("   "),
            pagesFolder("page-001.jpg" to "Акт выполненных работ"),
            eyes,
        )

        assertTrue(knownText(realizer.perform(pdf())).contains("Акт выполненных работ"))
    }

    // ——— Таблица: читается своим читателем, отказ называет настоящую причину ———

    private fun xlsx(name: String, sheetXml: String): PointObject {
        val file = temp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(sheetXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return PointObject(
            id = "xlsx",
            mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.OFFICE),
            metadata = mapOf("name" to name),
        )
    }

    /** Смета владельца: общего словаря строк в файле нет вовсе, строки лежат внутри листа. */
    private val smeta = """
        <worksheet><sheetData>
        <row r="1"><c r="A1" t="inlineStr"><is><t>Работа</t></is></c><c r="B1"><v>12</v></c>
        <c r="C1"><v>250</v></c><c r="D1"><v>3000</v></c></row>
        <row r="2"><c r="A2" t="inlineStr"><is><t>Материалы</t></is></c><c r="B2"><v>4</v></c>
        <c r="C2"><v>120</v></c><c r="D2"><v>480</v></c></row>
        <row r="3"><c r="A3" t="inlineStr"><is><t>Итого</t></is></c><c r="D3"><v>3480</v></c></row>
        </sheetData></worksheet>
    """.trimIndent()

    @Test
    fun `смета со строками внутри листа читается — вместе с числами`() = runTest {
        val result = OfficeRealizer(store, OoxmlOfficeTextExtractor())
            .perform(xlsx("смета.xlsx", smeta), null)

        val known = knownText(result)
        assertTrue(known, known.contains("Работа") && known.contains("Материалы"))
        assertTrue("числа таблицы потеряны: $known", known.contains("3480") && known.contains("250"))
    }

    @Test
    fun `современной таблице не рассказывают про старые doc и xls`() = runTest {
        val empty = xlsx("смета.xlsx", "<worksheet><sheetData></sheetData></worksheet>")

        val result = OfficeRealizer(store, OoxmlOfficeTextExtractor()).perform(empty, null)

        val said = (result as ActionResult.Failure).reason
        assertFalse("причина не про этот файл: $said", said.contains(".doc") || said.contains(".xls "))
    }

    @Test
    fun `старому формату причина названа его именем`() = runTest {
        val old = PointObject(
            id = "xls",
            mime = "application/vnd.ms-excel",
            uri = ScratchRef(temp.newFile("смета.xls").absolutePath),
            state = ObjectState(ObjectKind.OFFICE),
            metadata = mapOf("name" to "смета.xls"),
        )

        val result = OfficeRealizer(store, OoxmlOfficeTextExtractor()).perform(old, null)

        val said = (result as ActionResult.Failure).reason
        assertTrue(said, said.contains(".xlsx"))
    }

    private companion object {

        /** Слой украинского бухгалтерского PDF с подменённой раскладкой шрифта (#933). */
        const val GARBLED =
            "ToeapucrBo 3 o6MexeHop eignoeiganbHicrlo BaxraxoorpxMyBaq cKnaAaHHR " +
                "flocraqanbHHK e.qPnov Eniqgxtp 3aMoBHHK PaxyHok-cbakrypa"
    }
}
