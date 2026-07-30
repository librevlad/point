package com.point.core.flow

/**
 * Giving a truncated address back the rest of its line (#236) — on device, for free.
 *
 * Measured on the owner's parcel screenshot: the text says `Олексйвка, вул. Сонячна, 15 ©`, ML Kit
 * hands back `вул. Сонячна, 15`, and the map then offers four «вул. Сонячна, 15» across Ukraine.
 * The settlement was on screen the whole time — it simply did not survive extraction.
 *
 * Deliberately **not** a model. What was lost is on the same line, in the same words; asking a
 * paid model to read a line we already have would be slower, cost a quota, and could disagree.
 * Repairing the letters OCR mangled is a different job, and that one does need a model.
 */

/** How much text may be prepended. A settlement, not a paragraph. «Білгород-Дністровський,»
 *  is 23 characters — the longest real one in the samples. */
private const val MAX_PREFIX = 32

/** A settlement is one name, sometimes with a «с.»/«м.» in front. Never a phrase. */
private const val MAX_PREFIX_WORDS = 3

/** How long the whole line may be before it stops looking like one address. */
private const val MAX_LINE = 120

/** Icons and separators OCR leaves around a line. Letters and digits are content; these are not. */
private val EDGE_NOISE = Regex("""^[^\p{L}\p{Nd}]+|[^\p{L}\p{Nd}]+$""")

/** A place name: letters, digits and the punctuation that lives inside addresses. */
private val PLACE_LIKE = Regex("""^[\p{L}\p{Nd}\s.\-'’«»/№]+,$""")

/**
 * Returns [value] with whatever preceded it on its own line, when that prefix reads like part of
 * the same address — otherwise [value] unchanged.
 *
 * Conservative on purpose. It only ever *prepends* what is already in the document, only from the
 * line the value itself came from, and only when the result still looks like one address. A value
 * it cannot find, or a line that carries other content, is left alone: a wrong address sends the
 * user to the wrong town, which is worse than an incomplete one sending them to a chooser.
 */
fun expandAddressToLine(value: String, text: String): String {
    val needle = value.trim()
    if (needle.isEmpty()) return value

    val line = text.lineSequence()
        .map { it.replace(EDGE_NOISE, "").trim() }
        .firstOrNull { it.contains(needle) && it.length <= MAX_LINE }
        ?: return value

    // Only a prefix is restored. Anything after the value on the line is not part of the address —
    // on a real screen that is the next field, a price, a chevron.
    val prefix = line.substringBefore(needle).trim()
    if (prefix.isEmpty() || prefix.length > MAX_PREFIX) return value
    if (prefix.split(Regex("""\s+""")).size > MAX_PREFIX_WORDS) return value
    // The comma is what tells a settlement from a sentence. Every real sample reads
    // «Олексйвка, вул. Сонячна, 15»; «Позвони мне когда доедешь до вул. Сонячна, 15» does not,
    // and without this it would become part of the address.
    if (!PLACE_LIKE.matches(prefix)) return value

    return "$prefix $needle".replace(Regex("""\s+"""), " ").trim()
}
