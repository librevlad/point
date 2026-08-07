package com.point.core.flow

private const val MAX_PREFIX = 32

private const val MAX_PREFIX_WORDS = 3

private const val MAX_LINE = 120

private val EDGE_NOISE = Regex("""^[^\p{L}\p{Nd}]+|[^\p{L}\p{Nd}]+$""")

private val PLACE_LIKE = Regex("""^[\p{L}\p{Nd}\s.\-'’«»/№]+,$""")

fun expandAddressToLine(value: String, text: String): String {
    val needle = value.trim()
    if (needle.isEmpty()) return value

    val line = text.lineSequence()
        .map { it.replace(EDGE_NOISE, "").trim() }
        .firstOrNull { it.contains(needle) && it.length <= MAX_LINE }
        ?: return value

    val prefix = line.substringBefore(needle).trim()
    if (prefix.isEmpty() || prefix.length > MAX_PREFIX) return value
    if (prefix.split(Regex("""\s+""")).size > MAX_PREFIX_WORDS) return value

    if (!PLACE_LIKE.matches(prefix)) return value

    return "$prefix $needle".replace(Regex("""\s+"""), " ").trim()
}
