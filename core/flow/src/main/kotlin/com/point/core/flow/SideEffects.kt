package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ScratchRef

interface Sharer {
    suspend fun share(obj: PointObject)

    suspend fun shareAll(objs: List<PointObject>)
}

interface Viewer {
    suspend fun view(obj: PointObject)
}

data class AppTarget(
    val label: String,
    val packageName: String,
    val activity: String,
    val via: String? = null,
)

interface AppLauncher {

    suspend fun handlers(obj: PointObject): List<AppTarget>

    suspend fun handlersForMime(mime: String): List<AppTarget>

    suspend fun launch(target: AppTarget, obj: PointObject)
}

interface Exporter {

    suspend fun export(obj: PointObject): String
}

interface UrlOpener {
    suspend fun open(url: String)
}

interface Clipboard {
    suspend fun copy(text: String, label: String)
}

interface CalendarInserter {
    suspend fun insertEvent(title: String)
}

interface ContactInserter {
    suspend fun insertContact(phone: String?, email: String?)
}

interface PdfTextExtractor {
    suspend fun extractText(obj: PointObject): String
}

interface SpreadsheetWriter {

    suspend fun write(
        rows: List<List<String>>,
        candidates: Map<Pair<Int, Int>, List<String>> = emptyMap(),
    ): ScratchRef

    suspend fun write(plan: SheetPlan): ScratchRef = write(plan.rows, plan.candidates)
}

interface SpreadsheetReader {
    suspend fun readRows(obj: PointObject): List<List<String>>
}

enum class DocStyle { TITLE, HEADING, BULLET, NORMAL }

data class DocBlock(
    val text: String,
    val style: DocStyle,

    val uncertain: Boolean = false,

    val evidence: CropEvidence? = null,
)

fun uncertainInExport(text: String, mode: ReadingMode): Boolean =
    text.contains('⚠') || text.contains(STRIKE_FENCE) ||
        (mode == ReadingMode.HANDWRITTEN && text.any(Char::isDigit))

const val STRIKE_FENCE = "~~"

fun List<DocBlock>.withReadingNote(mode: ReadingMode): List<DocBlock> {
    if (mode != ReadingMode.HANDWRITTEN || isEmpty()) return this
    val note = if (any { it.uncertain }) "$HANDWRITTEN_NOTE $HANDWRITTEN_MARKS" else HANDWRITTEN_NOTE
    return listOf(DocBlock(note, DocStyle.NORMAL)) + this
}

const val HANDWRITTEN_NOTE =
    "Это рукопись: текст ниже прочитан по снимку, а не взят из файла, — ошибка чтения возможна " +
        "в любом слове. Сверьте с оригиналом."

const val HANDWRITTEN_MARKS = "Жёлтым отмечено то, что стоит проверить в первую очередь."

interface DocxWriter {

    suspend fun write(paragraphs: List<String>): ScratchRef

    suspend fun writeStyled(blocks: List<DocBlock>): ScratchRef = write(blocks.map { it.text })
}

interface QrEncoder {

    suspend fun encode(text: String): ScratchRef
}

interface BackgroundRemover {
    suspend fun cutout(imagePath: String): ScratchRef
}

interface QrReader {
    suspend fun decode(imagePath: String): String?
}

interface ImageCompositor {

    suspend fun composite(subjectPath: String, backgroundPath: String): ScratchRef

    suspend fun blur(imagePath: String): ScratchRef
}

interface PdfRasterizer {

    suspend fun rasterize(obj: PointObject): ScratchRef

    suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef?
}

interface OfficeTextExtractor {
    suspend fun extractText(obj: PointObject): String
}

interface ArchiveExtractor {

    suspend fun extract(obj: PointObject): ScratchRef
}
