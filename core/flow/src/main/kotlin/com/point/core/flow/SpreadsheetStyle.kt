package com.point.core.flow

data class StyledCell(
    val value: String,

    val strike: Boolean = false,

    val corrected: Boolean = false,
    val original: String = "",

    val flagged: Boolean = false,
)

private val CORRECTION = Regex("""~~(.+?)~~\s*(.*)""", RegexOption.DOT_MATCHES_ALL)

fun styleCell(raw: String): StyledCell {
    var s = raw.trim()
    val flagged = s.contains('⚠')
    if (flagged) s = s.replace("⚠", "").trim()

    val m = CORRECTION.find(s)
    if (m != null) {
        val old = m.groupValues[1].trim()
        val new = m.groupValues[2].trim()
        return if (new.isNotEmpty()) {
            StyledCell(new, corrected = true, original = old, flagged = flagged)
        } else {
            StyledCell(old, strike = true, flagged = flagged)
        }
    }
    return StyledCell(s, flagged = flagged)
}
