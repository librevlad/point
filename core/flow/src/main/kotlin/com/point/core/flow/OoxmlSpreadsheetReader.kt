package com.point.core.flow

import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Читатель таблиц OOXML (#997).
 *
 * Живёт здесь, а не в `:data`, потому что таблицу открывают обе поверхности — телефон и
 * компьютер, — и разбор у них обязан быть один. Строки в .xlsx лежат либо в общем словаре
 * `xl/sharedStrings.xml`, либо прямо в листе (`<is><t>`), а числа — только в листе; читать
 * надо все три случая, иначе современная смета не читается вообще.
 */
class OoxmlSpreadsheetReader : SpreadsheetReader {

    override suspend fun readRows(obj: PointObject): List<List<String>> = withContext(Dispatchers.IO) {
        var shared: String? = null
        var sheet1: String? = null
        var anySheet: String? = null
        runCatching {
            ZipInputStream(File(obj.uri.value).inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        when {
                            entry.name == SHARED_STRINGS -> shared = zis.readBytes().toString(Charsets.UTF_8)
                            entry.name == FIRST_SHEET -> sheet1 = zis.readBytes().toString(Charsets.UTF_8)
                            anySheet == null && isWorksheet(entry.name) ->
                                anySheet = zis.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        rowsOf(sheet1 ?: anySheet, shared)
    }

    companion object {

        const val SHARED_STRINGS = "xl/sharedStrings.xml"

        const val FIRST_SHEET = "xl/worksheets/sheet1.xml"

        fun isWorksheet(entryName: String): Boolean = WORKSHEET.matches(entryName)

        /** Разбор без файлов: лист и общий словарь строк уже прочитаны тем, кто открыл пакет. */
        fun rowsOf(sheetXml: String?, sharedXml: String?): List<List<String>> {
            val sheet = sheetXml ?: return emptyList()
            return parseSheet(sheet, sharedXml?.let(::parseShared) ?: emptyList())
        }

        private fun parseShared(xml: String): List<String> =
            SI.findAll(xml).map { si -> T.findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) } }
                .toList()

        private fun parseSheet(xml: String, shared: List<String>): List<List<String>> =
            ROW.findAll(xml).map { row -> parseRow(row.groupValues[1], shared) }.toList()

        private fun parseRow(rowXml: String, shared: List<String>): List<String> {
            val cells = sortedMapOf<Int, String>()
            var next = 0
            for (cell in CELL.findAll(rowXml)) {
                val attrs = cell.groupValues[1]
                val body = cell.groupValues[2]
                val col = colIndex(attrs) ?: next
                next = col + 1
                cells[col] = cellValue(attrs, body, shared)
            }
            if (cells.isEmpty()) return emptyList()
            return (0..cells.lastKey()).map { cells[it] ?: "" }
        }

        private fun cellValue(attrs: String, body: String, shared: List<String>): String {
            if (body.isBlank()) return ""
            return when (TYPE.find(attrs)?.groupValues?.get(1)) {
                "inlineStr" -> T.find(body)?.let { unescape(it.groupValues[1]) } ?: ""
                "s" -> V.find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                else -> (V.find(body) ?: T.find(body))?.let { unescape(it.groupValues[1]) } ?: ""
            }
        }

        private fun colIndex(attrs: String): Int? {
            val letters = REF.find(attrs)?.groupValues?.get(1) ?: return null
            var index = 0
            for (ch in letters) index = index * 26 + (ch - 'A' + 1)
            return index - 1
        }

        private fun unescape(s: String): String = s
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        private val WORKSHEET = Regex("""xl/worksheets/sheet\d+\.xml""")
        private val SI = Regex("""<si\b[^>]*>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
        private val ROW = Regex("""<row\b[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
        private val CELL = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
        private val T = Regex("""<t(?:\s[^>]*)?>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
        private val V = Regex("""<v(?:\s[^>]*)?>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
        private val TYPE = Regex("""\bt="([^"]+)"""")
        private val REF = Regex("""\br="([A-Z]+)\d+"""")
    }
}
