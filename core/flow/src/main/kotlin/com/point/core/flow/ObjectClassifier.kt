package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState

/**
 * Derives the initial [ObjectState] from zero-cost signals only (MIME, optional
 * file name extension, size). No I/O — this is what the first render (≤ 300 ms)
 * is allowed to use. Richer features (peeking inside a PDF/ZIP) are added
 * asynchronously afterwards.
 *
 * Pure Kotlin, so it is unit-tested directly with no Android runtime.
 */
class ObjectClassifier {

    fun classify(mime: String, sizeBytes: Long = 0L, fileName: String? = null): ObjectState {
        val features = buildSet {
            if (sizeBytes >= LARGE_THRESHOLD_BYTES) add(Feature.LARGE)
        }
        return ObjectState(kindOf(mime, fileName), features)
    }

    private fun kindOf(mime: String, fileName: String?): ObjectKind {
        val m = mime.lowercase().substringBefore(';').trim()
        return when {
            m.startsWith("image/") -> ObjectKind.IMAGE
            m == "application/pdf" -> ObjectKind.PDF
            m == "application/zip" || m == "application/x-zip-compressed" -> ObjectKind.ZIP
            m == "text/uri-list" -> ObjectKind.URL
            m.startsWith("text/") -> ObjectKind.TEXT
            else -> kindFromExtension(fileName)
        }
    }

    private fun kindFromExtension(fileName: String?): ObjectKind {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> ObjectKind.IMAGE
            "pdf" -> ObjectKind.PDF
            "zip" -> ObjectKind.ZIP
            "txt", "md" -> ObjectKind.TEXT
            else -> ObjectKind.UNKNOWN
        }
    }

    companion object {
        /** Above this size, async enrichment is deferred (e.g. a 200 MB zip). */
        const val LARGE_THRESHOLD_BYTES = 20L * 1024 * 1024
    }
}
