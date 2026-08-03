package com.point.data

import com.point.core.flow.ObjectStore
import com.point.core.flow.SpreadsheetWriter
import com.point.core.flow.styleCell
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Writes rows to a minimal `.xlsx` by hand — a ZIP of the OOXML parts a spreadsheet needs, with values
 * as inline strings (no shared-strings table). No Apache POI: mirrors [OoxmlOfficeTextExtractor].
 *
 * #200 ocr++: cells carry rendering markers ([styleCell]) — a bold header row, a struck «~~52~~», a
 * correction «~~53~~ 40» (yellow), an uncertain «…⚠» (orange). When the consensus supplies `candidates`
 * for a disagreed cell, that cell also gets an in-cell **dropdown** of the models' readings, backed by a
 * hidden `_варіанти` sheet (a range reference, so comma-decimals like «0,72 0,883» never break the list).
 */
class OoxmlSpreadsheetWriter @Inject constructor(
    private val store: ObjectStore,
) : SpreadsheetWriter {

    private class Block(val ref: String, val values: List<String>, val start: Int, val end: Int)

    override suspend fun write(
        rows: List<List<String>>,
        candidates: Map<Pair<Int, Int>, List<String>>,
    ): ScratchRef = withContext(Dispatchers.IO) {
        val ref = store.newScratchFile("xlsx")
        // lay each multi-option cell's candidates down column A of the helper sheet
        var hrow = 1
        val blocks = candidates.entries
            .filter { it.value.size > 1 }
            .sortedBy { it.key.first * 1000 + it.key.second }
            .map { (rc, vals) ->
                val (r, c) = rc
                Block("${col(c)}${r + 1}", vals, hrow, hrow + vals.size - 1).also { hrow += vals.size }
            }
        val hasCand = blocks.isNotEmpty()
        ZipOutputStream(File(ref.value).outputStream().buffered()).use { zip ->
            zip.put("[Content_Types].xml", contentTypes(hasCand))
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("xl/workbook.xml", workbook(hasCand))
            zip.put("xl/_rels/workbook.xml.rels", workbookRels(hasCand))
            zip.put("xl/styles.xml", STYLES)
            zip.put("xl/worksheets/sheet1.xml", sheet(rows, blocks))
            if (hasCand) zip.put("xl/worksheets/sheet2.xml", helperSheet(blocks))
        }
        ref
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sheet(rows: List<List<String>>, blocks: List<Block>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { r, cells ->
            append("""<row r="${r + 1}">""")
            cells.forEachIndexed { c, value ->
                val cell = styleCell(value)
                append("""<c r="${col(c)}${r + 1}" s="${styleId(r, cell)}" t="inlineStr"><is><t xml:space="preserve">""")
                append(xml(cell.value))
                append("""</t></is></c>""")
            }
            append("</row>")
        }
        append("</sheetData>")
        if (blocks.isNotEmpty()) {
            append("""<dataValidations count="${blocks.size}">""")
            for (b in blocks) {
                // showErrorMessage="0" — a suggestion, not a lock: the user may still type a value the
                // models missed. Range reference on the hidden sheet handles commas in the options.
                append("""<dataValidation type="list" allowBlank="1" showInputMessage="1" showErrorMessage="0" sqref="${b.ref}">""")
                append("""<formula1>'_варіанти'!${'$'}A${'$'}${b.start}:${'$'}A${'$'}${b.end}</formula1></dataValidation>""")
            }
            append("</dataValidations>")
        }
        append("</worksheet>")
    }

    private fun helperSheet(blocks: List<Block>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        // blocks are laid out contiguously from row 1 — emit each option on its row
        for (b in blocks) {
            b.values.forEachIndexed { i, v ->
                val row = b.start + i
                append("""<row r="$row"><c r="A$row" t="inlineStr"><is><t xml:space="preserve">""")
                append(xml(v))
                append("""</t></is></c></row>""")
            }
        }
        append("</sheetData></worksheet>")
    }

    /**
     * Cell style index into [STYLES] cellXfs: **сначала пометки, потом оформление шапки**.
     *
     * Порядок был обратным, и это стоило предупреждений. Знака «⚠» в тексте ячейки нет —
     * [styleCell] снимает его, и единственный носитель неуверенности в файле — заливка. Стиль
     * шапки стоял на первой строке безусловно, поэтому у документа **без строки заголовков**
     * (кадр 18 корпуса: сетка начинается сразу под подписью) первая строка данных красилась
     * серым «заголовком», и все её пометки исчезали бесследно. Живой прогон: приложение
     * насчитало 24 помеченные ячейки, в файле их осталось 13 — одиннадцать предупреждений
     * стёрло оформление.
     *
     * Неуверенность — факт, шапка — оформление; факт оформлению не уступает.
     */
    private fun styleId(row: Int, cell: com.point.core.flow.StyledCell): Int = when {
        cell.flagged -> STYLE_FLAG
        cell.corrected -> STYLE_CORRECTED
        cell.strike -> STYLE_STRIKE
        row == 0 -> STYLE_HEADER
        else -> STYLE_DEFAULT
    }

    /** 0-based column index -> A, B, … Z, AA, AB … */
    private fun col(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, 'A' + (i % 26))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private fun xml(s: String): String = buildString {
        for (ch in s) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> append(ch)
            }
        }
    }

    private fun contentTypes(hasCand: Boolean) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>${if (hasCand) """<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" else ""}</Types>"""

    private fun workbook(hasCand: Boolean) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Таблица" sheetId="1" r:id="rId1"/>${if (hasCand) """<sheet name="_варіанти" sheetId="2" state="hidden" r:id="rId3"/>""" else ""}</sheets></workbook>"""

    private fun workbookRels(hasCand: Boolean) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>${if (hasCand) """<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>""" else ""}</Relationships>"""

    private companion object {
        const val STYLE_DEFAULT = 0
        const val STYLE_HEADER = 1
        const val STYLE_STRIKE = 2
        const val STYLE_CORRECTED = 3
        const val STYLE_FLAG = 4

        const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

        const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><strike/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="5"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFD9D9D9"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF2CC"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFD199"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="0" fontId="0" fillId="3" borderId="0" xfId="0" applyFill="1"/><xf numFmtId="0" fontId="0" fillId="4" borderId="0" xfId="0" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
    }
}
