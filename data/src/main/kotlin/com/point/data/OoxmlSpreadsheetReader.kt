package com.point.data

import com.point.core.flow.SpreadsheetReader
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject

class OoxmlSpreadsheetReader @Inject constructor() : SpreadsheetReader {

    override suspend fun readRows(obj: PointObject): List<List<String>> =
        readSheets(obj).firstOrNull() ?: emptyList()

    /**
     * Строки всех листов книги, по порядку листов.
     *
     * #997: из таблицы человеку нужно всё её содержимое — заголовки, позиции и числа,
     * а не первый попавшийся лист. Разбор ячеек живёт здесь одним местом, чтобы чтение
     * таблицы для текста и чтение таблицы для строк не расходились.
     */
    internal suspend fun readSheets(obj: PointObject): List<List<List<String>>> = withContext(Dispatchers.IO) {
        var shared: String? = null
        val sheets = sortedMapOf<String, String>(BY_SHEET_ORDER)
        runCatching {
            ZipInputStream(File(obj.uri.value).inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        when {
                            entry.name == "xl/sharedStrings.xml" -> shared = zis.readBytes().toString(Charsets.UTF_8)
                            WORKSHEET.matches(entry.name) ->
                                sheets[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        if (sheets.isEmpty()) return@withContext emptyList()
        // Общая таблица строк может лежать в архиве после листов, поэтому разбираем её,
        // когда прочитан весь файл, а не по ходу обхода.
        val strings = shared?.let(::parseShared) ?: emptyList()
        sheets.values.map { parseSheet(it, strings) }
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

    private companion object {
        val WORKSHEET = Regex("""xl/worksheets/sheet\d+\.xml""")
        val SHEET_NUMBER = Regex("""sheet(\d+)\.xml$""")

        /** Порядок листов книги числовой: sheet2 идёт раньше sheet10, как у человека в книге. */
        val BY_SHEET_ORDER = compareBy<String>(
            { SHEET_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE },
            { it },
        )

        val SI = Regex("""<si\b[^>]*>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
        val ROW = Regex("""<row\b[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
        val CELL = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
        val T = Regex("""<t(?:\s[^>]*)?>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
        val V = Regex("""<v(?:\s[^>]*)?>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
        val TYPE = Regex("""\bt="([^"]+)"""")
        val REF = Regex("""\br="([A-Z]+)\d+"""")
    }
}
