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
 *
 * «Прочитать книгу» и «взять таблицу» — разные вопросы (#995). Текст книги — это весь её
 * текст, все листы: «Смета» и «Итого» — два листа одного документа, и человек, открывший
 * книгу, ждёт её содержимое, а не половину; за это отвечает [rowsOf]. А [readRows] отдаёт
 * одну таблицу — ту, с которой работают «На новый период» и поиск столбца дат: склей туда
 * второй лист, и бланк соберётся из двух разных таблиц, а даты будут искаться в смеси.
 */
class OoxmlSpreadsheetReader : SpreadsheetReader {

    /** Одна таблица документа — первый лист книги. Весь её текст спрашивают у [rowsOf]. */
    override suspend fun readRows(obj: PointObject): List<List<String>> = withContext(Dispatchers.IO) {
        var shared: String? = null
        val sheets = sortedMapOf<String, String>()
        runCatching {
            ZipInputStream(File(obj.uri.value).inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        when {
                            entry.name == SHARED_STRINGS -> shared = zis.readBytes().toString(Charsets.UTF_8)
                            isWorksheet(entry.name) ->
                                sheets[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        val first = sheets.entries.minByOrNull { sheetNumber(it.key) }
        rowsOf(listOfNotNull(first).associate { it.key to it.value }, shared)
    }

    companion object {

        const val SHARED_STRINGS = "xl/sharedStrings.xml"

        fun isWorksheet(entryName: String): Boolean = WORKSHEET.matches(entryName)

        /**
         * Весь текст книги: листы и общий словарь строк уже прочитаны тем, кто открыл пакет.
         *
         * Листы идут по своему номеру, а не по порядку записей в архиве: порядок листов —
         * то, в каком виде человек видит книгу у себя.
         */
        fun rowsOf(sheets: Map<String, String>, sharedXml: String?): List<List<String>> {
            if (sheets.isEmpty()) return emptyList()
            val shared = sharedXml?.let(::parseShared) ?: emptyList()
            return sheets.entries.sortedBy { sheetNumber(it.key) }.flatMap { parseSheet(it.value, shared) }
        }

        private fun sheetNumber(entryName: String): Int =
            WORKSHEET.find(entryName)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

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

        private val WORKSHEET = Regex("""xl/worksheets/sheet(\d+)\.xml""")
        private val SI = Regex("""<si\b[^>]*>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
        private val ROW = Regex("""<row\b[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
        private val CELL = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
        private val T = Regex("""<t(?:\s[^>]*)?>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
        private val V = Regex("""<v(?:\s[^>]*)?>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
        private val TYPE = Regex("""\bt="([^"]+)"""")
        private val REF = Regex("""\br="([A-Z]+)\d+"""")
    }
}
