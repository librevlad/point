package com.point.core.flow

import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OoxmlDocxWriter(
    private val store: ObjectStore,
    private val cropper: EvidenceCropper,
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

    override suspend fun writeStyled(blocks: List<DocBlock>): ScratchRef =
        withContext(Dispatchers.IO) {

            val crops = crops(blocks)
            val ref = store.newScratchFile("docx")
            ZipOutputStream(File(ref.value).outputStream().buffered()).use { zip ->
                zip.put("[Content_Types].xml", contentTypes(crops.values))
                zip.put("_rels/.rels", ROOT_RELS)
                if (crops.isNotEmpty()) zip.put("word/_rels/document.xml.rels", documentRels(crops.values))
                zip.put("word/document.xml", styledDocument(blocks, crops))
                crops.values.forEach { zip.put("word/${it.part}", it.image.bytes) }
            }
            ref
        }

    private suspend fun crops(blocks: List<DocBlock>): Map<Int, Crop> =
        blocks.withIndex()
            .filter { (_, block) -> block.uncertain && block.evidence != null }
            .take(MAX_EVIDENCE_CROPS)
            .mapNotNull { (index, block) ->
                val evidence = block.evidence ?: return@mapNotNull null
                val image = cropper.crop(evidence) ?: return@mapNotNull null
                if (image.widthPx <= 0 || image.heightPx <= 0) null else index to image
            }
            .mapIndexed { ordinal, (index, image) -> index to Crop(ordinal + 1, image) }
            .toMap()

    private class Crop(val id: Int, val image: EvidenceImage) {
        val relId = "rId$id"
        val part = "media/evidence-$id.${image.extension}"
    }

    private fun styledDocument(blocks: List<DocBlock>, crops: Map<Int, Crop>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"""")

        if (crops.isNotEmpty()) append(DRAWING_NAMESPACES)
        append("><w:body>")
        (blocks.ifEmpty { listOf(DocBlock("", DocStyle.NORMAL)) }).forEachIndexed { index, block ->
            val (pPr, style, text) = when (block.style) {
                DocStyle.TITLE ->
                    Triple("""<w:pPr><w:spacing w:after="240"/></w:pPr>""", RunStyle(bold = true, size = 48), block.text)
                DocStyle.HEADING ->
                    Triple("""<w:pPr><w:spacing w:before="240" w:after="120"/></w:pPr>""", RunStyle(bold = true, size = 32), block.text)
                DocStyle.BULLET ->
                    Triple("""<w:pPr><w:ind w:left="720"/></w:pPr>""", RunStyle(), "• " + block.text)
                DocStyle.NORMAL -> Triple("", RunStyle(), block.text)
            }
            append("<w:p>").append(pPr)

            runsOf(text).forEach { run ->
                append("<w:r>").append(runProps(style, block.uncertain, run.struck))
                append("""<w:t xml:space="preserve">""").append(xml(run.text)).append("</w:t></w:r>")
            }
            append("</w:p>")

            crops[index]?.let { append(drawing(it, block.text)) }
        }
        append("""<w:sectPr/></w:body></w:document>""")
    }

    private class RunStyle(val bold: Boolean = false, val size: Int? = null)

    private class Run(val text: String, val struck: Boolean)

    private fun runsOf(text: String): List<Run> {
        val out = mutableListOf<Run>()
        var at = 0
        for (m in STRIKE.findAll(text)) {
            if (m.range.first > at) out += Run(text.substring(at, m.range.first), struck = false)
            out += Run(m.groupValues[1], struck = true)
            at = m.range.last + 1
        }
        if (out.isEmpty()) return listOf(Run(text, struck = false))
        if (at < text.length) out += Run(text.substring(at), struck = false)
        return out
    }

    private fun runProps(style: RunStyle, uncertain: Boolean, struck: Boolean): String {
        if (!style.bold && style.size == null && !uncertain && !struck) return ""
        return buildString {
            append("<w:rPr>")
            if (style.bold) append("<w:b/>")
            if (struck) append("<w:strike/>")
            style.size?.let { append("""<w:sz w:val="$it"/>""") }
            if (uncertain) append("""<w:highlight w:val="yellow"/>""")
            append("</w:rPr>")
        }
    }

    private fun drawing(crop: Crop, alt: String): String = buildString {
        val cx = minOf(crop.image.widthPx * EMU_PER_PX, MAX_WIDTH_EMU)
        val cy = (cx * crop.image.heightPx / crop.image.widthPx).coerceAtLeast(1L)
        append("<w:p><w:r><w:drawing>")
        append("""<wp:inline distT="0" distB="0" distL="0" distR="0">""")
        append("""<wp:extent cx="$cx" cy="$cy"/>""")
        append("""<wp:docPr id="${crop.id}" name="Улика ${crop.id}" descr="${attr(alt)}"/>""")
        append("""<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">""")
        append("<pic:pic>")
        append("""<pic:nvPicPr><pic:cNvPr id="${crop.id}" name="evidence-${crop.id}"/><pic:cNvPicPr/></pic:nvPicPr>""")
        append("""<pic:blipFill><a:blip r:embed="${crop.relId}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""")
        append("""<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""")
        append("""<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>""")
        append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
    }

    private fun documentRels(crops: Collection<Crop>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        crops.forEach {
            append("""<Relationship Id="${it.relId}" """)
            append("""Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" """)
            append("""Target="${it.part}"/>""")
        }
        append("</Relationships>")
    }

    private fun contentTypes(crops: Collection<Crop>): String {
        if (crops.isEmpty()) return CONTENT_TYPES
        val defaults = crops.map { it.image.extension }.distinct().joinToString("") {
            """<Default Extension="$it" ContentType="${mediaType(it)}"/>"""
        }
        return CONTENT_TYPES.replace("<Override", defaults + "<Override")
    }

    private fun mediaType(extension: String): String = when (extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun ZipOutputStream.put(name: String, content: String) = put(name, content.toByteArray(Charsets.UTF_8))

    private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun document(paragraphs: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")

        (paragraphs.ifEmpty { listOf("") }).forEach { para ->
            append("""<w:p><w:r><w:t xml:space="preserve">""")
            append(xml(para))
            append("""</w:t></w:r></w:p>""")
        }
        append("""<w:sectPr/></w:body></w:document>""")
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun attr(value: String): String = xml(value).replace("\"", "&quot;")

    private companion object {

        val STRIKE = Regex("""~~(.+?)~~""", RegexOption.DOT_MATCHES_ALL)

        const val EMU_PER_PX = 9525L

        const val MAX_WIDTH_EMU = 5_486_400L

        val DRAWING_NAMESPACES =
            """ xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"""" +
                """ xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"""" +
                """ xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"""" +
                """ xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture""""

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
