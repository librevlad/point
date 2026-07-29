package com.point.core.flow

/**
 * Rendering hints for one spreadsheet cell, parsed from the markers the #200 ocr++ vision prompt
 * emits. The plain [value] is always the text to store; the flags tell a styled writer how to show it.
 */
data class StyledCell(
    val value: String,
    /** «~~52~~» — struck through on the source, no replacement. */
    val strike: Boolean = false,
    /** «~~53~~ 40» — a correction: [value] is the new reading, [original] the crossed-out one. */
    val corrected: Boolean = false,
    val original: String = "",
    /** Trailing «⚠» — the model was not sure: highlight for the user to confirm (never a silent guess). */
    val flagged: Boolean = false,
)

private val CORRECTION = Regex("""~~(.+?)~~\s*(.*)""", RegexOption.DOT_MATCHES_ALL)

/**
 * Parse a raw cell into a [StyledCell]. Recognises (in order): a trailing «⚠» uncertainty flag,
 * a «~~old~~ new» correction, and a bare «~~struck~~». Anything else is a plain value.
 */
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
