package com.point.core.flow

/**
 * Cleaning up what OCR reads off a **screenshot** before anything treats it as content (#233).
 *
 * A screenshot carries the phone's own furniture at the top: the clock, the battery, the signal
 * bars. OCR reads it like any other text, and then the clock becomes «дата 15:12» — a fact about
 * the phone, presented as a fact about the parcel. Since #222 шаг 4 it is worse than a wrong line:
 * it is a tappable `Date` object offering to put a meaningless time in the calendar.
 *
 * Verified against real device output (2026-07-30, six screenshots):
 * ```
 * 15:12 © “3! @Э        ← Nova Poshta
 * 11:58 ке              ← Viber
 * 09:24 Ж © 45+!        ← WhatsApp
 * 11:49 3; all Sil ED   ← Nova Poshta
 * ```
 * The shape is stable across apps: a bare time, then symbols and fragments, and never a word.
 */

/** A leading `HH:MM`, with whatever OCR made of the icons that followed it. */
private val STATUS_BAR = Regex("""^\s*\d{1,2}[:.]\d{2}\b(.*)$""")

/** Any real word. Three letters or fewer can be an icon misread; four cannot. */
private val REAL_WORD = Regex("""[\p{L}]{4,}""")

/**
 * Drops the status-bar line from recognised text, or returns it unchanged.
 *
 * Deliberately only the **first** line, and only when what follows the time is not a word.
 * «15:12 Встреча с Петром» is a real first line and stays: a note that happens to begin with a
 * time must not lose it. A chat's own message timestamps further down are left alone too —
 * they are content, however clock-shaped.
 */
fun stripStatusBar(text: String): String {
    val lines = text.lineSequence().toList()
    val first = lines.firstOrNull() ?: return text
    val rest = STATUS_BAR.matchEntire(first)?.groupValues?.get(1) ?: return text
    if (REAL_WORD.containsMatchIn(rest)) return text
    return lines.drop(1).joinToString("\n")
}
