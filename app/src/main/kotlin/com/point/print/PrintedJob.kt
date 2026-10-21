package com.point.print

import com.point.core.model.tr
import com.point.source.Produced

fun printedToProduced(path: String, sizeBytes: Long): Produced? =
    if (sizeBytes > 0) Produced(path, "application/pdf") else null

fun printedFileName(label: String?): String {
    val clean = label?.trim()?.replace(Regex("[/\\\\]"), "-").orEmpty()
    return if (clean.isEmpty()) tr("Печать.pdf") else "$clean.pdf"
}
