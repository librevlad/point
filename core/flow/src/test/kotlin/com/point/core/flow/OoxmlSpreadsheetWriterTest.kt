package com.point.core.flow

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

class OoxmlSpreadsheetWriterTest {

    private val store = object : ObjectStore {
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
        assertTrue(sheet.contains("r=\"B2\""))
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

        assertTrue(sheet.contains("<t xml:space=\"preserve\">40</t>"))
        assertTrue(sheet.contains("<t xml:space=\"preserve\">Гречка</t>"))
        assertFalse("strike markers stripped", sheet.contains("~~"))
        assertFalse("warning marker stripped", sheet.contains("⚠"))

        assertTrue("header cell styled", sheet.contains("r=\"A1\" s=\"1\""))
    }

    @Test
    fun `candidates become an in-cell dropdown backed by a hidden sheet (#200 ocr++)`() = runBlocking {
        val ref = OoxmlSpreadsheetWriter(store).write(
            listOf(listOf("№", "Сума"), listOf("1", "0,72 0,883")),
            mapOf((1 to 1) to listOf("0,72 0,883", "0,270")),
        )
        val entries = ZipFile(File(ref.value)).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue("helper sheet present", entries.contains("xl/worksheets/sheet2.xml"))
        val sheet = sheetOf(ref)
        assertTrue("a list data-validation is emitted", sheet.contains("<dataValidation type=\"list\""))
        assertTrue("dropdown targets the disagreed cell B2", sheet.contains("sqref=\"B2\""))
        assertTrue("options come from the hidden sheet by range", sheet.contains("_варіанти"))
    }

    @Test
    fun `пометка на первой строке не съедается стилем шапки (#262)`() = runBlocking {
        val ref = OoxmlSpreadsheetWriter(store).write(listOf(listOf("304702⚠", "42")))
        val sheet = sheetOf(ref)
        assertTrue("помеченная ячейка залита неуверенностью", sheet.contains("""r="A1" s="4""""))
        assertTrue("непомеченная осталась шапкой", sheet.contains("""r="B1" s="1""""))
    }

    @Test
    fun `шапку назначает план документа, а не номер строки (#266)`() = runBlocking {
        val rows = listOf(listOf("Гречка", "2"), listOf("Рис", "5"))

        val noHeader = sheetOf(OoxmlSpreadsheetWriter(store).write(SheetPlan(rows, headerRows = emptySet())))
        val secondRow = sheetOf(OoxmlSpreadsheetWriter(store).write(SheetPlan(rows, headerRows = setOf(1))))

        assertTrue("шапки нет — первая строка обычная", noHeader.contains("""r="A1" s="0""""))
        assertTrue("шапка на второй строке — она и жирная", secondRow.contains("""r="A2" s="1""""))
        assertTrue("а первая осталась данными", secondRow.contains("""r="A1" s="0""""))
    }

    @Test
    fun `uncertain cell is filled with the colour the corpus harness looks for (#262)`() = runBlocking {
        val ref = OoxmlSpreadsheetWriter(store).write(listOf(listOf("Артикул"), listOf("11004⚠")))
        val sheet = sheetOf(ref)
        val styles = ZipFile(File(ref.value)).use { zip ->
            zip.getInputStream(zip.getEntry("xl/styles.xml")).readBytes().decodeToString()
        }

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

    // ---- #1371: таблица в Excel остаётся таблицей ----

    /** Мини-ведомость: заголовок, шапка на пять граф, две пустые нумерованные строки. */
    private fun vidomist() = SheetPlan(
        rows = listOf(
            listOf("ВІДОМІСТЬ"),
            listOf("№", "ПІБ", "ЗВАННЯ", "ПОСАДА", "ОСОБИСТИЙ ПІДПИС"),
            listOf("1"),
            listOf("2"),
        ),
        headerRows = setOf(1),
        tables = listOf(1..3),
        titles = setOf(0),
    )

    @Test
    fun `строки сетки обведены рамкой — включая пустые графы бланка`() = runBlocking {
        val sheet = sheetOf(OoxmlSpreadsheetWriter(store).write(vidomist()))

        assertTrue("шапка в стиле сетки: $sheet", sheet.contains("""r="A2" s="7""""))
        assertTrue("значение сетки с рамкой", sheet.contains("""r="A3" s="6""""))
        assertTrue(
            "пустая графа дописана и несёт рамку — бланк остаётся бланком",
            sheet.contains("""<c r="B3" s="6"/>"""),
        )
        assertTrue("пустая графа подписи", sheet.contains("""<c r="E4" s="6"/>"""))
    }

    @Test
    fun `свободные строки рамкой не обводятся, а заголовок ложится по ширине таблицы`() = runBlocking {
        val sheet = sheetOf(OoxmlSpreadsheetWriter(store).write(vidomist()))

        assertTrue("заголовок — стиль заголовка, не сетки", sheet.contains("""r="A1" s="5""""))
        assertTrue(
            "заголовок объединён на все пять граф",
            sheet.contains("""<mergeCell ref="A1:E1"/>"""),
        )
        assertFalse("заголовку пустых клеток сетки не дописано", sheet.contains("""<c r="B1" s="6"/>"""))
    }

    @Test
    fun `ширина колонки идёт за содержимым, но не дальше потолка`() = runBlocking {
        val long = "х".repeat(120)
        val plan = vidomist().let { it.copy(rows = it.rows + listOf(listOf("3", long))) }
            .copy(tables = listOf(1..4))
        val sheet = sheetOf(OoxmlSpreadsheetWriter(store).write(plan))

        assertTrue(
            "«ОСОБИСТИЙ ПІДПИС» читается целиком — кириллица шире знака цифры: $sheet",
            sheet.contains("""<col min="5" max="5" width="22" customWidth="1"/>"""),
        )
        assertTrue("узкому № — узкая колонка", sheet.contains("""<col min="1" max="1" width="7" customWidth="1"/>"""))
        assertTrue(
            "одна длинная ячейка не растягивает колонку на весь экран",
            sheet.contains("""<col min="2" max="2" width="50" customWidth="1"/>"""),
        )
    }

    @Test
    fun `длинный объединённый заголовок получает высоту — Excel сам её merged-ячейке не подбирает`() = runBlocking {
        val long = "доведення про кримінальну відповідальність за незаконне поводження зі зброєю, " +
            "бойовими припасами або вибуховими речовинами відповідно до Кримінального кодексу"
        val plan = vidomist().let { it.copy(rows = listOf(listOf(long)) + it.rows.drop(1)) }
        val sheet = sheetOf(OoxmlSpreadsheetWriter(store).write(plan))

        assertTrue("строке задана высота под перенос: $sheet", Regex("""<row r="1" ht="\d+" customHeight="1">""").containsMatchIn(sheet))
    }

    @Test
    fun `лист без знания о сетке пишется как раньше — ни рамок, ни ширин`() = runBlocking {
        val sheet = sheetOf(
            OoxmlSpreadsheetWriter(store).write(listOf(listOf("Имя", "Сумма"), listOf("Приказ", "42"))),
        )

        assertFalse(sheet.contains("<cols>"))
        assertFalse(sheet.contains("mergeCell"))
        assertFalse("стилей сетки нет", sheet.contains("""s="6""""))
    }

    @Test
    fun `наш же читатель читает бланк назад`() = runBlocking {
        val ref = OoxmlSpreadsheetWriter(store).write(vidomist())
        val obj = PointObject("x", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ref, com.point.core.model.ObjectState(com.point.core.model.ObjectKind.OFFICE))

        val back = OoxmlSpreadsheetReader().readRows(obj)

        assertEquals(listOf("ВІДОМІСТЬ"), back.first().filter { it.isNotBlank() })
        assertEquals(listOf("№", "ПІБ", "ЗВАННЯ", "ПОСАДА", "ОСОБИСТИЙ ПІДПИС"), back[1])
    }

    @Test
    fun `лист — валидный XML целиком`() = runBlocking {
        val ref = OoxmlSpreadsheetWriter(store).write(vidomist())
        ZipFile(File(ref.value)).use { zip ->
            for (entry in zip.entries()) {
                val bytes = zip.getInputStream(entry).readBytes()
                javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(bytes.inputStream())
            }
        }
    }
}
