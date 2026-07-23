package com.point

/**
 * Cleans a raw text preview for display. Collapses long base64 blobs — e.g. the inline
 * `PHOTO;BASE64:…` in a shared vCard, otherwise pages of `A–Za–z0–9+/=` gibberish that bury the
 * readable contact fields — into a single `…`. Real text always carries spaces and punctuation, so
 * its base64-char ratio stays below the threshold and it is never touched. Once inside a blob the
 * shorter wrapped continuation lines (the last is often < 40 chars) are swallowed too, until real
 * text resumes. Preview-only: the stored object is never modified.
 */
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

private const val BLOB_MIN = 40 // a line this long that is almost all base64 opens a blob
private const val CONT_MIN = 8 // once inside, a shorter base64 line still continues it
private const val BLOB_RATIO = 0.95
private val BASE64_CHARS: Set<Char> = buildSet {
    ('A'..'Z').forEach(::add)
    ('a'..'z').forEach(::add)
    ('0'..'9').forEach(::add)
    add('+'); add('/'); add('=')
}
