package com.point.core.flow

import com.point.core.model.PointObject

/**
 * Activity-free side-effect contracts for terminal executors. Implemented in
 * :data with the application context (no Activity needed), so ShareExecutor /
 * SaveExecutor stay independent and testable with fakes.
 */

/** Launches the system share sheet for an object. */
interface Sharer {
    suspend fun share(obj: PointObject)
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

/** Extracts text from an OOXML office document (docx/xlsx/pptx). Empty if none
 *  (e.g. a legacy binary .doc/.xls/.ppt which needs a heavier parser). */
interface OfficeTextExtractor {
    suspend fun extractText(obj: PointObject): String
}

/** Unpacks an archive (zip/tar/gz/bz2/xz) into the scratch store. */
interface ArchiveExtractor {
    /** @return the number of files written (0 if unsupported / empty). */
    suspend fun extract(obj: PointObject): Int
}
