package com.point.core.flow

/**
 * Как называется этот тип файла и чем он открывается — одно знание в одном месте (#840).
 *
 * Жило в трёх: `desktop/MimeMap.kt` знал двадцать расширений, `FileHistoryStore` — свою
 * короткую таблицу, `ObjectClassifier` — вид объекта. Расходились они молча: формат,
 * добавленный в одном месте, в двух других не появлялся.
 *
 * Таблица бедная нарочно: она отвечает за общеизвестные типы, а не за полный реестр IANA.
 * Незнакомое честно называется потоком байтов — Point не выдумывает тип, которого не знает.
 */
private val MIME_BY_EXT: Map<String, String> = mapOf(
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "webp" to "image/webp",
    "gif" to "image/gif",
    "bmp" to "image/bmp",
    "heic" to "image/heic",
    "pdf" to "application/pdf",
    "txt" to "text/plain",
    "log" to "text/plain",
    "md" to "text/markdown",
    "html" to "text/html",
    "htm" to "text/html",
    "csv" to "text/csv",
    "zip" to "application/zip",
    "rar" to "application/vnd.rar",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "doc" to "application/msword",
    "xls" to "application/vnd.ms-excel",
    "ppt" to "application/vnd.ms-powerpoint",
    "ogg" to "audio/ogg",
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "wav" to "audio/wav",
)

const val UNKNOWN_MIME = "application/octet-stream"

/** Тип файла по его имени. Незнакомое — поток байтов, а не догадка. */
fun mimeForName(fileName: String): String =
    MIME_BY_EXT[fileName.substringAfterLast('.', "").lowercase()] ?: UNKNOWN_MIME

/**
 * Расширение для файла этого типа — чтобы копия открывалась тем же приложением, что и
 * исходник. Пусто — расширения не будет: пустое честнее выдуманного.
 */
fun extensionForMime(mime: String): String {
    val clean = mime.lowercase().substringBefore(';').trim()
    MIME_BY_EXT.entries.firstOrNull { it.value == clean }?.let { return it.key }
    return when {
        clean.startsWith("image/") -> clean.substringAfter('/').substringBefore('+')
        clean.startsWith("text/") -> "txt"
        else -> ""
    }
}

/**
 * Расширение для копии объекта: сначала имя, которое дал человек или система, и лишь потом
 * тип. Имя знает больше — в нём переживают форматы, которых нет в таблице.
 */
fun extensionForFile(name: String?, mime: String): String {
    val fromName = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    if (fromName.isNotBlank() && fromName.length <= MAX_EXT && fromName.all { it.isLetterOrDigit() }) {
        return fromName
    }
    return extensionForMime(mime)
}

private const val MAX_EXT = 5
