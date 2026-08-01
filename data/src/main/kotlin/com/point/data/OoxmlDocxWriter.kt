package com.point.data

import com.point.core.flow.DocxWriter
import com.point.core.flow.ObjectStore
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Writes an editable `.docx` by hand — a ZIP of the three OOXML parts a wordprocessing document
 * needs, one `<w:p>` per paragraph. No Apache POI: mirrors [OoxmlSpreadsheetWriter]. Enough for
 * PDF/text → Word, where the value is an editable document, not fidelity.
 */
class OoxmlDocxWriter @Inject constructor(
    private val store: ObjectStore,
) : DocxWriter {

    override suspend fun write(paragraphs: List<String>): ScratchRef = withContext(Dispatchers.IO) {
        val ref = store.newScratchFile("docx")
        ZipOutputStream(File(ref.value).outputStream().buffered()).use { zip ->
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("word/document.xml", document(paragraphs))
        }
        ref
    }

    override suspend fun writeStyled(blocks: List<com.point.core.flow.DocBlock>): ScratchRef =
        withContext(Dispatchers.IO) {
            val ref = store.newScratchFile("docx")
            ZipOutputStream(File(ref.value).outputStream().buffered()).use { zip ->
                zip.put("[Content_Types].xml", CONTENT_TYPES)
                zip.put("_rels/.rels", ROOT_RELS)
                zip.put("word/document.xml", styledDocument(blocks))
            }
            ref
        }

    /** Hand-rolled run/paragraph properties (#128): enough real formatting for an
     *  editable structured document — no styles part needed. */
    private fun styledDocument(blocks: List<com.point.core.flow.DocBlock>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        (blocks.ifEmpty { listOf(com.point.core.flow.DocBlock("", com.point.core.flow.DocStyle.NORMAL)) }).forEach { block ->
            val (pPr, rPr, text) = when (block.style) {
                com.point.core.flow.DocStyle.TITLE ->
                    Triple("""<w:pPr><w:spacing w:after="240"/></w:pPr>""", """<w:rPr><w:b/><w:sz w:val="48"/></w:rPr>""", block.text)
                com.point.core.flow.DocStyle.HEADING ->
                    Triple("""<w:pPr><w:spacing w:before="240" w:after="120"/></w:pPr>""", """<w:rPr><w:b/><w:sz w:val="32"/></w:rPr>""", block.text)
                com.point.core.flow.DocStyle.BULLET ->
                    Triple("""<w:pPr><w:ind w:left="720"/></w:pPr>""", "", "• " + block.text)
                com.point.core.flow.DocStyle.NORMAL -> Triple("", "", block.text)
            }
            // Неуверенное подсвечивается прямо в документе (#267): чистый .docx из рукописи
            // тихо врёт — прочитанное в нём неотличимо от угаданного. Подсветка снимается
            // одним действием, когда человек вычитал, и Word остаётся нормальным Word.
            val mark = if (block.uncertain) """<w:highlight w:val="yellow"/>""" else ""
            val props = if (mark.isEmpty()) {
                rPr
            } else if (rPr.isEmpty()) {
                "<w:rPr>" + mark + "</w:rPr>"
            } else {
                rPr.replace("</w:rPr>", mark + "</w:rPr>")
            }
            append("<w:p>").append(pPr)
            append("<w:r>").append(props)
            append("""<w:t xml:space="preserve">""").append(xml(text)).append("</w:t></w:r></w:p>")
        }
        append("""<w:sectPr/></w:body></w:document>""")
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun document(paragraphs: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        // An empty list still needs one paragraph so Word opens a valid (blank) document.
        (paragraphs.ifEmpty { listOf("") }).forEach { para ->
            append("""<w:p><w:r><w:t xml:space="preserve">""")
            append(xml(para))
            append("""</w:t></w:r></w:p>""")
        }
        append("""<w:sectPr/></w:body></w:document>""")
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """</Types>"""

        val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" """ +
            """Target="word/document.xml"/></Relationships>"""
    }
}
