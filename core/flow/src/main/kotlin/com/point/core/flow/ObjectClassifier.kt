package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState

class ObjectClassifier {

    fun classify(mime: String, sizeBytes: Long = 0L, fileName: String? = null): ObjectState {
        val features = buildSet {
            if (sizeBytes >= LARGE_THRESHOLD_BYTES) add(Feature.LARGE)
        }
        return ObjectState(kindOf(mime, fileName), features)
    }

    private fun kindOf(mime: String, fileName: String?): ObjectKind {
        val m = mime.lowercase().substringBefore(';').trim()
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when {

            m == "inode/directory" -> ObjectKind.COLLECTION

            m in OFFICE_MIMES || ext in OFFICE_EXTS -> ObjectKind.OFFICE
            m.startsWith("image/") -> ObjectKind.IMAGE

            m.startsWith("audio/") || m == "application/ogg" || ext in AUDIO_EXTS -> ObjectKind.AUDIO
            m == "application/pdf" -> ObjectKind.PDF
            m in ARCHIVE_MIMES || ext in ARCHIVE_EXTS -> ObjectKind.ZIP
            m == "text/uri-list" -> ObjectKind.URL
            m.startsWith("text/") -> ObjectKind.TEXT
            else -> kindFromExtension(ext)
        }
    }

    private fun kindFromExtension(ext: String): ObjectKind = when (ext) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp" -> ObjectKind.IMAGE
        "pdf" -> ObjectKind.PDF
        "txt", "md" -> ObjectKind.TEXT
        else -> ObjectKind.UNKNOWN
    }

    private companion object {

        const val LARGE_THRESHOLD_BYTES = 20L * 1024 * 1024

        val OFFICE_MIMES = setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
        )
        val OFFICE_EXTS = setOf("docx", "xlsx", "pptx", "doc", "xls", "ppt")

        val AUDIO_EXTS = setOf(
            "ogg", "oga", "opus", "m4a", "mp3", "wav", "amr", "aac", "flac", "aiff", "aif", "3gp", "wma",
        )

        val ARCHIVE_MIMES = setOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/x-tar",
            "application/gzip",
            "application/x-gzip",
            "application/x-bzip2",
            "application/x-xz",
            "application/x-7z-compressed",
            "application/vnd.rar",
            "application/x-rar-compressed",
            "application/x-rar",
        )
        val ARCHIVE_EXTS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar")
    }
}
