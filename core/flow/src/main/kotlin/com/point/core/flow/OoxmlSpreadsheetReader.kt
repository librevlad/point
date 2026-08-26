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
 *
 * Какой лист первый и в каком порядке идут остальные — спрашивается у самой книги
 * (`xl/workbook.xml` плюс её связи), а не у имён файлов внутри архива. Номер в имени
 * `sheet2.xml` — след того, каким лист создавали, а не того, каким человек его видит:
 * переставленные или удалённые вкладки эти два порядка расходят, и тогда «На новый период»
 * взяло бы не ту таблицу, а текст книги вышел бы не в том порядке.
 */
class OoxmlSpreadsheetReader : SpreadsheetReader {

    /** Одна таблица документа — первый лист книги. Весь её текст спрашивают у [rowsOf]. */
    override suspend fun readRows(obj: PointObject): List<List<String>> = withContext(Dispatchers.IO) {
        var shared: String? = null
        var workbook: String? = null
        var relations: String? = null
        val sheets = sortedMapOf<String, String>()
        runCatching {
            ZipInputStream(File(obj.uri.value).inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        when {
                            entry.name == SHARED_STRINGS -> shared = zis.readBytes().toString(Charsets.UTF_8)
                            entry.name == WORKBOOK -> workbook = zis.readBytes().toString(Charsets.UTF_8)
                            entry.name == WORKBOOK_RELS -> relations = zis.readBytes().toString(Charsets.UTF_8)
                            isWorksheet(entry.name) ->
                                sheets[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        val order = sheetOrder(workbook, relations)
        val first = inBookOrder(sheets.keys, order).firstOrNull()
        rowsOf(sheets.filterKeys { it == first }, shared, order)
    }

    companion object {

        const val SHARED_STRINGS = "xl/sharedStrings.xml"

        /** Сама книга: в ней записано, какие у неё листы и в каком они порядке. */
        const val WORKBOOK = "xl/workbook.xml"

        /** Связи книги: по ним `r:id` листа превращается в файл внутри архива. */
        const val WORKBOOK_RELS = "xl/_rels/workbook.xml.rels"

        fun isWorksheet(entryName: String): Boolean = WORKSHEET.matches(entryName)

        /**
         * Порядок листов книги: имена файлов листов в том порядке, в каком человек видит
         * вкладки у себя (#995). Пусто — книга о себе не рассказала, и порядок остаётся
         * прежним, по номеру в имени файла.
         */
        fun sheetOrder(workbookXml: String?, relsXml: String?): List<String> {
            if (workbookXml == null || relsXml == null) return emptyList()
            val target = RELATION.findAll(relsXml).associate { rel ->
                rel.groupValues[1] to partName(rel.groupValues[2])
            }
            return SHEET.findAll(workbookXml).mapNotNull { sheet ->
                REL_ID.find(sheet.groupValues[1])?.groupValues?.get(1)?.let(target::get)
            }.filter(::isWorksheet).toList()
        }

        /**
         * Весь текст книги: листы и общий словарь строк уже прочитаны тем, кто открыл пакет.
         *
         * [order] — порядок вкладок из самой книги ([sheetOrder]). Лист, которого в нём нет,
         * идёт следом по номеру в имени файла: потерять лист молча хуже, чем показать его
         * последним.
         */
        fun rowsOf(
            sheets: Map<String, String>,
            sharedXml: String?,
            order: List<String>,
        ): List<List<String>> {
            if (sheets.isEmpty()) return emptyList()
            val shared = sharedXml?.let(::parseShared) ?: emptyList()
            return inBookOrder(sheets.keys, order).flatMap { parseSheet(sheets.getValue(it), shared) }
        }

        private fun inBookOrder(present: Collection<String>, order: List<String>): List<String> {
            val known = order.filter { it in present }
            return known + present.filterNot { it in known }.sortedBy(::sheetNumber)
        }

        /** Адрес части внутри пакета: связи пишут его и от папки книги, и от корня архива. */
        private fun partName(target: String): String {
            val clean = target.removePrefix("./")
            return if (clean.startsWith("/")) clean.removePrefix("/") else "xl/$clean"
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
        private val SHEET = Regex("""<sheet\b([^>]*)>""")

        // Ссылка листа на его файл: пространство имён связей зовут обычно `r`, но имя
        // приставки — дело того, кто записал книгу, а не наше.
        private val REL_ID = Regex("""\b(?:[\w.-]+:)?id="(rId[^"]+)"""", RegexOption.IGNORE_CASE)
        private val RELATION =
            Regex("""<Relationship\b(?=[^>]*\bId="([^"]+)")(?=[^>]*\bTarget="([^"]+)")[^>]*>""")
        private val SI = Regex("""<si\b[^>]*>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
        private val ROW = Regex("""<row\b[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
        private val CELL = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
        private val T = Regex("""<t(?:\s[^>]*)?>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
        private val V = Regex("""<v(?:\s[^>]*)?>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
        private val TYPE = Regex("""\bt="([^"]+)"""")
        private val REF = Regex("""\br="([A-Z]+)\d+"""")
    }
}
