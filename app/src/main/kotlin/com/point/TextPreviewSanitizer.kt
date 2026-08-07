package com.point

internal fun sanitizeTextPreview(raw: String): String {
    val out = StringBuilder()
    var inBlob = false
    for (line in raw.split("\n")) {
        val t = line.trim()
        val ratio = base64Ratio(t)
        val blob = (t.length >= BLOB_MIN && ratio >= BLOB_RATIO) ||
            (inBlob && t.length >= CONT_MIN && ratio >= BLOB_RATIO)
        if (blob) {
            if (!inBlob) out.append("…\n")
            inBlob = true
        } else {
            inBlob = false
            out.append(line).append('\n')
        }
    }
    return out.toString().trim()
}

private fun base64Ratio(line: String): Double =
    if (line.isEmpty()) 0.0 else line.count { it in BASE64_CHARS }.toDouble() / line.length

private const val BLOB_MIN = 40
private const val CONT_MIN = 8
private const val BLOB_RATIO = 0.95
private val BASE64_CHARS: Set<Char> = buildSet {
    ('A'..'Z').forEach(::add)
    ('a'..'z').forEach(::add)
    ('0'..'9').forEach(::add)
    add('+'); add('/'); add('=')
}
