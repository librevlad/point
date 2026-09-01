package com.point.core.flow

import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OoxmlSpreadsheetWriter(
    private val store: ObjectStore,
) : SpreadsheetWriter {

    private class Block(val ref: String, val values: List<String>, val start: Int, val end: Int)

    override suspend fun write(
        rows: List<List<String>>,
        candidates: Map<Pair<Int, Int>, List<String>>,
    ): ScratchRef = write(sheetPlanOf(rows, candidates))

    override suspend fun write(plan: SheetPlan): ScratchRef = withContext(Dispatchers.IO) {
        val rows = plan.rows
        val candidates = plan.candidates
        val ref = store.newScratchFile("xlsx")

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
            zip.put("xl/worksheets/sheet1.xml", sheet(plan, blocks))
            if (hasCand) zip.put("xl/worksheets/sheet2.xml", helperSheet(blocks))
        }
        ref
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sheet(plan: SheetPlan, blocks: List<Block>): String = buildString {
        val rows = plan.rows
        val headerRows = plan.headerRows
        val gridWidth = IntArray(rows.size)
        plan.tables.forEach { range ->
            val width = range.maxOf { rows.getOrNull(it)?.size ?: 0 }
            range.forEach { r -> if (r in rows.indices) gridWidth[r] = width }
        }
        val sheetWidth = gridWidth.maxOrNull() ?: 0

        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")

        // Ширины колонок — по содержимому сетки (#1371): «ОСОБИСТИЙ ПІДПИС» читается
        // целиком, а одна длинная ячейка не растягивает колонку на весь экран — потолок.
        // Ширина листа меряется в знаках цифры, а кириллица шире её (живой прогон:
        // «ПОСАДА» ломалась переносом при ширине «по буквам»).
        val widths = IntArray(sheetWidth) { MIN_COL_CHARS }
        plan.tables.forEach { range ->
            range.forEach { r ->
                rows.getOrNull(r)?.forEachIndexed { c, value ->
                    val shown = styleCell(value).value
                    val longest = shown.split(NEWLINE).maxOf { line -> Math.ceil(line.length * CHAR_WIDTH).toInt() }
                    widths[c] = maxOf(widths[c], minOf(longest + COL_PADDING_CHARS, MAX_COL_CHARS))
                }
            }
        }
        if (sheetWidth > 0) {
            append("<cols>")
            widths.forEachIndexed { c, w ->
                append("""<col min="${c + 1}" max="${c + 1}" width="$w" customWidth="1"/>""")
            }
            append("</cols>")
        }

        append("<sheetData>")
        val merges = ArrayList<String>()
        val lineChars = widths.sum()
        rows.forEachIndexed { r, cells ->
            val single = cells.singleOrNull()?.let { styleCell(it).value }.orEmpty()

            // Объединённой ячейке Excel высоту сам не подбирает (#1371, живой прогон:
            // длинный заголовок обрезался первой строкой) — сколько строк займёт перенос,
            // считается по ширине листа, и строка получает высоту.
            // Символов на строку влезает меньше, чем юнитов ширины: юнит — знак цифры,
            // а кириллица шире (тот же коэффициент, что и у колонок).
            val perLine = maxOf(1, Math.floor(lineChars / CHAR_WIDTH).toInt())
            val mergedLines =
                if (gridWidth[r] == 0 && cells.size == 1 && sheetWidth > 1 && single.length > perLine) {
                    (single.length + perLine - 1) / perLine
                } else {
                    0
                }
            if (mergedLines > 1) {
                append("""<row r="${r + 1}" ht="${mergedLines * LINE_HEIGHT_PT}" customHeight="1">""")
            } else {
                append("""<row r="${r + 1}">""")
            }
            val grid = gridWidth[r] > 0
            cells.forEachIndexed { c, value ->
                val cell = styleCell(value)
                append("""<c r="${col(c)}${r + 1}" s="${styleId(r in headerRows, cell, grid, r in plan.titles)}" t="inlineStr"><is><t xml:space="preserve">""")
                append(xml(cell.value))
                append("""</t></is></c>""")
            }

            // Пустая клетка бланка остаётся видимой графой (#1371): строка сетки
            // дописывается до ширины своей таблицы клетками с той же рамкой.
            if (grid) {
                for (c in cells.size until gridWidth[r]) {
                    append("""<c r="${col(c)}${r + 1}" s="${styleId(r in headerRows, EMPTY_CELL, grid = true, title = false)}"/>""")
                }
            }

            // Свободная строка одной ячейкой — заголовок или подпись документа — ложится
            // по ширине таблицы, а не остаётся текстом в первой колонке (#1371).
            if (!grid && cells.size == 1 && cells.single().isNotEmpty() && sheetWidth > 1) {
                merges += "A${r + 1}:${col(sheetWidth - 1)}${r + 1}"
            }
            append("</row>")
        }
        append("</sheetData>")
        if (merges.isNotEmpty()) {
            append("""<mergeCells count="${merges.size}">""")
            merges.forEach { append("""<mergeCell ref="$it"/>""") }
            append("</mergeCells>")
        }
        if (blocks.isNotEmpty()) {
            append("""<dataValidations count="${blocks.size}">""")
            for (b in blocks) {

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

    private fun styleId(header: Boolean, cell: StyledCell, grid: Boolean, title: Boolean): Int = when {
        grid && cell.flagged -> STYLE_GRID_FLAG
        grid && cell.corrected -> STYLE_GRID_CORRECTED
        grid && cell.strike -> STYLE_GRID_STRIKE
        grid && header -> STYLE_GRID_HEADER
        grid -> STYLE_GRID
        cell.flagged -> STYLE_FLAG
        cell.corrected -> STYLE_CORRECTED
        cell.strike -> STYLE_STRIKE
        header -> STYLE_HEADER
        title -> STYLE_TITLE
        else -> STYLE_DEFAULT
    }

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
        const val STYLE_TITLE = 5
        const val STYLE_GRID = 6
        const val STYLE_GRID_HEADER = 7
        const val STYLE_GRID_STRIKE = 8
        const val STYLE_GRID_CORRECTED = 9
        const val STYLE_GRID_FLAG = 10

        /** Пустая графа бланка: рамка есть, текста нет. */
        val EMPTY_CELL = StyledCell("")

        val NEWLINE = 10.toChar()

        /** Кириллическая буква шире знака цифры, которым Excel меряет ширину колонки. */
        const val CHAR_WIDTH = 1.2

        const val LINE_HEIGHT_PT = 15

        const val MIN_COL_CHARS = 7
        const val COL_PADDING_CHARS = 2
        const val MAX_COL_CHARS = 50

        const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

        const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><strike/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="5"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFD9D9D9"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF2CC"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFD199"/></patternFill></fill></fills><borders count="2"><border/><border><left style="thin"><color rgb="FF000000"/></left><right style="thin"><color rgb="FF000000"/></right><top style="thin"><color rgb="FF000000"/></top><bottom style="thin"><color rgb="FF000000"/></bottom></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="11"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="0" fontId="0" fillId="3" borderId="0" xfId="0" applyFill="1"/><xf numFmtId="0" fontId="0" fillId="4" borderId="0" xfId="0" applyFill="1"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="center" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="2" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="4" borderId="1" xfId="0" applyFill="1" applyBorder="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
    }
}
