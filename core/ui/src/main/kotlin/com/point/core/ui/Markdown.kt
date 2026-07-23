package com.point.core.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * A tiny Markdown → [AnnotatedString] renderer for the AI/text result preview — so an answer reads
 * as headings, bold and bullets instead of raw `###` / `**` / `*`. Deliberately small: headings
 * (`#`/`##`/`###`), inline `**bold**`, and `* `/`- ` bullets. Everything else passes through as
 * plain text. The caller supplies the base colour/size via the `Text` style; this only adds spans.
 */
fun markdownToAnnotated(md: String): AnnotatedString = buildAnnotatedString {
    val lines = md.trim().split("\n")
    lines.forEachIndexed { index, raw ->
        val line = raw.trim()
        when {
            line.startsWith("###") -> heading(line.trimStart('#', ' '), 15.sp)
            line.startsWith("##") -> heading(line.trimStart('#', ' '), 17.sp)
            line.startsWith("#") -> heading(line.trimStart('#', ' '), 20.sp)
            line.startsWith("* ") || line.startsWith("- ") -> {
                append("•  ")
                inline(line.drop(2))
            }
            else -> inline(line) // includes blank lines (just the newline below)
        }
        if (index < lines.lastIndex) append("\n")
    }
}

private fun AnnotatedString.Builder.heading(text: String, size: TextUnit) {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size)) { inline(text) }
}

/** Append [text], turning `**bold**` runs bold; unmatched `**` passes through literally. */
private fun AnnotatedString.Builder.inline(text: String) {
    var i = 0
    while (i < text.length) {
        val open = text.indexOf("**", i)
        if (open < 0) {
            append(text.substring(i))
            return
        }
        append(text.substring(i, open))
        val close = text.indexOf("**", open + 2)
        if (close < 0) {
            append(text.substring(open))
            return
        }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(open + 2, close)) }
        i = close + 2
    }
}
