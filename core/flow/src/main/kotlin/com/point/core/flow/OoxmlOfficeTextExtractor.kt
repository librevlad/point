package com.point.core.flow

import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

class OoxmlOfficeTextExtractor : OfficeTextExtractor {

    override suspend fun extractText(obj: PointObject): String = withContext(Dispatchers.IO) {
        val read = read(obj)
        val rows = OoxmlSpreadsheetReader.rowsOf(
            read.sheets,
            read.shared,
            OoxmlSpreadsheetReader.sheetOrder(read.workbook, read.relations),
        )
        when {
            rows.isNotEmpty() ->
                rows.joinToString("\n") { row -> row.joinToString("\t").trimEnd() }
                    .lines().filter(String::isNotBlank).joinToString("\n")

            // Текст презентации — это её слайды по порядку (#1105): каждый со своей строки.
            // Одним куском было не разобрать, где кончился первый слайд и начался второй.
            read.slides.isNotEmpty() -> read.slides.values.filter { it.isNotBlank() }.joinToString("\n")

            else -> read.words.toString().replace(MULTISPACE, " ").trim()
        }
    }

    /**
     * Слайды презентации по порядку — части одного объекта (#1105).
     *
     * Каждый слайд идёт со своим номером: номер — знание самого слайда, а не его место в
     * списке. В списке — только те слайды, что в файле действительно нашлись: сплошным рядом
     * от первого номера до самого большого крошечный файл с единственной частью
     * `slide2000000000.xml` заставлял бы телефон по одному тапу человека выложить два
     * миллиарда пустых строк. Не презентация — пустой список.
     *
     * Оборванный архив целым не притворяется: обрыв чтения доходит до вызывающего
     * (инвариант 8) — три части побитой колоды из десяти были бы неполным объектом, выданным
     * за полный.
     */
    override suspend fun slides(obj: PointObject): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        val read = read(obj)
        read.broken?.let { throw it }
        read.slides.map { (number, text) -> number to text }
    }

    /**
     * Один проход по файлу: он читается с диска, и второй раз ради того же ответа его
     * открывать незачем.
     *
     * Обрыв чтения не выдаётся за честный конец файла: он остаётся в [OoxmlParts.broken], и
     * тот, кому куска мало, отказывает вслух. Текст документа при этом берёт то, что успело
     * прочитаться, — прочитанные слова человеку полезны и без остальных.
     */
    private fun read(obj: PointObject): OoxmlParts {
        val parts = OoxmlParts()
        parts.broken = runCatching {
            ZipInputStream(File(obj.uri.value).inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val slide = slideNumberOf(entry.name)
                        when {
                            entry.name == WORD_DOCUMENT ->
                                appendTagText(zis.readBytes().toString(Charsets.UTF_8), "w:t", parts.words)

                            // Слайды складываются по своему номеру, а не по порядку в архиве:
                            // `slide10.xml` лежит там раньше `slide2.xml`, и текст выходил
                            // вперемешку.
                            slide != null ->
                                parts.slides[slide] = tagText(zis.readBytes().toString(Charsets.UTF_8), "a:t")

                            // Таблицу разбирает свой читатель (#997): в общем словаре строк
                            // может не быть вовсе, а чисел там нет никогда. Листы забираются
                            // все (#995): у книги их бывает несколько, и текст второго листа
                            // человеку нужен не меньше, чем текст первого. Порядок вкладок
                            // книга рассказывает о себе сама — им и выходит её текст.
                            entry.name == OoxmlSpreadsheetReader.SHARED_STRINGS ->
                                parts.shared = zis.readBytes().toString(Charsets.UTF_8)
                            entry.name == OoxmlSpreadsheetReader.WORKBOOK ->
                                parts.workbook = zis.readBytes().toString(Charsets.UTF_8)
                            entry.name == OoxmlSpreadsheetReader.WORKBOOK_RELS ->
                                parts.relations = zis.readBytes().toString(Charsets.UTF_8)
                            OoxmlSpreadsheetReader.isWorksheet(entry.name) ->
                                parts.sheets[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }.exceptionOrNull()
        return parts
    }

    /** Номер слайда по имени части архива, или `null` — это не слайд. */
    private fun slideNumberOf(entryName: String): Int? =
        SLIDE_ENTRY.matchEntire(entryName)?.groupValues?.get(1)?.toIntOrNull()

    private fun tagText(xml: String, tag: String): String =
        StringBuilder().also { appendTagText(xml, tag, it) }.toString().replace(MULTISPACE, " ").trim()

    private fun appendTagText(xml: String, tag: String, out: StringBuilder) {
        val regex = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        for (match in regex.findAll(xml)) {
            out.append(unescape(match.groupValues[1])).append(' ')
        }
    }

    private fun unescape(s: String): String = NUMERIC_ENTITY.replace(s) { m ->
        val code = m.groupValues[1].let { body ->
            if (body.startsWith("x") || body.startsWith("X")) {
                body.drop(1).toIntOrNull(16)
            } else {
                body.toIntOrNull()
            }
        }

        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
    }
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")

    /** Части OOXML-файла, собранные за один проход по архиву. */
    private class OoxmlParts {
        val words = StringBuilder()
        val slides = sortedMapOf<Int, String>()
        val sheets = sortedMapOf<String, String>()
        var shared: String? = null
        var workbook: String? = null
        var relations: String? = null

        /** На чём проход оборвался, или `null` — архив дочитан до конца. */
        var broken: Throwable? = null
    }

    private companion object {
        const val WORD_DOCUMENT = "word/document.xml"

        val SLIDE_ENTRY = Regex("ppt/slides/slide(\\d+)\\.xml")

        val MULTISPACE = Regex("\\s{2,}")

        val NUMERIC_ENTITY = Regex("&#(x?[0-9A-Fa-f]+);")
    }
}

/**
 * Почему в документе не нашлось текста (#997).
 *
 * Отказ валил всё на старый формат: современная .xlsx получала «старые .doc и .xls
 * компьютер не открывает» — причину, которая к ней не относится. Формат виден по имени и
 * по mime, и назвать нужно тот, что есть на самом деле.
 */
fun officeTextMissingReason(fileName: String?, mime: String): String =
    if (isModernOffice(fileName, mime)) NO_TEXT_IN_OFFICE else OLD_OFFICE_FORMAT

/** .docx / .xlsx / .pptx — те, что Point открывает сам. */
fun isModernOffice(fileName: String?, mime: String): Boolean {
    val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    val m = mime.lowercase().substringBefore(';').trim()
    return ext in MODERN_OFFICE_EXTS || m.startsWith("application/vnd.openxmlformats-officedocument.")
}

private val MODERN_OFFICE_EXTS = setOf("docx", "xlsx", "pptx")

const val NO_TEXT_IN_OFFICE =
    "В этом документе текста нет — внутри только оформление и картинки. Откройте его целиком, " +
        "чтобы посмотреть глазами"

const val OLD_OFFICE_FORMAT =
    "Это старый формат Office — Point читает .docx, .xlsx и .pptx. Пересохраните документ в одном из них"
