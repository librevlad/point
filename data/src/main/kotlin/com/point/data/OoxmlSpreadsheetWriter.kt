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
 * Writes rows to a minimal `.xlsx` by hand — a ZIP of the OOXML parts a spreadsheet needs, with
 * values as inline strings (no shared-strings table). No Apache POI: mirrors [OoxmlOfficeTextExtractor],
 * which reads OOXML without a dependency.
 *
 * #200 ocr++: cells carry rendering markers ([styleCell]) — a bold header row, a struck «~~52~~»,
 * a correction «~~53~~ 40» (yellow), and an uncertain «…⚠» (orange, «перевірити»). A tiny hand-rolled
 * `xl/styles.xml` backs those; markers are stripped from the stored text. Plain strings are unaffected.
 */
class OoxmlSpreadsheetWriter @Inject constructor(
    private val store: ObjectStore,
) : SpreadsheetWriter {

    override suspend fun write(rows: List<List<String>>): ScratchRef = withContext(Dispatchers.IO) {
        val ref = store.newScratchFile("xlsx")
        ZipOutputStream(File(ref.value).outputStream().buffered()).use { zip ->
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("xl/workbook.xml", WORKBOOK)
            zip.put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            zip.put("xl/styles.xml", STYLES)
            zip.put("xl/worksheets/sheet1.xml", sheet(rows))
        }
        ref
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sheet(rows: List<List<String>>): String = buildString {
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
        append("</sheetData></worksheet>")
    }

    /** Cell style index into [STYLES] cellXfs: header row wins, then flag > correction > strike. */
    private fun styleId(row: Int, cell: com.point.core.flow.StyledCell): Int = when {
        row == 0 -> STYLE_HEADER
        cell.flagged -> STYLE_FLAG
        cell.corrected -> STYLE_CORRECTED
        cell.strike -> STYLE_STRIKE
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
                // Drop control chars XML 1.0 forbids (keep tab/newline/carriage-return).
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> append(ch)
            }
        }
    }

    private companion object {
        const val STYLE_DEFAULT = 0
        const val STYLE_HEADER = 1
        const val STYLE_STRIKE = 2
        const val STYLE_CORRECTED = 3
        const val STYLE_FLAG = 4

        const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""

        const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

        const val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Таблица" sheetId="1" r:id="rId1"/></sheets></workbook>"""

        // rId1 = worksheet, rId2 = styles.
        const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

        // fills: 0 none, 1 gray125 (reserved), 2 grey header, 3 yellow (correction), 4 orange (flag).
        // cellXfs indices match STYLE_* above.
        const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><strike/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="5"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFD9D9D9"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF2CC"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFD199"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="0" fontId="0" fillId="3" borderId="0" xfId="0" applyFill="1"/><xf numFmtId="0" fontId="0" fillId="4" borderId="0" xfId="0" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
    }
}
