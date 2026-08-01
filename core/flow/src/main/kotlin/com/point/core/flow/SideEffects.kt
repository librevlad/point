package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ScratchRef

/**
 * Activity-free side-effect contracts for terminal executors. Implemented in
 * :data with the application context (no Activity needed), so ShareExecutor /
 * SaveExecutor stay independent and testable with fakes.
 */

/** Launches the system share sheet for an object. */
interface Sharer {
    suspend fun share(obj: PointObject)

    /** Share several objects at once in one sheet (ACTION_SEND_MULTIPLE). */
    suspend fun shareAll(objs: List<PointObject>)
}

/** Opens an object in an external app (the system "open with" / ACTION_VIEW). */
interface Viewer {
    suspend fun view(obj: PointObject)
}

/**
 * One installed app that can act on an object. Strings only — Android-free. [via] is set for a
 * *bridged* target (#79.1): the object can't be opened in this app directly, but it can be after one
 * transform (whose capability id is [via]) — e.g. an image opened as a PDF. Null = direct handler.
 */
data class AppTarget(
    val label: String,
    val packageName: String,
    val activity: String,
    val via: String? = null,
)

/**
 * The device's *real* capabilities: the installed apps that can handle an object, enumerated from
 * `PackageManager.queryIntentActivities`, and a launch of the chosen one. This is what lets Point
 * show your actual apps inline as actions (#66) — hard for a generic OCR/LLM app to reproduce.
 */
interface AppLauncher {
    /** Installed apps that can open [obj], most-relevant first; empty if none. */
    suspend fun handlers(obj: PointObject): List<AppTarget>

    /** Installed apps that can open an object of [mime] — used to find apps reachable via a
     *  transform (#79.1), before the object of that type actually exists. */
    suspend fun handlersForMime(mime: String): List<AppTarget>

    /** Open [obj] in the chosen [target] app. */
    suspend fun launch(target: AppTarget, obj: PointObject)
}

/** Exports an object to shared storage. */
interface Exporter {
    /** @return a short, user-facing location (e.g. "Downloads/report.pdf"). */
    suspend fun export(obj: PointObject): String
}

/** Opens a URL in the system browser. */
interface UrlOpener {
    suspend fun open(url: String)
}

/** Puts text on the system clipboard (a terminal "Скопировать"). Called only while Point is
 *  foreground, so the Android 10+ background-clipboard limit doesn't apply. */
interface Clipboard {
    suspend fun copy(text: String, label: String)
}

/** Opens the system calendar's "new event" screen, pre-filled with [title] (the user sets the time).
 *  ACTION_INSERT isn't a URI scheme, so this can't ride [UrlOpener]. */
interface CalendarInserter {
    suspend fun insertEvent(title: String)
}

/** Extracts plain text from a PDF object (empty if it has none, e.g. a scan). */
interface PdfTextExtractor {
    suspend fun extractText(obj: PointObject): String
}

/** Writes rows of cells to a minimal .xlsx in the scratch store (no dependency —
 *  a hand-rolled OOXML package, mirroring the dependency-free OOXML reader). */
interface SpreadsheetWriter {
    /** @return the scratch .xlsx file holding [rows] on one sheet. [candidates] maps a flagged
     *  `(row, col)` to the models' distinct readings (#200) — emitted as an in-cell dropdown so the
     *  user picks the right one instead of retyping. Empty = a plain sheet (back-compatible). */
    suspend fun write(
        rows: List<List<String>>,
        candidates: Map<Pair<Int, Int>, List<String>> = emptyMap(),
    ): ScratchRef
}

/** Reads an OOXML `.xlsx` back into rows of cell text — the inverse of [SpreadsheetWriter].
 *  Handles the three ways a cell carries text: inline strings (what our writer emits), the
 *  shared-strings table (what Excel/Sheets emit) and bare numeric values. Empty when the file
 *  is not a readable OOXML spreadsheet. */
interface SpreadsheetReader {
    suspend fun readRows(obj: PointObject): List<List<String>>
}

/** Writes paragraphs to a minimal, editable .docx in the scratch store — a hand-rolled OOXML
 *  wordprocessing package (no Apache POI), mirroring [SpreadsheetWriter]. */
/** A structured document block (#128) — what «В Word+» lays the raw text out into. */
enum class DocStyle { TITLE, HEADING, BULLET, NORMAL }

data class DocBlock(
    val text: String,
    val style: DocStyle,
    /**
     * Фрагмент, который человек обязан вычитать глазами (#267): чистый уверенно выглядящий
     * .docx из рукописи — документ, который **тихо врёт**, потому что прочитанное в нём
     * неотличимо от угаданного. Экспорт неуверенность не сглаживает, а переносит в документ:
     * помеченное видно при вычитке и снимается одним действием, когда человек проверил.
     */
    val uncertain: Boolean = false,
)

/**
 * Что в этом фрагменте нельзя отдавать как прочитанное (#267).
 *
 * На рукописи ([ReadingMode.HANDWRITTEN]) **цифры помечаются всегда**: правило «модель не
 * трогает цифры» (#236) там структурно неисполнимо — символы предложила зрячая модель, и
 * честная пометка остаётся единственной заменой гарантии. Маркер неуверенности самой модели
 * (⚠) переносится в документ на любом режиме — он и означает «я не уверена».
 */
fun uncertainInExport(text: String, mode: ReadingMode): Boolean =
    text.contains('⚠') || (mode == ReadingMode.HANDWRITTEN && text.any(Char::isDigit))

interface DocxWriter {
    /** @return the scratch .docx holding [paragraphs], one `<w:p>` each. */
    suspend fun write(paragraphs: List<String>): ScratchRef

    /** Styled variant (#128): titles bold+large, headings bold, bullets indented with a
     *  marker. Default keeps old implementations compiling by flattening to plain text. */
    suspend fun writeStyled(blocks: List<DocBlock>): ScratchRef = write(blocks.map { it.text })
}

/** Encodes text into a QR-code PNG in the scratch store — a pure on-device type transform
 *  (text/url → image) that opens the whole image action set (save / share / open). */
interface QrEncoder {
    /** @return the scratch .png holding a QR code for [text]. Throws if [text] is too long to fit. */
    suspend fun encode(text: String): ScratchRef
}

/** Cuts the main subject out of a photo (on-device segmentation), returning a transparent-background
 *  PNG in the scratch store — the basis for "remove/replace background". Throws if no subject is found. */
interface BackgroundRemover {
    suspend fun cutout(imagePath: String): ScratchRef
}

/** Decodes a QR code from an image (on-device). @return its text, or null if the image has no QR. */
interface QrReader {
    suspend fun decode(imagePath: String): String?
}

/** On-device image compositing — the pieces for replacing / blurring a background. */
interface ImageCompositor {
    /** Draw [subjectPath] (a transparent-PNG cutout) over [backgroundPath] (centre-cropped to fill
     *  the subject's frame). @return the composited PNG in scratch. */
    suspend fun composite(subjectPath: String, backgroundPath: String): ScratchRef

    /** @return a heavily-blurred copy of [imagePath] in scratch — a background for the portrait effect. */
    suspend fun blur(imagePath: String): ScratchRef
}

/** Renders each page of a PDF to an image in a fresh scratch directory. */
interface PdfRasterizer {
    /** @return the scratch directory holding the page images (page-001.jpg …),
     *  which may be empty if the PDF has no pages / cannot be rendered. */
    suspend fun rasterize(obj: PointObject): ScratchRef

    /** Render ONLY the first page — cheap enough for the header preview (#114).
     *  @return the page image, or null when the PDF has no pages / cannot be read. */
    suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef?
}

/** Extracts text from an OOXML office document (docx/xlsx/pptx). Empty if none
 *  (e.g. a legacy binary .doc/.xls/.ppt which needs a heavier parser). */
interface OfficeTextExtractor {
    suspend fun extractText(obj: PointObject): String
}

/** Unpacks an archive (zip/tar/gz/bz2/xz) into the scratch store. */
interface ArchiveExtractor {
    /** Unpacks into a fresh scratch directory. @return that directory (may be
     *  empty if the archive was unsupported / empty — the caller checks). */
    suspend fun extract(obj: PointObject): ScratchRef
}
