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
    /** @return the scratch .xlsx file holding [rows] on one sheet. */
    suspend fun write(rows: List<List<String>>): ScratchRef
}

/** Encodes text into a QR-code PNG in the scratch store — a pure on-device type transform
 *  (text/url → image) that opens the whole image action set (save / share / open). */
interface QrEncoder {
    /** @return the scratch .png holding a QR code for [text]. Throws if [text] is too long to fit. */
    suspend fun encode(text: String): ScratchRef
}

/** Renders each page of a PDF to an image in a fresh scratch directory. */
interface PdfRasterizer {
    /** @return the scratch directory holding the page images (page-001.jpg …),
     *  which may be empty if the PDF has no pages / cannot be rendered. */
    suspend fun rasterize(obj: PointObject): ScratchRef
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
