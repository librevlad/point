package com.point.data

import com.point.core.flow.ObjectStore
import com.point.core.flow.SheetPlan
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
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

    @Test
    fun `candidates become an in-cell dropdown backed by a hidden sheet (#200 ocr++)`() = runBlocking {
        val ref = OoxmlSpreadsheetWriter(store).write(
            listOf(listOf("№", "Сума"), listOf("1", "0,72 0,883")),
            mapOf((1 to 1) to listOf("0,72 0,883", "0,270")), // comma-decimals — must not break the list
        )
        val entries = ZipFile(File(ref.value)).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue("helper sheet present", entries.contains("xl/worksheets/sheet2.xml"))
        val sheet = sheetOf(ref)
        assertTrue("a list data-validation is emitted", sheet.contains("<dataValidation type=\"list\""))
        assertTrue("dropdown targets the disagreed cell B2", sheet.contains("sqref=\"B2\""))
        assertTrue("options come from the hidden sheet by range", sheet.contains("_варіанти"))
    }

    /**
     * Пометка первой строки переживает оформление шапки (#262, кадр 18).
     *
     * У документа без строки заголовков первая строка — это данные. Знака «⚠» в тексте нет
     * ([styleCell] его снимает), и единственный носитель неуверенности — заливка; стиль шапки,
     * стоявший на первой строке безусловно, стирал предупреждение бесследно. Живой прогон кадра
     * 18: приложение насчитало 24 помеченные ячейки, в файле их осталось 13.
     */
    @Test
    fun `пометка на первой строке не съедается стилем шапки (#262)`() = runBlocking {
        val ref = OoxmlSpreadsheetWriter(store).write(listOf(listOf("304702⚠", "42")))
        val sheet = sheetOf(ref)
        assertTrue("помеченная ячейка залита неуверенностью", sheet.contains("""r="A1" s="4""""))
        assertTrue("непомеченная осталась шапкой", sheet.contains("""r="B1" s="1""""))
    }

    /**
     * Шапка — по факту документа, а не по позиции строки (#266).
     *
     * У счёта, где сетка начинается сразу под подписью, заголовков нет вовсе, и первая товарная
     * строка приезжала жирной — оформление утверждало то, чего в документе нет. Теперь писателю
     * говорят, какие строки заголовочные, и «ни одной» — законный ответ.
     */
    @Test
    fun `шапку назначает план документа, а не номер строки (#266)`() = runBlocking {
        val rows = listOf(listOf("Гречка", "2"), listOf("Рис", "5"))

        val noHeader = sheetOf(OoxmlSpreadsheetWriter(store).write(SheetPlan(rows, headerRows = emptySet())))
        val secondRow = sheetOf(OoxmlSpreadsheetWriter(store).write(SheetPlan(rows, headerRows = setOf(1))))

        assertTrue("шапки нет — первая строка обычная", noHeader.contains("""r="A1" s="0""""))
        assertTrue("шапка на второй строке — она и жирная", secondRow.contains("""r="A2" s="1""""))
        assertTrue("а первая осталась данными", secondRow.contains("""r="A1" s="0""""))
    }

    /**
     * Заливка неуверенности — контракт с харнессом корпуса (#262), а не деталь оформления.
     *
     * Знака «⚠» в тексте ячейки нет: писатель снимает маркер и оставляет только номер стиля. Счётчик
     * таблиц (`tools/xlsx-to-tsv.awk`) переводит стиль обратно в маркер по ЦВЕТУ заливки — иначе
     * каждое честное предупреждение он посчитал бы молчаливым расхождением, то есть соврал бы в
     * главном своём числе и в худшую сторону. Цвет продублирован в `tools/table-score.sh`
     * (`FLAG_FILL`), и этот тест держит копию за руку: уехала палитра — падает здесь, а не тихо в
     * метрике через две недели.
     */
    @Test
    fun `uncertain cell is filled with the colour the corpus harness looks for (#262)`() = runBlocking {
        val ref = OoxmlSpreadsheetWriter(store).write(listOf(listOf("Артикул"), listOf("11004⚠")))
        val sheet = sheetOf(ref)
        val styles = ZipFile(File(ref.value)).use { zip ->
            zip.getInputStream(zip.getEntry("xl/styles.xml")).readBytes().decodeToString()
        }

        // s="…" помеченной ячейки → её xf в cellXfs → fillId → цвет. Ровно тот путь, что идёт awk.
        val styleId = Regex("""r="A2" s="(\d+)"""").find(sheet)!!.groupValues[1].toInt()
        val xfs = Regex("""<cellXfs\b[^>]*>(.*?)</cellXfs>""", RegexOption.DOT_MATCHES_ALL)
            .find(styles)!!.groupValues[1]
        val fillId = Regex("""<xf\b[^>]*?>""").findAll(xfs).toList()[styleId]
            .let { Regex("""fillId="(\d+)"""").find(it.value)!!.groupValues[1].toInt() }
        val fills = Regex("""<fills\b[^>]*>(.*?)</fills>""", RegexOption.DOT_MATCHES_ALL)
            .find(styles)!!.groupValues[1]
        val rgb = Regex("""<fill>(.*?)</fill>""", RegexOption.DOT_MATCHES_ALL).findAll(fills).toList()[fillId]
            .let { Regex("""rgb="([0-9A-Fa-f]+)"""").find(it.value)?.groupValues?.get(1) }

        assertEquals(
            "цвет неуверенности сменился — поправь FLAG_FILL в tools/table-score.sh, иначе счётчик " +
                "таблиц (#262) назовёт молчаливыми все предупреждённые расхождения",
            "FFFFD199",
            rgb,
        )
    }
}
