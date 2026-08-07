package com.point.core.flow

import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

class OoxmlOfficeTextExtractor : OfficeTextExtractor {

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

    private fun textTagFor(entryName: String): String? = when {
        entryName == "word/document.xml" -> "w:t"
        entryName == "xl/sharedStrings.xml" -> "t"
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
