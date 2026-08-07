package com.point.desktop

fun mimeFor(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "md" -> "text/markdown"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "ppt" -> "application/vnd.ms-powerpoint"
        "doc" -> "application/msword"
        "xls" -> "application/vnd.ms-excel"
        else -> "application/octet-stream"
    }

fun extFor(mime: String): String = when (mime.lowercase().substringBefore(';').trim()) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "application/pdf" -> "pdf"
    "text/plain" -> "txt"
    "text/markdown" -> "md"
    "text/html" -> "html"
    "application/zip" -> "zip"
    else -> "bin"
}
