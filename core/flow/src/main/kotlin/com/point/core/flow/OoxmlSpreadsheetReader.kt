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
        val book = open(obj)
        val first = inBookOrder(book.sheets.keys, book.order).firstOrNull()
        rowsOf(book.sheets.filterKeys { it == first }, book.shared, book.order, book.styles)
    }

    /**
     * Все листы книги порознь, в порядке вкладок и с именами (#1417): период ищется на каждом.
     * Имя — из самой книги (`xl/workbook.xml`); книга без имён отдаёт имя части архива.
     */
    override suspend fun readSheets(obj: PointObject): List<NamedSheet> = withContext(Dispatchers.IO) {
        val book = open(obj)
        val names = sheetNames(book.workbook, book.relations)
        inBookOrder(book.sheets.keys, book.order).map { entry ->
            NamedSheet(
                names[entry] ?: entry.removePrefix(WORKSHEETS).removeSuffix(".xml"),
                rowsOf(mapOf(entry to book.sheets.getValue(entry)), book.shared, book.order, book.styles),
            )
        }
    }

    /** Части книги, прочитанные за один проход по архиву. */
    private class Book(
        val sheets: Map<String, String>,
        val shared: String?,
        val workbook: String?,
        val relations: String?,
        val styles: String?,
    ) {
        val order: List<String> get() = sheetOrder(workbook, relations)
    }

    private fun open(obj: PointObject): Book {
        var shared: String? = null
        var workbook: String? = null
        var relations: String? = null
        var styles: String? = null
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
                            entry.name == STYLES -> styles = zis.readBytes().toString(Charsets.UTF_8)
                            isWorksheet(entry.name) ->
                                sheets[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return Book(sheets, shared, workbook, relations, styles)
    }

    companion object {

        const val SHARED_STRINGS = "xl/sharedStrings.xml"

        /**
         * Стили книги: по ним видно, что числовая ячейка — дата (#1418).
         *
         * Excel и 1С пишут дату числом дней от 30.12.1899, а датой её делает только формат
         * ячейки. Без стилей читатель отдавал «45839» вместо «01.07.2025»: в тексте книги
         * стояли числа, а период по настоящим датам не находился — правила ждали `dd.mm.yyyy`
         * текстом, на котором и были проверены.
         */
        const val STYLES = "xl/styles.xml"

        /** Сама книга: в ней записано, какие у неё листы и в каком они порядке. */
        const val WORKBOOK = "xl/workbook.xml"

        /** Связи книги: по ним `r:id` листа превращается в файл внутри архива. */
        const val WORKBOOK_RELS = "xl/_rels/workbook.xml.rels"

        /**
         * Лист ли это книги (#995).
         *
         * Лист — часть пакета в папке листов, а как её назвали внутри архива, решает тот, кто
         * записал книгу: `sheet1.xml` — привычка Excel, а не правило OOXML. Пока спрашивалось
         * строгое имя `sheetN.xml`, книга с иначе названными листами теряла их все и человек
         * слышал «в этом документе текста нет» на файле, где текст есть.
         *
         * Служебные связи листа (`xl/worksheets/_rels/…`) — не лист: это разметка о нём.
         */
        fun isWorksheet(entryName: String): Boolean =
            entryName.startsWith(WORKSHEETS) &&
                entryName.endsWith(".xml", ignoreCase = true) &&
                !entryName.removePrefix(WORKSHEETS).contains('/')

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

        /** Имена вкладок по частям архива (#1417): книга сама говорит, как зовётся каждый лист. */
        fun sheetNames(workbookXml: String?, relsXml: String?): Map<String, String> {
            if (workbookXml == null || relsXml == null) return emptyMap()
            val target = RELATION.findAll(relsXml).associate { rel ->
                rel.groupValues[1] to partName(rel.groupValues[2])
            }
            return SHEET.findAll(workbookXml).mapNotNull { sheet ->
                val part = REL_ID.find(sheet.groupValues[1])?.groupValues?.get(1)?.let(target::get)
                    ?: return@mapNotNull null
                val name = NAME.find(sheet.groupValues[1])?.groupValues?.get(1)?.let(::unescape)
                    ?: return@mapNotNull null
                part to name
            }.toMap()
        }

        /**
         * Весь текст книги: листы и общий словарь строк уже прочитаны тем, кто открыл пакет.
         *
         * [order] — порядок вкладок из самой книги ([sheetOrder]). Лист, которого в нём нет,
         * идёт следом по номеру в имени файла, а без номера — как лежит в архиве: потерять
         * лист молча хуже, чем показать его последним.
         */
        fun rowsOf(
            sheets: Map<String, String>,
            sharedXml: String?,
            order: List<String>,
            stylesXml: String? = null,
        ): List<List<String>> {
            if (sheets.isEmpty()) return emptyList()
            val shared = sharedXml?.let(::parseShared) ?: emptyList()
            val dates = SpreadsheetDateStyles.parse(stylesXml)
            return inBookOrder(sheets.keys, order).flatMap { parseSheet(sheets.getValue(it), shared, dates) }
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

        private fun parseSheet(xml: String, shared: List<String>, dates: SpreadsheetDateStyles): List<List<String>> =
            ROW.findAll(xml).map { row -> parseRow(row.groupValues[1], shared, dates) }.toList()

        private fun parseRow(rowXml: String, shared: List<String>, dates: SpreadsheetDateStyles): List<String> {
            val cells = sortedMapOf<Int, String>()
            var next = 0
            for (cell in CELL.findAll(rowXml)) {
                val attrs = cell.groupValues[1]
                val body = cell.groupValues[2]
                val col = colIndex(attrs) ?: next
                next = col + 1
                cells[col] = cellValue(attrs, body, shared, dates)
            }
            if (cells.isEmpty()) return emptyList()
            return (0..cells.lastKey()).map { cells[it] ?: "" }
        }

        private fun cellValue(attrs: String, body: String, shared: List<String>, dates: SpreadsheetDateStyles): String {
            if (body.isBlank()) return ""
            return when (TYPE.find(attrs)?.groupValues?.get(1)) {
                "inlineStr" -> T.find(body)?.let { unescape(it.groupValues[1]) } ?: ""
                "s" -> V.find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                else -> {
                    val raw = (V.find(body) ?: T.find(body))?.let { unescape(it.groupValues[1]) } ?: ""
                    val style = STYLE.find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                    // Число в ячейке стиля даты — дата: так её видит человек в Excel (#1418).
                    if (style != null && dates.isDate(style)) excelDate(raw) ?: raw else raw
                }
            }
        }

        /**
         * Дата из числа дней Excel: целая часть — дни от 30.12.1899, дробная — время суток.
         * Не число — не дата, отдаётся как есть.
         */
        fun excelDate(raw: String): String? {
            val serial = raw.trim().toDoubleOrNull() ?: return null
            if (serial < 1 || serial > MAX_SERIAL) return null
            val days = kotlin.math.floor(serial).toLong()
            val date = EXCEL_EPOCH.plusDays(days)
            val seconds = kotlin.math.round((serial - days) * SECONDS_A_DAY).toLong()
            val day = "%02d.%02d.%d".format(date.dayOfMonth, date.monthValue, date.year)
            if (seconds < 60) return day
            val time = java.time.LocalTime.ofSecondOfDay(seconds.coerceAtMost(SECONDS_A_DAY - 1))
            return "%s %02d:%02d".format(day, time.hour, time.minute)
        }

        private val EXCEL_EPOCH: java.time.LocalDate = java.time.LocalDate.of(1899, 12, 30)

        private const val SECONDS_A_DAY = 86_400L

        /** Дальше 31.12.9999 Excel не считает. */
        private const val MAX_SERIAL = 2_958_465.0

        private fun colIndex(attrs: String): Int? {
            val letters = REF.find(attrs)?.groupValues?.get(1) ?: return null
            var index = 0
            for (ch in letters) index = index * 26 + (ch - 'A' + 1)
            return index - 1
        }

        private fun unescape(s: String): String = s
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        /** Папка листов пакета: всё, что лежит прямо в ней, — лист книги. */
        private const val WORKSHEETS = "xl/worksheets/"

        private val WORKSHEET = Regex("""xl/worksheets/sheet(\d+)\.xml""")
        private val SHEET = Regex("""<sheet\b([^>]*)>""")
        private val NAME = Regex("""\bname="([^"]*)"""")

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
        private val STYLE = Regex("""\bs="(\d+)"""")
        private val REF = Regex("""\br="([A-Z]+)\d+"""")
    }
}

/**
 * Стили дат книги (#1418): индексы `cellXfs`, чьи числовые форматы — даты.
 *
 * Дату книга хранит числом, а датой её называет формат ячейки: встроенные форматы Excel
 * 14–22, 27–36, 45–47 и 50–58 — даты и время, свой формат (`numFmts`) — дата, если в его коде
 * есть буквы дня, месяца, года или часа вне кавычек и квадратных скобок («0.000» — число,
 * «dd/mm/yyyy;@» — дата).
 */
class SpreadsheetDateStyles private constructor(private val dateXfs: Set<Int>) {

    fun isDate(styleIndex: Int): Boolean = styleIndex in dateXfs

    companion object {

        val NONE = SpreadsheetDateStyles(emptySet())

        fun parse(stylesXml: String?): SpreadsheetDateStyles {
            if (stylesXml.isNullOrBlank()) return NONE
            val custom = NUM_FMT.findAll(stylesXml).associate { fmt ->
                fmt.groupValues[1].toInt() to fmt.groupValues[2]
            }
            val xfs = CELL_XFS.find(stylesXml)?.groupValues?.get(1) ?: return NONE
            val dates = XF.findAll(xfs).mapIndexedNotNull { index, xf ->
                val id = NUM_FMT_ID.find(xf.value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                index.takeIf { id in BUILT_IN_DATES || custom[id]?.let(::looksLikeDate) == true }
            }.toSet()
            return SpreadsheetDateStyles(dates)
        }

        /** Код формата — дата, если после снятия литералов в нём остались буквы дня/месяца/года/часа. */
        fun looksLikeDate(formatCode: String): Boolean {
            val bare = formatCode.replace(QUOTED, "").replace(BRACKETED, "")
            return bare.any { it in DATE_LETTERS }
        }

        private val BUILT_IN_DATES: Set<Int> = ((14..22) + (27..36) + (45..47) + (50..58)).toSet()

        private const val DATE_LETTERS = "dmyhDMYH"

        private val NUM_FMT = Regex("""<numFmt\b(?=[^>]*\bnumFmtId="(\d+)")(?=[^>]*\bformatCode="([^"]*)")[^>]*/?>""")
        private val CELL_XFS = Regex("""<cellXfs\b[^>]*>(.*?)</cellXfs>""", RegexOption.DOT_MATCHES_ALL)
        private val XF = Regex("""<xf\b[^>]*?(?:/>|>.*?</xf>)""", RegexOption.DOT_MATCHES_ALL)
        private val NUM_FMT_ID = Regex("""\bnumFmtId="(\d+)"""")
        private val QUOTED = Regex(""""[^"]*"""")
        private val BRACKETED = Regex("""\[[^\]]*]""")
    }
}
