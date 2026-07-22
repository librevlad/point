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

/** Exports an object to shared storage. */
interface Exporter {
    /** @return a short, user-facing location (e.g. "Downloads/report.pdf"). */
    suspend fun export(obj: PointObject): String
}

/** Opens a URL in the system browser. */
interface UrlOpener {
    suspend fun open(url: String)
}

/** Extracts plain text from a PDF object (empty if it has none, e.g. a scan). */
interface PdfTextExtractor {
    suspend fun extractText(obj: PointObject): String
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
