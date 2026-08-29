package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState

class ObjectClassifier {

    fun classify(
        mime: String,
        sizeBytes: Long = 0L,
        fileName: String? = null,
        head: ByteArray = EMPTY_HEAD,
    ): ObjectState {
        val declared = kindOf(mime, fileName, head)

        // Имя и mime промолчали — спрашиваем сами байты (файл без расширения из
        // менеджера иначе становился мёртвым «неизвестным» при читаемом тексте внутри).
        val kind = if (declared == ObjectKind.UNKNOWN) kindFromBytes(head) else declared

        val features = buildSet {
            if (sizeBytes >= LARGE_THRESHOLD_BYTES) add(Feature.LARGE)

            // Годность — часть состояния объекта (#684): нулевой размер виден без единого
            // чтения. COLLECTION — папка, у неё «размер» ничего не значит и это не она.
            if (sizeBytes == 0L && kind != ObjectKind.COLLECTION) add(Feature.UNUSABLE)

            // Из чего состоит документ, видно по тому же нулевому сигналу (#1105): у
            // презентации есть слайды, и дверь к ним открывается с первого экрана, не
            // дожидаясь ни одного прочитанного байта.
            if (kind == ObjectKind.OFFICE && isPresentation(fileName, mime)) add(Feature.IS_PRESENTATION)
        }
        return ObjectState(kind, features)
    }

    private fun kindOf(mime: String, fileName: String?, head: ByteArray): ObjectKind {
        val m = mime.lowercase().substringBefore(';').trim()
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when {

            m == "inode/directory" -> ObjectKind.COLLECTION

            m in OFFICE_MIMES || ext in OFFICE_EXTS -> ObjectKind.OFFICE
            m.startsWith("image/") -> ObjectKind.IMAGE

            m.startsWith("audio/") || m == "application/ogg" || ext in AUDIO_EXTS -> ObjectKind.AUDIO
            m == "application/pdf" -> ObjectKind.PDF
            m in ARCHIVE_MIMES || ext in ARCHIVE_EXTS -> ObjectKind.ZIP

            // Ссылка, переданная файлом, — ссылка только когда в байтах есть адрес (#999):
            // вид по одному MIME давал «Ссылку» без адреса, и ни одно действие ссылки не
            // работало. Адрес не прочитался — это не ссылка, а текстовый файл.
            m == "text/uri-list" && uriListAddress(head) != null -> ObjectKind.URL
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

    private fun kindFromBytes(head: ByteArray): ObjectKind {
        fun startsWith(vararg sig: Int) =
            head.size >= sig.size && sig.withIndex().all { (i, b) -> (head[i].toInt() and 0xFF) == b }

        fun riffSubtype(sub: String) =
            head.size >= 12 && startsWith(0x52, 0x49, 0x46, 0x46) &&
                String(head, 8, 4, Charsets.ISO_8859_1) == sub
        return when {
            startsWith(0x25, 0x50, 0x44, 0x46) -> ObjectKind.PDF
            startsWith(0x50, 0x4B, 0x03, 0x04) -> ObjectKind.ZIP
            startsWith(0x89, 0x50, 0x4E, 0x47) -> ObjectKind.IMAGE
            startsWith(0xFF, 0xD8, 0xFF) -> ObjectKind.IMAGE
            startsWith(0x47, 0x49, 0x46, 0x38) -> ObjectKind.IMAGE
            startsWith(0x42, 0x4D) -> ObjectKind.IMAGE
            riffSubtype("WEBP") -> ObjectKind.IMAGE
            riffSubtype("WAVE") -> ObjectKind.AUDIO
            startsWith(0x4F, 0x67, 0x67, 0x53) -> ObjectKind.AUDIO
            startsWith(0x49, 0x44, 0x33) -> ObjectKind.AUDIO
            startsWith(0x66, 0x4C, 0x61, 0x43) -> ObjectKind.AUDIO
            looksLikeText(head) -> ObjectKind.TEXT
            else -> ObjectKind.UNKNOWN
        }
    }

    // Печатный текст: без NUL и почти без управляющих байтов. Кириллица и любой
    // UTF-8 проходят — старшие байты не считаются управляющими.
    private fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        var control = 0
        for (b in head) {
            val u = b.toInt() and 0xFF
            if (u == 0) return false
            if (u < 0x09 || u in 0x0E..0x1F) control++
        }
        return control * 20 < head.size
    }

    private companion object {

        val EMPTY_HEAD = ByteArray(0)

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
