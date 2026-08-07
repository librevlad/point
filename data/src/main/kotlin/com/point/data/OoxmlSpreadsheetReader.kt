package com.point.data

import com.point.core.flow.SpreadsheetReader
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject

class OoxmlSpreadsheetReader @Inject constructor() : SpreadsheetReader {

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
                            entry.name == "xl/sharedStrings.xml" -> shared = zis.readBytes().toString(Charsets.UTF_8)
                            entry.name == "xl/worksheets/sheet1.xml" -> sheet1 = zis.readBytes().toString(Charsets.UTF_8)
                            anySheet == null && WORKSHEET.matches(entry.name) ->
                                anySheet = zis.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        val sheetXml = sheet1 ?: anySheet ?: return@withContext emptyList()
        parseSheet(sheetXml, shared?.let(::parseShared) ?: emptyList())
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
        val SI = Regex("""<si\b[^>]*>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
        val ROW = Regex("""<row\b[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
        val CELL = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
        val T = Regex("""<t(?:\s[^>]*)?>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
        val V = Regex("""<v(?:\s[^>]*)?>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
        val TYPE = Regex("""\bt="([^"]+)"""")
        val REF = Regex("""\br="([A-Z]+)\d+"""")
    }
}
