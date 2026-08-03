package com.point.data

import com.point.core.flow.DocBlock
import com.point.core.flow.DocStyle
import com.point.core.flow.DocxWriter
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.EvidenceImage
import com.point.core.flow.MAX_EVIDENCE_CROPS
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
 *
 * Улики (#267): к помеченному фрагменту сюда же кладётся кусок исходного кадра — части
 * `word/media`, отношение в `word/_rels/document.xml.rels` и разметка `w:drawing`. Их нет —
 * файл побайтово тот же, что и раньше.
 */
class OoxmlDocxWriter @Inject constructor(
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
            // Резать — до записи: разметка ссылается на отношение, которого без картинки нет,
            // а неудавшийся кроп не должен оставлять в документе ссылку в никуда.
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

    /**
     * Улики по номерам блоков — те, что реально вырезались.
     *
     * Здесь же второй раз стоят оба правила отбора (#267). Политику держит ядро
     * ([com.point.core.flow.withCropEvidence]), но цену за раздутый файл платит писатель, и он
     * не обязан верить вызывающему: улика идёт только к помеченному фрагменту и только пока их
     * меньше [MAX_EVIDENCE_CROPS].
     */
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

    /** Одна улика в упаковке: свой номер, своя часть `word/media`, своё отношение. */
    private class Crop(val id: Int, val image: EvidenceImage) {
        val relId = "rId$id"
        val part = "media/evidence-$id.${image.extension}"
    }

    /** Hand-rolled run/paragraph properties (#128): enough real formatting for an
     *  editable structured document — no styles part needed. */
    private fun styledDocument(blocks: List<DocBlock>, crops: Map<Int, Crop>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"""")
        // Пространства имён картинки объявляются, только когда картинка есть: документ без улик
        // обязан остаться прежним файлом.
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
            // Зачёркнутое — отдельным прогоном (#247). Модель возвращает правку разметкой
            // «~~было~~ стало», и вывести её в документ дословно значило бы показать человеку
            // тильды вместо того, что на бумаге. Зачёркивание — обычное свойство текста Word:
            // видно, что было и что стало, и снимается одним действием, как и подсветка.
            runsOf(text).forEach { run ->
                append("<w:r>").append(runProps(style, block.uncertain, run.struck))
                append("""<w:t xml:space="preserve">""").append(xml(run.text)).append("</w:t></w:r>")
            }
            append("</w:p>")
            // Улика идёт СРАЗУ за своим фрагментом: рядом — это и есть весь смысл (#267).
            crops[index]?.let { append(drawing(it, block.text)) }
        }
        append("""<w:sectPr/></w:body></w:document>""")
    }

    /** Оформление прогона от стиля блока — то, что одинаково у всех его кусков. */
    private class RunStyle(val bold: Boolean = false, val size: Int? = null)

    /** Кусок абзаца: свой текст и признак «зачёркнут на бумаге». */
    private class Run(val text: String, val struck: Boolean)

    /**
     * Абзац, разложенный на прогоны по забору `~~…~~` (та же разметка правок, что у ячейки
     * таблицы, — `com.point.core.flow.styleCell`).
     *
     * Забора нет — ровно один прогон с прежним текстом, то есть прежний файл побайтово. Незакрытый
     * забор тоже не режется: половина разметки — не правка, а совпадение символов, и делать из неё
     * зачёркивание значило бы угадывать.
     */
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

    /**
     * Свойства прогона в порядке схемы OOXML (`CT_RPr`): жирность, зачёркивание, размер, подсветка.
     * Порядок не косметика — Word считает пакет с переставленными свойствами битым.
     *
     * Неуверенное подсвечивается прямо в документе (#267): чистый .docx из рукописи тихо врёт —
     * прочитанное в нём неотличимо от угаданного. Подсветка снимается одним действием, когда
     * человек вычитал, и Word остаётся нормальным Word.
     */
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

    /**
     * Картинка отдельным абзацем сразу под фрагментом.
     *
     * `wp:extent` меряется в EMU (914400 на дюйм), поэтому пиксели переводятся из расчёта 96 dpi
     * и ширина зажимается по колонке текста — полоса ведомости в 4000 px иначе уехала бы за поля,
     * и Word показал бы обрезок. Пропорция сохраняется: улика, растянутая по вертикали, читается
     * хуже исходника, а её работа — читаться.
     */
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

    /** Отношения документа: у каждой картинки своё, иначе `r:embed` указывает в никуда. */
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

    /** Тот же список типов плюс `Default` на каждое расширение картинок — без него Word считает
     *  пакет битым. Улик нет — строка ровно прежняя. */
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

    /** То же экранирование плюс кавычка: значение едет в атрибут, а не в текст. */
    private fun attr(value: String): String = xml(value).replace("\"", "&quot;")

    private companion object {
        /** Забор зачёркнутого в ответе модели: «~~было~~ стало». Нежадный — правок в абзаце бывает
         *  несколько, и жадный забор съел бы всё между первой и последней. */
        val STRIKE = Regex("""~~(.+?)~~""", RegexOption.DOT_MATCHES_ALL)

        /** 96 dpi: столько EMU в пикселе (914400 EMU в дюйме). */
        const val EMU_PER_PX = 9525L

        /** Шесть дюймов — колонка текста A4 со стандартными полями Word. */
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
