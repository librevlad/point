package com.point.core.flow

import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

class OoxmlOfficeTextExtractor : OfficeTextExtractor {

    override suspend fun extractText(obj: PointObject): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        var shared: String? = null
        var workbook: String? = null
        var relations: String? = null
        val sheets = sortedMapOf<String, String>()
        runCatching {
            ZipInputStream(File(obj.uri.value).inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val tag = textTagFor(entry.name)
                        when {
                            tag != null -> appendTagText(zis.readBytes().toString(Charsets.UTF_8), tag, out)

                            // Таблицу разбирает свой читатель (#997): в общем словаре строк
                            // может не быть вовсе, а чисел там нет никогда. Листы забираются
                            // все (#995): у книги их бывает несколько, и текст второго листа
                            // человеку нужен не меньше, чем текст первого. Порядок вкладок
                            // книга рассказывает о себе сама — им и выходит её текст.
                            entry.name == OoxmlSpreadsheetReader.SHARED_STRINGS ->
                                shared = zis.readBytes().toString(Charsets.UTF_8)
                            entry.name == OoxmlSpreadsheetReader.WORKBOOK ->
                                workbook = zis.readBytes().toString(Charsets.UTF_8)
                            entry.name == OoxmlSpreadsheetReader.WORKBOOK_RELS ->
                                relations = zis.readBytes().toString(Charsets.UTF_8)
                            OoxmlSpreadsheetReader.isWorksheet(entry.name) ->
                                sheets[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        val rows = OoxmlSpreadsheetReader.rowsOf(
            sheets,
            shared,
            OoxmlSpreadsheetReader.sheetOrder(workbook, relations),
        )
        if (rows.isEmpty()) {
            out.toString().replace(MULTISPACE, " ").trim()
        } else {
            rows.joinToString("\n") { row -> row.joinToString("\t").trimEnd() }
                .lines().filter(String::isNotBlank).joinToString("\n")
        }
    }

    private fun textTagFor(entryName: String): String? = when {
        entryName == "word/document.xml" -> "w:t"
        entryName.startsWith("ppt/slides/slide") && entryName.endsWith(".xml") -> "a:t"
        else -> null
    }

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

    private companion object {
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
