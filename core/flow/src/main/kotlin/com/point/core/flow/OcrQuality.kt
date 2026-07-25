package com.point.core.flow

/**
 * Tesseract on a photographed document often returns *gibberish* (symbols and isolated 1-2 char
 * fragments) rather than empty — so a blank-check alone never catches it. This flags that gibberish
 * by two cheap signals: too few letters among the non-space characters, or almost no real
 * (4+ letter) words. Shared by the OCR realizer chain (fall back to cloud) and the OCR enricher
 * (discard silently). A false positive just means less automation — so this errs toward flagging.
 */
fun looksLikeOcrGarbage(text: String): Boolean {
    val nonSpace = text.count { !it.isWhitespace() }
    if (nonSpace < 30) return false // too short to judge — let it through
    val letters = text.count { it.isLetter() }
    val words = Regex("""\p{L}{4,}""").findAll(text).count()
    return letters.toDouble() / nonSpace < 0.6 || words < 3
}
