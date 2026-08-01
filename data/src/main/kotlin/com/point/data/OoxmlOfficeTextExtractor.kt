package com.point.data

import com.point.core.flow.OfficeTextExtractor
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * Dependency-free text extraction from OOXML office files (docx/xlsx/pptx): the
 * container is a zip, so we read the text-bearing XML parts and strip the tags.
 * Legacy binary formats (.doc/.xls/.ppt) are not OOXML and yield "".
 *
 * Pure JVM (zip + regex) — unit-tested directly.
 */
class OoxmlOfficeTextExtractor @Inject constructor() : OfficeTextExtractor {

    override suspend fun extractText(obj: PointObject): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        runCatching {
            ZipInputStream(File(obj.uri.value).inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val tag = textTagFor(entry.name)
                    if (tag != null && !entry.isDirectory) {
                        appendTagText(zis.readBytes().toString(Charsets.UTF_8), tag, out)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        out.toString().replace(MULTISPACE, " ").trim()
    }

    /** The text element for each OOXML part we know how to read. */
    private fun textTagFor(entryName: String): String? = when {
        entryName == "word/document.xml" -> "w:t"                                        // docx
        entryName == "xl/sharedStrings.xml" -> "t"                                       // xlsx
        entryName.startsWith("ppt/slides/slide") && entryName.endsWith(".xml") -> "a:t"  // pptx
        else -> null
    }

    private fun appendTagText(xml: String, tag: String, out: StringBuilder) {
        val regex = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        for (match in regex.findAll(xml)) {
            out.append(unescape(match.groupValues[1])).append(' ')
        }
    }

    /**
     * Именованные сущности XML плюс **числовые ссылки** (#289).
     *
     * Word сохраняет кириллицу как `&#1053;&#1077;...`, когда документ пришёл из чужого
     * редактора или прошёл через конвертер. Пять именованных сущностей их не знают, и текст
     * доезжал до экрана и до PDF дословно в виде «&#1053;&#1077;…» — те самые кракозябры, о
     * которых сообщил владелец. Хуже всего было то, что путь считал это успехом.
     *
     * Порядок важен: `&amp;` разворачивается ПОСЛЕДНИМ, иначе «&amp;#1053;» (экранированный
     * текст про сущность) превратился бы в букву, которой в документе нет.
     */
    private fun unescape(s: String): String = NUMERIC_ENTITY.replace(s) { m ->
        val code = m.groupValues[1].let { body ->
            if (body.startsWith("x") || body.startsWith("X")) {
                body.drop(1).toIntOrNull(16)
            } else {
                body.toIntOrNull()
            }
        }
        // Непредставимое число — не наша выдумка: оставляем как было, чтобы не подменить текст.
        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
    }
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")

    private companion object {
        val MULTISPACE = Regex("\\s{2,}")

        /** `&#1053;` и `&#x41D;` — так Word пишет кириллицу после чужого редактора (#289). */
        val NUMERIC_ENTITY = Regex("&#(x?[0-9A-Fa-f]+);")
    }
}
