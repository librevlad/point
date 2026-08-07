package com.point.source

import com.point.core.flow.stampedObjectName
import com.point.core.flow.textObjectName

data class Produced(val uri: String, val mime: String, val name: String? = null)

const val EXTRA_OBJECT_NAME = "com.point.source.OBJECT_NAME"

private const val UNKNOWN_MIME = "application/octet-stream"

fun clipToProduced(
    text: String?,
    uri: String?,
    mime: String?,
    textFile: (String) -> String,
): Produced? = when {
    uri != null -> Produced(uri, mime ?: UNKNOWN_MIME)
    !text.isNullOrBlank() -> Produced(textFile(text), "text/plain", textObjectName(text))
    else -> null
}

fun captureToProduced(
    path: String,
    sizeBytes: Long,
    epochMillis: Long = System.currentTimeMillis(),
): Produced? =
    if (sizeBytes > 0) Produced(path, "image/jpeg", stampedObjectName("Снимок", epochMillis)) else null
