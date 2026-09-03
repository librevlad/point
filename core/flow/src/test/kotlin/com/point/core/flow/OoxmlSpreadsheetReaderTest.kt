package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Даты Excel читаются датами, а не серийными числами (#1418).
 *
 * Живая охота 03.09.2026, книга владельца «Їдальня»: 184 даты подряд в первой колонке, а в
 * тексте книги — «45839, 45840, …». Excel и 1С пишут дату числом дней от 30.12.1899 и стилем
 * ячейки говорят, что это дата; читатель стиль не открывал. На таких датах не находился период,
 * а человек читал числа вместо дат.
 */
class OoxmlSpreadsheetReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `дата в ячейке со стилем даты читается датой, а не числом дней`() = runTest {
        val book = workbook(
            sheet = """<row r="1"><c r="A1" s="1"><v>45839</v></c><c r="B1" s="0"><v>45839</v></c></row>""",
            styles = stylesWithDateAt(index = 1, numFmtId = 14),
        )

        val rows = OoxmlSpreadsheetReader().readRows(book)

        assertEquals(listOf(listOf("01.07.2025", "45839")), rows)
    }

    @Test
    fun `дата со временем несёт и время`() = runTest {
        val book = workbook(
            sheet = """<row r="1"><c r="A1" s="1"><v>45839.5</v></c></row>""",
            styles = stylesWithDateAt(index = 1, numFmtId = 22),
        )

        assertEquals(listOf(listOf("01.07.2025 12:00")), OoxmlSpreadsheetReader().readRows(book))
    }

    @Test
    fun `свой формат даты книги — тоже дата`() = runTest {
        val book = workbook(
            sheet = """<row r="1"><c r="A1" s="1"><v>45839</v></c></row>""",
            styles = """<numFmts count="1"><numFmt numFmtId="164" formatCode="dd/mm/yyyy;@"/></numFmts>""" +
                """<cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="164" applyNumberFormat="1"/></cellXfs>""",
        )

        assertEquals(listOf(listOf("01.07.2025")), OoxmlSpreadsheetReader().readRows(book))
    }

    @Test
    fun `число со своим числовым форматом датой не становится`() = runTest {
        val book = workbook(
            sheet = """<row r="1"><c r="A1" s="1"><v>45839</v></c></row>""",
            styles = """<numFmts count="1"><numFmt numFmtId="164" formatCode="0.000"/></numFmts>""" +
                """<cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="164" applyNumberFormat="1"/></cellXfs>""",
        )

        assertEquals(listOf(listOf("45839")), OoxmlSpreadsheetReader().readRows(book))
    }

    @Test
    fun `литералы в коде формата за буквы даты не считаются`() {
        assertFalse(SpreadsheetDateStyles.looksLikeDate("""[Red]0.00"""))
        assertFalse(SpreadsheetDateStyles.looksLikeDate(""""дн." 0"""))
        assertTrue(SpreadsheetDateStyles.looksLikeDate("""yyyy-mm-dd"""))
        assertTrue(SpreadsheetDateStyles.looksLikeDate("""[$-419]d mmmm yyyy;@"""))
    }

    /** Тот же класс, что #222: правило периода проверялось на датах текстом, а книги хранят даты числом. */
    @Test
    fun `период находится по настоящим датам Excel`() = runTest {
        val days = (0 until 7).joinToString("") { i ->
            """<row r="${i + 2}"><c r="A${i + 2}" s="1"><v>${45839 + i}</v></c><c r="B${i + 2}" t="inlineStr"><is><t>захід</t></is></c></row>"""
        }
        val book = workbook(
            sheet = """<row r="1"><c r="A1" t="inlineStr"><is><t>Дата</t></is></c></row>$days""",
            styles = stylesWithDateAt(index = 1, numFmtId = 14),
        )

        val reading = readPeriod(OoxmlSpreadsheetReader().readRows(book))

        assertNotNull("период по датам-числам не найден", reading)
        assertEquals(LocalDate.of(2025, 7, 1), reading!!.period.from)
        assertEquals(LocalDate.of(2025, 7, 7), reading.period.to)
    }

    @Test
    fun `текст книги показывает даты датами`() = runTest {
        val book = workbook(
            sheet = """<row r="1"><c r="A1" s="1"><v>45839</v></c><c r="B1" s="0"><v>1680</v></c></row>""",
            styles = stylesWithDateAt(index = 1, numFmtId = 14),
        )

        assertEquals("01.07.2025\t1680", OoxmlOfficeTextExtractor().extractText(book))
    }

    /**
     * #1417: у книг владельца первый лист — шапка или шаблон, период живёт на втором. Листы
     * отдаются порознь, с именами и в порядке вкладок; одна таблица (`readRows`) — по-прежнему первый.
     */
    @Test
    fun `листы книги читаются порознь, с именами и в порядке вкладок`() = runTest {
        val file = File(tmp.newFolder(), "book.xlsx")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun part(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            // Вкладки в книге идут «Однор_0», затем «ШАБЛОН», хотя в архиве шаблон — sheet1.
            part(
                "xl/workbook.xml",
                """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                    """<sheet name="Однор_0" sheetId="2" r:id="rId2"/><sheet name="ШАБЛОН" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            )
            part(
                "xl/_rels/workbook.xml.rels",
                """<Relationships><Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/>""" +
                    """<Relationship Id="rId2" Type="worksheet" Target="worksheets/sheet2.xml"/></Relationships>""",
            )
            part("xl/worksheets/sheet1.xml", """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>шапка</t></is></c></row></sheetData></worksheet>""")
            part("xl/worksheets/sheet2.xml", """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>дата</t></is></c></row></sheetData></worksheet>""")
        }
        val book = PointObject(
            "book", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ScratchRef(file.absolutePath), ObjectState(ObjectKind.OFFICE),
        )

        val sheets = OoxmlSpreadsheetReader().readSheets(book)

        assertEquals(listOf("Однор_0", "ШАБЛОН"), sheets.map { it.name })
        assertEquals(listOf(listOf("дата")), sheets[0].rows)
        assertEquals(listOf(listOf("шапка")), sheets[1].rows)
        assertEquals("одна таблица — первый лист книги, а не первый файл архива", listOf(listOf("дата")), OoxmlSpreadsheetReader().readRows(book))
    }

    private fun stylesWithDateAt(index: Int, numFmtId: Int): String {
        val xfs = (0..index).joinToString("") { i ->
            if (i == index) """<xf numFmtId="$numFmtId" applyNumberFormat="1"/>""" else """<xf numFmtId="0"/>"""
        }
        return """<cellXfs count="${index + 1}">$xfs</cellXfs>"""
    }

    /** Минимальная книга: один лист, стили, без общего словаря строк. */
    private fun workbook(sheet: String, styles: String): PointObject {
        val file = File(tmp.newFolder(), "book.xlsx")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun part(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            part(
                "xl/workbook.xml",
                """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Лист1" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            )
            part(
                "xl/_rels/workbook.xml.rels",
                """<Relationships><Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            )
            part("xl/styles.xml", """<styleSheet>$styles</styleSheet>""")
            part("xl/worksheets/sheet1.xml", """<worksheet><sheetData>$sheet</sheetData></worksheet>""")
        }
        return PointObject(
            "book",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ScratchRef(file.absolutePath),
            ObjectState(ObjectKind.OFFICE),
            metadata = mapOf("name" to "book.xlsx"),
        )
    }
}
